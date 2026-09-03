use loadtool_generator::notification::{NotificationPayload, parse_notifications};
use loadtool_generator::pull::{GatewayNotification, PullBatch, PullState};

#[test]
fn parser_accepts_all_current_pacs008_and_pacs002_shapes() {
    let pacs008 = br#"{"CdtTrfTxInf":[
        {"PmtId":{"EndToEndId":"rust-1-1"}},
        {"PmtId":{"EndToEndID":"rust-1-2"}}
    ]}"#;
    assert_eq!(
        parse_notifications(pacs008).unwrap(),
        vec![
            NotificationPayload::Pacs008 {
                end_to_end_id: "rust-1-1".to_owned()
            },
            NotificationPayload::Pacs008 {
                end_to_end_id: "rust-1-2".to_owned()
            }
        ]
    );

    let pacs002 = br#"{"TxInfAndSts":[
        {"OrgnlEndToEndId":"rust-1-1","TxSts":"ACSC","StsRsnInf":[]},
        {"OrgnlEndToEndID":"rust-1-2","TxSts":"RJCT","StsRsnInf":[
            {"Rsn":{"Cd":"AM04"}},{"Rsn":{"Cd":"AB03"}}
        ]}
    ]}"#;
    assert_eq!(
        parse_notifications(pacs002).unwrap(),
        vec![
            NotificationPayload::Pacs002 {
                end_to_end_id: "rust-1-1".to_owned(),
                status: "ACSC".to_owned(),
                reason_codes: vec![]
            },
            NotificationPayload::Pacs002 {
                end_to_end_id: "rust-1-2".to_owned(),
                status: "RJCT".to_owned(),
                reason_codes: vec!["AM04".to_owned(), "AB03".to_owned()]
            }
        ]
    );
}

#[test]
fn parser_rejects_empty_unknown_and_malformed_payloads() {
    for payload in [
        b"{}".as_slice(),
        br#"{"CdtTrfTxInf":[{"PmtId":{}}]}"#,
        br#"{"unknown":true}"#,
        b"{".as_slice(),
    ] {
        assert!(parse_notifications(payload).is_err());
    }
}

#[test]
fn pull_cursor_advances_only_after_the_entire_batch_succeeds() {
    let mut state = PullState::new();
    let batch = PullBatch {
        notifications: vec![
            GatewayNotification {
                communication_id: "c1".to_owned(),
                payload: br#"{"CdtTrfTxInf":[{"PmtId":{"EndToEndId":"rust-1-1"}}]}"#.to_vec(),
            },
            GatewayNotification {
                communication_id: "c2".to_owned(),
                payload: br#"{"CdtTrfTxInf":[{"PmtId":{"EndToEndId":"rust-1-2"}}]}"#.to_vec(),
            },
        ],
        next_cursor: "cursor-2".to_owned(),
    };
    let mut handled = 0;

    let error = state
        .process(batch.clone(), |_| {
            handled += 1;
            if handled == 2 {
                anyhow::bail!("causal admission failed");
            }
            Ok(())
        })
        .expect_err("partial processing must fail");

    assert!(error.to_string().contains("causal admission failed"));
    assert_eq!(state.cursor(), "");

    state
        .process(batch, |_| Ok(()))
        .expect("redelivery is safe");
    assert_eq!(state.cursor(), "cursor-2");
}

#[test]
fn pull_rejects_protocol_violations_before_processing_any_message() {
    let mut state = PullState::new();
    let too_large = PullBatch {
        notifications: (0..16)
            .map(|index| GatewayNotification {
                communication_id: format!("c{index}"),
                payload: b"{}".to_vec(),
            })
            .collect(),
        next_cursor: "bad".to_owned(),
    };
    let mut handled = 0;
    assert!(
        state
            .process(too_large, |_| {
                handled += 1;
                Ok(())
            })
            .is_err()
    );
    assert_eq!(handled, 0);
    assert_eq!(state.cursor(), "");

    let missing_id = PullBatch {
        notifications: vec![GatewayNotification {
            communication_id: String::new(),
            payload: b"{}".to_vec(),
        }],
        next_cursor: "bad".to_owned(),
    };
    assert!(state.process(missing_id, |_| Ok(())).is_err());
    assert_eq!(state.cursor(), "");
}
