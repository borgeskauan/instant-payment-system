use std::fs::File;
use std::future::Future;
use std::io::BufReader;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Once};
use std::time::Instant;

use anyhow::{Context, Result, anyhow, bail};
use bytes::Bytes;
use http_body_util::{BodyExt, Full};
use hyper::client::conn::http2::SendRequest;
use hyper::{Method, Request, Uri, Version};
use hyper_util::rt::{TokioExecutor, TokioIo};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName};
use rustls::{ClientConfig, RootCertStore};
use tokio::net::TcpStream;
use tokio::sync::{Mutex, OwnedMutexGuard};
use tokio::time::timeout_at;
use tokio_rustls::TlsConnector;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct HttpAttempt {
    pub status: u16,
    protocol_is_http2: Option<bool>,
}

impl HttpAttempt {
    pub fn http2(status: u16) -> Self {
        Self {
            status,
            protocol_is_http2: Some(true),
        }
    }

    pub fn http1(status: u16) -> Self {
        Self {
            status,
            protocol_is_http2: Some(false),
        }
    }

    pub fn failed() -> Self {
        Self {
            status: 0,
            protocol_is_http2: None,
        }
    }

    pub fn used_http1(self) -> bool {
        self.protocol_is_http2 == Some(false)
    }
}

pub trait Http2Client: Send + Sync {
    type Reservation: Http2Reservation;

    fn reserve_until(
        &self,
        deadline: Instant,
    ) -> impl Future<Output = Result<Option<Self::Reservation>>> + Send;
}

pub trait Http2Reservation: Send {
    fn send(
        self,
        path: &str,
        body: Bytes,
        deadline: Instant,
    ) -> impl Future<Output = HttpAttempt> + Send;
}

#[derive(Clone, Debug)]
pub struct Http2Config {
    base_url: String,
    ca_cert: PathBuf,
    client_cert_root: PathBuf,
    server_name: String,
}

impl Http2Config {
    pub fn new(
        base_url: impl Into<String>,
        ca_cert: impl Into<PathBuf>,
        client_cert_root: impl Into<PathBuf>,
        server_name: impl Into<String>,
    ) -> Self {
        Self {
            base_url: base_url.into(),
            ca_cert: ca_cert.into(),
            client_cert_root: client_cert_root.into(),
            server_name: server_name.into(),
        }
    }

    pub async fn connect(&self, ispb: &str) -> Result<PersistentHttp2Client> {
        install_crypto_provider();
        let uri: Uri = self
            .base_url
            .parse()
            .with_context(|| format!("parse central transfer URL {}", self.base_url))?;
        if uri.scheme_str() != Some("https") {
            bail!("central transfer URL must use https: {}", self.base_url);
        }
        let authority = uri
            .authority()
            .context("central transfer URL has no authority")?
            .as_str()
            .to_owned();
        let host = uri.host().context("central transfer URL has no host")?;
        let port = uri.port_u16().unwrap_or(443);

        let mut roots = RootCertStore::empty();
        for certificate in read_certificates(&self.ca_cert, "central transfer CA")? {
            roots
                .add(certificate)
                .context("add central transfer CA certificate")?;
        }
        let participant_root = self.client_cert_root.join(format!("psp-{ispb}"));
        let certificates = read_certificates(&participant_root.join("client.crt"), "client")?;
        let private_key = read_private_key(&participant_root.join("client.key"))?;
        let mut tls = ClientConfig::builder()
            .with_root_certificates(roots)
            .with_client_auth_cert(certificates, private_key)
            .with_context(|| format!("configure client certificate for ISPB {ispb}"))?;
        tls.alpn_protocols = vec![b"h2".to_vec()];

        let tcp = TcpStream::connect((host, port))
            .await
            .with_context(|| format!("connect central transfer at {host}:{port}"))?;
        tcp.set_nodelay(true).context("enable TCP_NODELAY")?;
        let server_name = ServerName::try_from(self.server_name.clone())
            .context("invalid central transfer TLS server name")?;
        let tls_stream = TlsConnector::from(Arc::new(tls))
            .connect(server_name, tcp)
            .await
            .context("central transfer TLS handshake")?;
        if tls_stream.get_ref().1.alpn_protocol() != Some(b"h2") {
            bail!("central transfer did not negotiate HTTP/2 through ALPN");
        }
        let (sender, connection) =
            hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(tls_stream))
                .await
                .context("central transfer HTTP/2 handshake")?;
        tokio::spawn(async move {
            if let Err(error) = connection.await {
                eprintln!("central transfer HTTP/2 connection closed: {error}");
            }
        });

