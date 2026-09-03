use std::fs::File;
use std::future::Future;
use std::io::BufReader;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, Once};
use std::task::{Context as TaskContext, Poll, Waker};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, anyhow, bail};
use bytes::Bytes;
use h2::client::SendRequest;
use http::{Method, Request, Uri};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName};
use rustls::{ClientConfig, RootCertStore};
use tokio::net::TcpStream;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tokio::time::{sleep, timeout, timeout_at};
use tokio_rustls::TlsConnector;

const HTTP2_SETTINGS_TIMEOUT: Duration = Duration::from_secs(5);

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
    type Prepared: PreparedHttp2Request;

    fn prepare(self, path: &str, body: Bytes) -> Result<Self::Prepared>;

    fn start(
        self,
        path: &str,
        body: Bytes,
        deadline: Instant,
    ) -> impl Future<Output = HttpAttempt> + Send
    where
        Self: Sized,
    {
        async move {
            let Ok(prepared) = self.prepare(path, body) else {
                return HttpAttempt::failed();
            };
            let mut admit = || Ok(());
            let mut before_start = |_| {};
            match prepared.start(
                deadline,
                Duration::MAX,
                deadline,
                &mut admit,
                &mut before_start,
            ) {
                Ok(HttpStart::Started { attempt, .. }) => attempt.await,
                Ok(HttpStart::Missed) | Err(_) => HttpAttempt::failed(),
            }
        }
    }

    fn send(
        self,
        path: &str,
        body: Bytes,
        deadline: Instant,
    ) -> impl Future<Output = HttpAttempt> + Send
    where
        Self: Sized,
    {
        self.start(path, body, deadline)
    }
}

pub trait PreparedHttp2Request: Send {
    fn start(
        self,
        admission_deadline: Instant,
        request_timeout: Duration,
        hard_deadline: Instant,
        admit: &mut dyn FnMut() -> Result<()>,
        before_start: &mut dyn FnMut(Instant),
    ) -> Result<HttpStart<impl Future<Output = HttpAttempt> + Send + use<Self>>>;
}

pub enum HttpStart<F> {
    Missed,
    Started {
        request_started_at: Instant,
        attempt: F,
    },
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
        let mut builder = h2::client::Builder::new();
        builder.initial_max_send_streams(0);
        let (sender, connection) = builder
            .handshake(tls_stream)
            .await
            .context("central transfer HTTP/2 handshake")?;
        tokio::spawn(async move {
            if let Err(error) = connection.await {
                eprintln!("central transfer HTTP/2 connection closed: {error}");
            }
        });

        let max_concurrent_streams = wait_for_stream_limit(&sender).await?;

        Ok(PersistentHttp2Client {
            authority,
            sender: Arc::new(Mutex::new(sender)),
            capacity: Arc::new(Semaphore::new(max_concurrent_streams)),
            max_concurrent_streams,
        })
    }
}

#[derive(Clone)]
pub struct PersistentHttp2Client {
    authority: String,
    sender: Arc<Mutex<SendRequest<Bytes>>>,
    capacity: Arc<Semaphore>,
    max_concurrent_streams: usize,
}

impl std::fmt::Debug for PersistentHttp2Client {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PersistentHttp2Client")
            .field("authority", &self.authority)
            .field("max_concurrent_streams", &self.max_concurrent_streams)
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
    sender: Arc<Mutex<SendRequest<Bytes>>>,
    permit: OwnedSemaphorePermit,
}

pub struct PersistentPreparedRequest {
    sender: Arc<Mutex<SendRequest<Bytes>>>,
    permit: OwnedSemaphorePermit,
    request: Request<()>,
    body: Bytes,
}

impl Http2Client for PersistentHttp2Client {
    type Reservation = PersistentReservation;

    async fn reserve_until(&self, deadline: Instant) -> Result<Option<Self::Reservation>> {
        let result = timeout_at(deadline.into(), Arc::clone(&self.capacity).acquire_owned()).await;
        match result {
            Ok(Ok(permit)) => Ok(Some(PersistentReservation {
                authority: self.authority.clone(),
                sender: Arc::clone(&self.sender),
                permit,
            })),
            Ok(Err(_)) => Err(anyhow!("central transfer HTTP/2 capacity is closed")),
            Err(_) => Ok(None),
        }
    }
}

impl Http2Reservation for PersistentReservation {
    type Prepared = PersistentPreparedRequest;

    fn prepare(self, path: &str, body: Bytes) -> Result<Self::Prepared> {
        self.prepare_method(Method::POST, path, body)
    }
}

