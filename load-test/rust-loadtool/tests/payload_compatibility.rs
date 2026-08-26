use rust_loadtool::payload::{pacs002, pacs008};

#[test]
fn pacs008_matches_the_existing_semantic_payload() {
    let body = pacs008(
        "rust-1-42",
        "10000001",
        "20000001",
        12_345,
        "2026-08-26T03:00:00Z",
    )
    .expect("PACS.008");
    let value: serde_json::Value = serde_json::from_slice(&body).expect("valid JSON");

    assert_eq!(value["GrpHdr"]["MsgId"], "MSG-rust-1-42");
    assert_eq!(value["GrpHdr"]["CreDtTm"], "2026-08-26T03:00:00Z");
    assert_eq!(value["GrpHdr"]["NbOfTxs"], 1);
    assert_eq!(value["CdtTrfTxInf"][0]["PmtId"]["EndToEndId"], "rust-1-42");
    assert_eq!(value["CdtTrfTxInf"][0]["IntrBkSttlmAmt"]["value"], 123.45);
    assert_eq!(value["CdtTrfTxInf"][0]["IntrBkSttlmAmt"]["Ccy"], "BRL");
    assert_eq!(
        value["CdtTrfTxInf"][0]["DbtrAgt"]["FinInstnId"]["ClrSysMmbId"]["MmbId"],
        "10000001"
    );
    assert_eq!(
        value["CdtTrfTxInf"][0]["CdtrAgt"]["FinInstnId"]["ClrSysMmbId"]["MmbId"],
        "20000001"
    );
    assert!(value["CdtTrfTxInf"][0]["DbtrAcct"].is_object());
    assert!(value["CdtTrfTxInf"][0]["CdtrAcct"].is_object());
}

#[test]
fn pacs002_matches_the_existing_semantic_payload() {
    let body = pacs002("rust-1-42", "2026-08-26T03:00:01Z").expect("PACS.002");
    let value: serde_json::Value = serde_json::from_slice(&body).expect("valid JSON");

    assert_eq!(value["GrpHdr"]["MsgId"], "STATUS-rust-1-42");
    assert_eq!(value["GrpHdr"]["CreDtTm"], "2026-08-26T03:00:01Z");
    assert_eq!(value["GrpHdr"]["NbOfTxs"], 1);
    assert_eq!(value["TxInfAndSts"][0]["OrgnlEndToEndId"], "rust-1-42");
    assert_eq!(value["TxInfAndSts"][0]["TxSts"], "ACSP");
}

#[test]
fn replay_clones_share_the_same_immutable_bytes() {
    let original = pacs008("rust-1-42", "10000001", "20000001", 100, "now").unwrap();
    let replay = original.clone();

    assert_eq!(original, replay);
    assert!(std::ptr::eq(original.as_ptr(), replay.as_ptr()));
}