        Ok(PersistentHttp2Client {
            authority,
            sender: Arc::new(Mutex::new(sender)),
        })
    }
}

#[derive(Clone)]
pub struct PersistentHttp2Client {
    authority: String,
    sender: Arc<Mutex<SendRequest<Full<Bytes>>>>,
}

impl std::fmt::Debug for PersistentHttp2Client {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PersistentHttp2Client")
            .field("authority", &self.authority)
            .finish_non_exhaustive()
    }
}

impl PersistentHttp2Client {
    pub async fn prewarm(&self, deadline: Instant) -> Result<()> {
        let Some(reservation) = self.reserve_until(deadline).await? else {
            bail!("central transfer HTTP/2 prewarm reached its deadline");
        };
        let attempt = reservation
            .send_method(Method::GET, "/health", Bytes::new(), deadline)
            .await;
        if attempt.used_http1() {
            bail!("central transfer health response did not use HTTP/2");
        }
        if !(200..300).contains(&attempt.status) {
            bail!(
                "central transfer health returned HTTP status {}",
                attempt.status
            );
        }
        Ok(())
    }
}

pub struct PersistentReservation {
    authority: String,
    sender: OwnedMutexGuard<SendRequest<Full<Bytes>>>,
}

impl Http2Client for PersistentHttp2Client {
    type Reservation = PersistentReservation;

    async fn reserve_until(&self, deadline: Instant) -> Result<Option<Self::Reservation>> {
        let sender = Arc::clone(&self.sender);
        let result = timeout_at(deadline.into(), async move {
            let mut guard = sender.lock_owned().await;
            guard.ready().await.context("HTTP/2 sender is not ready")?;
            Ok::<_, anyhow::Error>(guard)
        })
        .await;
        match result {
            Ok(Ok(sender)) => Ok(Some(PersistentReservation {
                authority: self.authority.clone(),
                sender,
            })),
            Ok(Err(error)) => Err(error),
            Err(_) => Ok(None),
        }
    }
}

impl Http2Reservation for PersistentReservation {
    async fn send(self, path: &str, body: Bytes, deadline: Instant) -> HttpAttempt {
        self.send_method(Method::POST, path, body, deadline).await
    }
}

impl PersistentReservation {
    async fn send_method(
        mut self,
        method: Method,
        path: &str,
        body: Bytes,
        deadline: Instant,
    ) -> HttpAttempt {
        let uri = match format!("https://{}{path}", self.authority).parse::<Uri>() {
            Ok(uri) => uri,
            Err(_) => return HttpAttempt::failed(),
        };
        let request = match Request::builder()
            .method(method)
            .uri(uri)
            .header("content-type", "application/octet-stream")
            .body(Full::new(body))
        {
            Ok(request) => request,
            Err(_) => return HttpAttempt::failed(),
        };
        let response = self.sender.send_request(request);
        drop(self.sender);
        let Ok(Ok(response)) = timeout_at(deadline.into(), response).await else {
            return HttpAttempt::failed();
        };
        let version = response.version();
        let status = response.status().as_u16();
        if !matches!(
            timeout_at(deadline.into(), response.into_body().collect()).await,
            Ok(Ok(_))
        ) {
            return HttpAttempt::failed();
        }
        if version == Version::HTTP_2 {
            HttpAttempt::http2(status)
        } else {
            HttpAttempt::http1(status)
        }
    }
}

fn install_crypto_provider() {
    static INSTALL: Once = Once::new();
    INSTALL.call_once(|| {
        let _ = rustls::crypto::ring::default_provider().install_default();
    });
}

fn read_certificates(path: &Path, description: &str) -> Result<Vec<CertificateDer<'static>>> {
    let file = File::open(path)
        .with_context(|| format!("read {description} certificate {}", path.display()))?;
    let certificates = rustls_pemfile::certs(&mut BufReader::new(file))
        .collect::<std::result::Result<Vec<_>, _>>()
        .with_context(|| format!("parse {description} certificate {}", path.display()))?;
    if certificates.is_empty() {
        bail!(
            "{description} certificate has no PEM certificates: {}",
            path.display()
        );
    }
    Ok(certificates)
}

fn read_private_key(path: &Path) -> Result<PrivateKeyDer<'static>> {
    let file = File::open(path).with_context(|| format!("read client key {}", path.display()))?;
    rustls_pemfile::private_key(&mut BufReader::new(file))
        .with_context(|| format!("parse client key {}", path.display()))?
        .ok_or_else(|| anyhow!("client key has no PEM private key: {}", path.display()))
}