impl PersistentReservation {
    async fn send_method(
        self,
        method: Method,
        path: &str,
        body: Bytes,
        deadline: Instant,
    ) -> HttpAttempt {
        let Ok(prepared) = self.prepare_method(method, path, body) else {
            return HttpAttempt::failed();
        };
        let mut admit = || Ok(());
        let mut before_start = |_| {};
        match prepared.start(
            deadline,
            Duration::MAX,
            deadline,
            &mut admit,
            &mut before_start,
        ) {
            Ok(HttpStart::Started { attempt, .. }) => attempt.await,
            Ok(HttpStart::Missed) | Err(_) => HttpAttempt::failed(),
        }
    }

    fn prepare_method(
        self,
        method: Method,
        path: &str,
        body: Bytes,
    ) -> Result<PersistentPreparedRequest> {
        let uri = format!("https://{}{path}", self.authority)
            .parse::<Uri>()
            .context("build central transfer request URI")?;
        let request = Request::builder()
            .method(method)
            .uri(uri)
            .header("content-type", "application/octet-stream")
            .body(())
            .context("build central transfer request")?;
        Ok(PersistentPreparedRequest {
            sender: self.sender,
            permit: self.permit,
            request,
            body,
        })
    }
}

impl PreparedHttp2Request for PersistentPreparedRequest {
    fn start(
        self,
        admission_deadline: Instant,
        request_timeout: Duration,
        hard_deadline: Instant,
        admit: &mut dyn FnMut() -> Result<()>,
        before_start: &mut dyn FnMut(Instant),
    ) -> Result<HttpStart<impl Future<Output = HttpAttempt> + Send + use<>>> {
        let Self {
            sender,
            permit,
            request,
            body,
        } = self;
        let mut sender = sender
            .lock()
            .map_err(|_| anyhow!("central transfer HTTP/2 sender lock is poisoned"))?;
        if Instant::now() >= admission_deadline {
            return Ok(HttpStart::Missed);
        }

        let mut context = TaskContext::from_waker(Waker::noop());
        match sender.poll_ready(&mut context) {
            Poll::Ready(Ok(())) => {}
            Poll::Ready(Err(error)) => {
                return Err(anyhow!(error).context("central transfer HTTP/2 sender is not ready"));
            }
            Poll::Pending => {
                bail!("central transfer HTTP/2 stream capacity invariant was violated");
            }
        }
        if Instant::now() >= admission_deadline {
            return Ok(HttpStart::Missed);
        }

        admit()?;
        let end_of_stream = body.is_empty();
        let exchange =
            sender
                .send_request(request, end_of_stream)
                .ok()
                .and_then(|(response, mut stream)| {
                    if end_of_stream || stream.send_data(body, true).is_ok() {
                        Some((response, stream))
                    } else {
                        None
                    }
                });
        let request_started_at = Instant::now();
        before_start(request_started_at);
        drop(sender);

        let request_deadline = request_started_at
            .checked_add(request_timeout)
            .unwrap_or(hard_deadline)
            .min(hard_deadline);
        let attempt = async move {
            let _permit = permit;
            let Some((response, mut request_stream)) = exchange else {
                return HttpAttempt::failed();
            };
            let response = match timeout_at(request_deadline.into(), response).await {
                Ok(Ok(response)) => response,
                Ok(Err(_)) => return HttpAttempt::failed(),
                Err(_) => {
                    request_stream.send_reset(h2::Reason::CANCEL);
                    return HttpAttempt::failed();
                }
            };
            let status = response.status().as_u16();
            let mut body = response.into_body();
            loop {
                match timeout_at(request_deadline.into(), body.data()).await {
                    Ok(Some(Ok(chunk))) => {
                        if body.flow_control().release_capacity(chunk.len()).is_err() {
                            return HttpAttempt::failed();
                        }
                    }
                    Ok(Some(Err(_))) => return HttpAttempt::failed(),
                    Err(_) => {
                        request_stream.send_reset(h2::Reason::CANCEL);
                        return HttpAttempt::failed();
                    }
                    Ok(None) => break,
                }
            }
            HttpAttempt::http2(status)
        };
        Ok(HttpStart::Started {
            request_started_at,
            attempt,
        })
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

async fn wait_for_stream_limit(sender: &SendRequest<Bytes>) -> Result<usize> {
    let limit = timeout(HTTP2_SETTINGS_TIMEOUT, async {
        loop {
            let limit = sender.current_max_send_streams();
            if limit != 0 {
                return limit;
            }
            sleep(Duration::from_millis(1)).await;
        }
    })
    .await
    .context("central transfer did not advertise HTTP/2 stream capacity")?;
    if limit == usize::MAX {
        bail!("central transfer must advertise a finite HTTP/2 stream capacity");
    }
    Ok(limit)
}

#[cfg(test)]
mod tests;
