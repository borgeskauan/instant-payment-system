use std::fs;
use std::path::PathBuf;

use anyhow::{Context, Result, bail};
use tonic::transport::{Certificate, Channel, ClientTlsConfig, Endpoint, Identity};

use crate::notification::{NotificationPayload, parse_notifications};
use crate::notification_proto::notification_gateway_client::NotificationGatewayClient;
use crate::notification_proto::{PullRequest, PullResponse};

pub const MAXIMUM_BATCH: usize = 15;

#[derive(Clone, Debug)]
pub struct GatewayNotification {
    pub communication_id: String,
    pub payload: Vec<u8>,
}

#[derive(Clone, Debug)]
pub struct PullBatch {
    pub notifications: Vec<GatewayNotification>,
    pub next_cursor: String,
}

#[derive(Debug)]
pub struct ProcessedNotification {
    pub communication_id: String,
    pub payloads: Vec<NotificationPayload>,
}

#[derive(Debug, Default)]
pub struct PullState {
    cursor: String,
}

impl PullState {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cursor(&self) -> &str {
        &self.cursor
    }

    pub fn process<F>(&mut self, batch: PullBatch, mut handler: F) -> Result<()>
    where
        F: FnMut(&ProcessedNotification) -> Result<()>,
    {
        if batch.notifications.len() > MAXIMUM_BATCH {
            bail!(
                "notification Pull returned {} messages, protocol maximum is {MAXIMUM_BATCH}",
                batch.notifications.len()
            );
        }
        let mut processed = Vec::with_capacity(batch.notifications.len());
        for notification in batch.notifications {
            if notification.communication_id.is_empty() {
                bail!("notification has no communication_id");
            }
            processed.push(ProcessedNotification {
                communication_id: notification.communication_id,
                payloads: parse_notifications(&notification.payload)?,
            });
        }
        for notification in &processed {
            handler(notification)?;
        }
        self.cursor = batch.next_cursor;
        Ok(())
    }
}

#[derive(Clone, Debug)]
pub struct PullClientConfig {
    address: String,
    ca_cert: PathBuf,
    client_cert_root: PathBuf,
    server_name: String,
}

impl PullClientConfig {
    pub fn new(
        address: impl Into<String>,
        ca_cert: impl Into<PathBuf>,
        client_cert_root: impl Into<PathBuf>,
        server_name: impl Into<String>,
    ) -> Self {
        Self {
            address: address.into(),
            ca_cert: ca_cert.into(),
            client_cert_root: client_cert_root.into(),
            server_name: server_name.into(),
        }
    }

    pub async fn connect(&self, ispb: &str) -> Result<PullClient> {
        if self.address.contains("://") {
            bail!(
                "notification gateway address must be host:port, got {}",
                self.address
            );
        }
        let ca = fs::read(&self.ca_cert)
            .with_context(|| format!("read gateway CA {}", self.ca_cert.display()))?;
        let participant = self.client_cert_root.join(format!("psp-{ispb}"));
        let certificate = fs::read(participant.join("client.crt"))
            .with_context(|| format!("read gateway client certificate for ISPB {ispb}"))?;
        let key = fs::read(participant.join("client.key"))
            .with_context(|| format!("read gateway client key for ISPB {ispb}"))?;
        let tls = ClientTlsConfig::new()
            .ca_certificate(Certificate::from_pem(ca))
            .identity(Identity::from_pem(certificate, key))
            .domain_name(self.server_name.clone());
        let endpoint =
            Endpoint::from_shared(format!("https://{}", self.address))?.tls_config(tls)?;
        let channel = endpoint
            .connect()
            .await
            .with_context(|| format!("connect notification gateway for ISPB {ispb}"))?;
        Ok(PullClient {
            client: NotificationGatewayClient::new(channel),
        })
    }
}

pub struct PullClient {
    client: NotificationGatewayClient<Channel>,
}

impl PullClient {
    pub async fn pull(&mut self, cursor: &str) -> Result<PullBatch> {
        let response: PullResponse = self
            .client
            .pull_notifications(PullRequest {
                cursor: cursor.to_owned(),
            })
            .await?
            .into_inner();
        Ok(PullBatch {
            notifications: response
                .notifications
                .into_iter()
                .map(|notification| GatewayNotification {
                    communication_id: notification.communication_id,
                    payload: notification.payload,
                })
                .collect(),
            next_cursor: response.next_cursor,
        })
    }
}
