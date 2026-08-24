use super::{
    AdmittedFetchTarget, PhysicalFetchBudget, RESOURCE_PREPARATION_STAGE, ResourceBodyProblem,
    ResourceBodyVerifier, ResourceProblemCode, VerifiedResourceBody,
};
use std::collections::BTreeSet;
use std::fmt::{Debug, Formatter};
use std::io::Read;
use std::net::IpAddr;
use std::str::FromStr;
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use ureq::http::HeaderMap;
use ureq::http::header::{CONTENT_ENCODING, CONTENT_LENGTH, TRANSFER_ENCODING};
use ureq::tls::{RootCerts, TlsConfig, TlsProvider};
use ureq::unversioned::resolver::{DefaultResolver, ResolvedSocketAddrs, Resolver};
use ureq::unversioned::transport::{DefaultConnector, NextTimeout};
use ureq::{Agent, Error};

pub const FETCH_ALLOWED_IP_COUNT_MAX: usize = 16;
pub const FETCH_ATTEMPT_LIMIT: usize = 2;
pub const FETCH_BACKOFF_MILLIS: u64 = 100;
pub const FETCH_ATTEMPT_MILLIS: u64 = 5_000;
pub const RESOURCE_PHASE_MILLIS: u64 = 20_000;
pub const FETCH_STREAM_CHUNK_BYTES: usize = 1_048_576;
pub const FETCH_RESPONSE_HEADER_BYTES: usize = 65_536;
const FETCH_CHECKPOINT_MILLIS: u64 = 50;
const TRANSPORT_IDENTITY: &str = "ureq/3.4.0+rustls-webpki";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FetchEgressPolicyError;

#[derive(Clone, Eq, PartialEq)]
pub struct FetchEgressPolicy {
    allowed_ips: Arc<[IpAddr]>,
}

impl Debug for FetchEgressPolicy {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("FetchEgressPolicy")
            .field("allowed_ip_count", &self.allowed_ips.len())
            .finish_non_exhaustive()
    }
}

impl FetchEgressPolicy {
    pub fn new<I, S>(values: I) -> Result<Self, FetchEgressPolicyError>
    where
        I: IntoIterator<Item = S>,
        S: AsRef<str>,
    {
        let mut allowed_ips = Vec::new();
        let mut unique = BTreeSet::new();
        for value in values {
            let raw = value.as_ref();
            if raw.contains('%') {
                return Err(FetchEgressPolicyError);
            }
            let parsed = IpAddr::from_str(raw).map_err(|_| FetchEgressPolicyError)?;
            if parsed.to_string() != raw || !unique.insert(parsed) {
                return Err(FetchEgressPolicyError);
            }
            allowed_ips.push(parsed);
            if allowed_ips.len() > FETCH_ALLOWED_IP_COUNT_MAX {
                return Err(FetchEgressPolicyError);
            }
        }
        if allowed_ips.is_empty() {
            return Err(FetchEgressPolicyError);
        }
        Ok(Self {
            allowed_ips: allowed_ips.into(),
        })
    }

    pub fn allowed_ip_count(&self) -> usize {
        self.allowed_ips.len()
    }

    fn allows(&self, address: IpAddr) -> bool {
        self.allowed_ips.contains(&address)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ResourceFetchProblemCode {
    FetchFailed,
    ResourceLeaseExpired,
    RenderDeadlineExceeded,
    ResourceBudgetExceeded,
    LengthMismatch,
    HashMismatch,
}

impl ResourceFetchProblemCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::FetchFailed => "FETCH_FAILED",
            Self::ResourceLeaseExpired => "RESOURCE_LEASE_EXPIRED",
            Self::RenderDeadlineExceeded => "RENDER_DEADLINE_EXCEEDED",
            Self::ResourceBudgetExceeded => "RESOURCE_BUDGET_EXCEEDED",
            Self::LengthMismatch => "LENGTH_MISMATCH",
            Self::HashMismatch => "HASH_MISMATCH",
        }
    }
}

#[derive(Clone, Eq, PartialEq)]
pub struct ResourceFetchProblem {
    code: ResourceFetchProblemCode,
    resource_id: Option<Box<str>>,
    limit_id: Option<&'static str>,
}

impl Debug for ResourceFetchProblem {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ResourceFetchProblem")
            .field("code", &self.code)
            .field("resource_id", &self.resource_id)
            .field("limit_id", &self.limit_id)
            .finish()
    }
}

impl ResourceFetchProblem {
    pub fn code(&self) -> ResourceFetchProblemCode {
        self.code
    }

    pub fn engine_stage(&self) -> &'static str {
        RESOURCE_PREPARATION_STAGE
    }

    pub fn resource_id(&self) -> Option<&str> {
        self.resource_id.as_deref()
    }

    pub fn limit_id(&self) -> Option<&'static str> {
        self.limit_id
    }

    pub fn fetch_failed(resource_id: &str) -> Self {
        Self::for_resource(ResourceFetchProblemCode::FetchFailed, resource_id)
    }

    pub fn resource_lease_expired(resource_id: &str) -> Self {
        Self::for_resource(ResourceFetchProblemCode::ResourceLeaseExpired, resource_id)
    }

    pub fn render_deadline_exceeded() -> Self {
        Self::deadline()
    }

    fn for_resource(code: ResourceFetchProblemCode, resource_id: &str) -> Self {
        Self {
            code,
            resource_id: Some(resource_id.into()),
            limit_id: None,
        }
    }

    fn deadline() -> Self {
        Self {
            code: ResourceFetchProblemCode::RenderDeadlineExceeded,
            resource_id: None,
            limit_id: None,
        }
    }

    fn from_body(problem: ResourceBodyProblem) -> Self {
        let code = match problem.code() {
            ResourceProblemCode::ResourceBudgetExceeded => {
                ResourceFetchProblemCode::ResourceBudgetExceeded
            }
            ResourceProblemCode::LengthMismatch => ResourceFetchProblemCode::LengthMismatch,
            ResourceProblemCode::HashMismatch => ResourceFetchProblemCode::HashMismatch,
        };
        Self {
            code,
            resource_id: Some(problem.resource_id().into()),
            limit_id: problem.limit_id(),
        }
    }
}

#[derive(Eq, PartialEq)]
pub struct FetchedResource {
    verified_body: VerifiedResourceBody,
    bytes: Box<[u8]>,
}

impl Debug for FetchedResource {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("FetchedResource")
            .field("verified_body", &self.verified_body)
            .finish_non_exhaustive()
    }
}

impl FetchedResource {
    pub fn verified_body(&self) -> &VerifiedResourceBody {
        &self.verified_body
    }

    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    pub(crate) fn into_verified_parts(self) -> (VerifiedResourceBody, Box<[u8]>) {
        (self.verified_body, self.bytes)
    }

    #[cfg(test)]
    pub(crate) fn from_verified_parts_for_test(
        verified_body: VerifiedResourceBody,
        bytes: Box<[u8]>,
    ) -> Self {
        Self {
            verified_body,
            bytes,
        }
    }
}

pub trait ResourceFetcher: Send + Sync {
    fn fetch_resources(
        &self,
        targets: &[AdmittedFetchTarget<'_>],
        deadline_epoch_millis: i64,
    ) -> Result<Vec<FetchedResource>, ResourceFetchProblem>;
}

#[derive(Clone)]
pub struct HttpsResourceFetcher {
    egress_policy: FetchEgressPolicy,
    root_certs: RootCerts,
}

impl Debug for HttpsResourceFetcher {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("HttpsResourceFetcher")
            .field("egress_policy", &self.egress_policy)
            .field("transport_identity", &TRANSPORT_IDENTITY)
            .finish_non_exhaustive()
    }
}

impl HttpsResourceFetcher {
    pub fn new(egress_policy: FetchEgressPolicy) -> Self {
        Self {
            egress_policy,
            root_certs: RootCerts::WebPki,
        }
    }

    pub fn transport_identity(&self) -> &'static str {
        TRANSPORT_IDENTITY
    }

    #[cfg(test)]
    fn with_root_certs(egress_policy: FetchEgressPolicy, root_certs: RootCerts) -> Self {
        Self {
            egress_policy,
            root_certs,
        }
    }

    fn agent(&self, timeout: Duration) -> Agent {
        let tls_config = TlsConfig::builder()
            .provider(TlsProvider::Rustls)
            .root_certs(self.root_certs.clone())
            .use_sni(true)
            .disable_verification(false)
            .build();
        let config = Agent::config_builder()
            .http_status_as_error(false)
            .https_only(true)
            .proxy(None)
            .max_redirects(0)
            .max_redirects_will_error(false)
            .user_agent("")
            .accept("")
            .accept_encoding("")
            .max_response_header_size(FETCH_RESPONSE_HEADER_BYTES)
            .max_idle_connections(0)
            .max_idle_connections_per_host(0)
            .timeout_global(Some(timeout))
            .timeout_resolve(Some(timeout))
            .timeout_connect(Some(timeout))
            .timeout_send_request(Some(timeout))
            .timeout_recv_response(Some(timeout))
            .timeout_recv_body(Some(timeout))
            .tls_config(tls_config)
            .build();
        Agent::with_parts(
            config,
            DefaultConnector::default(),
            EgressResolver::new(self.egress_policy.clone()),
        )
    }

    fn fetch_one(
        &self,
        target: &AdmittedFetchTarget<'_>,
        deadline_epoch_millis: i64,
        phase_started: Instant,
        budget: &mut PhysicalFetchBudget,
    ) -> Result<FetchedResource, ResourceFetchProblem> {
        for attempt_index in 0..FETCH_ATTEMPT_LIMIT {
            checkpoint(target, deadline_epoch_millis, phase_started)?;
            let timeout = attempt_timeout(target, deadline_epoch_millis, phase_started)?;
            match self.fetch_attempt(
                target,
                timeout,
                deadline_epoch_millis,
                phase_started,
                budget,
            ) {
                Ok(resource) => return Ok(resource),
                Err(AttemptFailure::Terminal(problem)) => return Err(problem),
                Err(AttemptFailure::Retryable) if attempt_index + 1 < FETCH_ATTEMPT_LIMIT => {
                    wait_before_retry(target, deadline_epoch_millis, phase_started)?;
                }
                Err(AttemptFailure::Retryable) => {
                    return Err(ResourceFetchProblem::for_resource(
                        ResourceFetchProblemCode::FetchFailed,
                        target.resource_id(),
                    ));
                }
            }
        }
        unreachable!("the frozen attempt limit is positive")
    }

    fn fetch_attempt(
        &self,
        target: &AdmittedFetchTarget<'_>,
        timeout: Duration,
        deadline_epoch_millis: i64,
        phase_started: Instant,
        budget: &mut PhysicalFetchBudget,
    ) -> Result<FetchedResource, AttemptFailure> {
        let response = self
            .agent(timeout)
            .get(target.fetch_url())
            .header("accept-encoding", "identity")
            .header("connection", "close")
            .call()
            .map_err(|error| classify_call_error(error, target.resource_id()))?;
        let disposition = classify_response(
            response.status().as_u16(),
            response.headers(),
            target.resource().byte_length(),
            target.resource_id(),
        );
        match disposition {
            ResponseDisposition::Body => read_verified_body(
                response.into_body().into_reader(),
                target,
                deadline_epoch_millis,
                phase_started,
                budget,
            ),
            ResponseDisposition::RetryableStatus => {
                drain_body(
                    response.into_body().into_reader(),
                    target,
                    deadline_epoch_millis,
                    phase_started,
                    budget,
                )?;
                Err(AttemptFailure::Retryable)
            }
            ResponseDisposition::Terminal(problem) => Err(AttemptFailure::Terminal(problem)),
        }
    }
}

impl ResourceFetcher for HttpsResourceFetcher {
    fn fetch_resources(
        &self,
        targets: &[AdmittedFetchTarget<'_>],
        deadline_epoch_millis: i64,
    ) -> Result<Vec<FetchedResource>, ResourceFetchProblem> {
        if targets.is_empty() {
            return Ok(Vec::new());
        }
        let phase_started = Instant::now();
        let mut budget = PhysicalFetchBudget::new();
        let mut fetched = Vec::new();
        fetched.try_reserve_exact(targets.len()).map_err(|_| {
            ResourceFetchProblem::for_resource(
                ResourceFetchProblemCode::FetchFailed,
                targets[0].resource_id(),
            )
        })?;
        for target in targets {
            fetched.push(self.fetch_one(
                target,
                deadline_epoch_millis,
                phase_started,
                &mut budget,
            )?);
        }
        Ok(fetched)
    }
}

#[derive(Debug)]
struct EgressResolver {
    policy: FetchEgressPolicy,
    default: DefaultResolver,
}

impl EgressResolver {
    fn new(policy: FetchEgressPolicy) -> Self {
        Self {
            policy,
            default: DefaultResolver::default(),
        }
    }
}

impl Resolver for EgressResolver {
    fn resolve(
        &self,
        uri: &ureq::http::Uri,
        config: &ureq::config::Config,
        timeout: NextTimeout,
    ) -> Result<ResolvedSocketAddrs, Error> {
        let resolved = self.default.resolve(uri, config, timeout)?;
        let mut admitted = self.empty();
        if let Some(address) = resolved
            .into_iter()
            .find(|address| self.policy.allows(address.ip()))
        {
            admitted.push(*address);
        }
        if admitted.is_empty() {
            Err(Error::HostNotFound)
        } else {
            Ok(admitted)
        }
    }
}

enum ResponseDisposition {
    Body,
    RetryableStatus,
    Terminal(ResourceFetchProblem),
}

fn classify_response(
    status: u16,
    headers: &HeaderMap,
    declared_length: u64,
    resource_id: &str,
) -> ResponseDisposition {
    if (500..=599).contains(&status) {
        return ResponseDisposition::RetryableStatus;
    }
    if status != 200 {
        return ResponseDisposition::Terminal(ResourceFetchProblem::for_resource(
            ResourceFetchProblemCode::FetchFailed,
            resource_id,
        ));
    }
    if headers.get_all(TRANSFER_ENCODING).iter().next().is_some() {
        return fetch_envelope_failure(resource_id);
    }
    let content_encodings: Vec<_> = headers.get_all(CONTENT_ENCODING).iter().collect();
    if content_encodings.len() > 1
        || content_encodings
            .first()
            .is_some_and(|value| value.as_bytes() != b"identity")
    {
        return fetch_envelope_failure(resource_id);
    }
    let content_lengths: Vec<_> = headers.get_all(CONTENT_LENGTH).iter().collect();
    if content_lengths.len() != 1 {
        return fetch_envelope_failure(resource_id);
    }
    let raw = content_lengths[0].as_bytes();
    if raw.is_empty() || raw.contains(&b',') || !raw.iter().all(u8::is_ascii_digit) {
        return fetch_envelope_failure(resource_id);
    }
    let Ok(actual_length) = std::str::from_utf8(raw)
        .ok()
        .and_then(|value| value.parse::<u64>().ok())
        .ok_or(())
    else {
        return fetch_envelope_failure(resource_id);
    };
    if actual_length != declared_length {
        return ResponseDisposition::Terminal(ResourceFetchProblem::for_resource(
            ResourceFetchProblemCode::LengthMismatch,
            resource_id,
        ));
    }
    ResponseDisposition::Body
}

fn fetch_envelope_failure(resource_id: &str) -> ResponseDisposition {
    ResponseDisposition::Terminal(ResourceFetchProblem::for_resource(
        ResourceFetchProblemCode::FetchFailed,
        resource_id,
    ))
}

fn read_verified_body(
    mut reader: impl Read,
    target: &AdmittedFetchTarget<'_>,
    deadline_epoch_millis: i64,
    phase_started: Instant,
    budget: &mut PhysicalFetchBudget,
) -> Result<FetchedResource, AttemptFailure> {
    let capacity = usize::try_from(target.resource().byte_length()).map_err(|_| {
        AttemptFailure::Terminal(ResourceFetchProblem::for_resource(
            ResourceFetchProblemCode::FetchFailed,
            target.resource_id(),
        ))
    })?;
    let mut bytes = Vec::new();
    bytes.try_reserve_exact(capacity).map_err(|_| {
        AttemptFailure::Terminal(ResourceFetchProblem::for_resource(
            ResourceFetchProblemCode::FetchFailed,
            target.resource_id(),
        ))
    })?;
    let mut verifier = ResourceBodyVerifier::new(target.resource(), budget);
    let mut chunk = vec![0_u8; FETCH_STREAM_CHUNK_BYTES];
    loop {
        checkpoint(target, deadline_epoch_millis, phase_started)
            .map_err(AttemptFailure::Terminal)?;
        let read = match reader.read(&mut chunk) {
            Ok(read) => read,
            Err(_) => {
                return match checkpoint(target, deadline_epoch_millis, phase_started) {
                    Ok(()) => Err(AttemptFailure::Retryable),
                    Err(problem) => Err(AttemptFailure::Terminal(problem)),
                };
            }
        };
        if read == 0 {
            break;
        }
        verifier
            .accept_chunk(&chunk[..read])
            .map_err(ResourceFetchProblem::from_body)
            .map_err(AttemptFailure::Terminal)?;
        bytes.extend_from_slice(&chunk[..read]);
    }
    checkpoint(target, deadline_epoch_millis, phase_started).map_err(AttemptFailure::Terminal)?;
    let verified_body = verifier
        .finish()
        .map_err(ResourceFetchProblem::from_body)
        .map_err(AttemptFailure::Terminal)?;
    Ok(FetchedResource {
        verified_body,
        bytes: bytes.into_boxed_slice(),
    })
}

fn drain_body(
    mut reader: impl Read,
    target: &AdmittedFetchTarget<'_>,
    deadline_epoch_millis: i64,
    phase_started: Instant,
    budget: &mut PhysicalFetchBudget,
) -> Result<(), AttemptFailure> {
    let mut chunk = vec![0_u8; FETCH_STREAM_CHUNK_BYTES];
    loop {
        checkpoint(target, deadline_epoch_millis, phase_started)
            .map_err(AttemptFailure::Terminal)?;
        let read = match reader.read(&mut chunk) {
            Ok(read) => read,
            Err(_) => return Err(AttemptFailure::Retryable),
        };
        if read == 0 {
            return Ok(());
        }
        budget
            .accept_chunk_bytes(
                target.resource(),
                u64::try_from(read).expect("usize must fit in u64"),
            )
            .map_err(ResourceFetchProblem::from_body)
            .map_err(AttemptFailure::Terminal)?;
    }
}

enum AttemptFailure {
    Retryable,
    Terminal(ResourceFetchProblem),
}

fn classify_call_error(error: Error, resource_id: &str) -> AttemptFailure {
    match error {
        Error::Io(_)
        | Error::Timeout(_)
        | Error::HostNotFound
        | Error::ConnectionFailed
        | Error::Tls(_)
        | Error::Rustls(_) => AttemptFailure::Retryable,
        _ => AttemptFailure::Terminal(ResourceFetchProblem::fetch_failed(resource_id)),
    }
}

fn checkpoint(
    target: &AdmittedFetchTarget<'_>,
    deadline_epoch_millis: i64,
    phase_started: Instant,
) -> Result<(), ResourceFetchProblem> {
    let now = now_epoch_millis();
    if now >= deadline_epoch_millis
        || phase_started.elapsed() >= Duration::from_millis(RESOURCE_PHASE_MILLIS)
    {
        return Err(ResourceFetchProblem::deadline());
    }
    let lease_expiry = i128::from(target.resource().expires_at_epoch_second()) * 1_000;
    if i128::from(now) >= lease_expiry {
        return Err(ResourceFetchProblem::for_resource(
            ResourceFetchProblemCode::ResourceLeaseExpired,
            target.resource_id(),
        ));
    }
    Ok(())
}

fn attempt_timeout(
    target: &AdmittedFetchTarget<'_>,
    deadline_epoch_millis: i64,
    phase_started: Instant,
) -> Result<Duration, ResourceFetchProblem> {
    checkpoint(target, deadline_epoch_millis, phase_started)?;
    let now = now_epoch_millis();
    let deadline_remaining = u64::try_from(deadline_epoch_millis - now).unwrap_or(0);
    let lease_expiry = i128::from(target.resource().expires_at_epoch_second()) * 1_000;
    let lease_remaining = u64::try_from(lease_expiry - i128::from(now)).unwrap_or(u64::MAX);
    let phase_elapsed = u64::try_from(phase_started.elapsed().as_millis()).unwrap_or(u64::MAX);
    let phase_remaining = RESOURCE_PHASE_MILLIS.saturating_sub(phase_elapsed);
    let millis = FETCH_ATTEMPT_MILLIS
        .min(deadline_remaining)
        .min(lease_remaining)
        .min(phase_remaining);
    if millis == 0 {
        checkpoint(target, deadline_epoch_millis, phase_started)?;
        return Err(ResourceFetchProblem::deadline());
    }
    Ok(Duration::from_millis(millis))
}

fn wait_before_retry(
    target: &AdmittedFetchTarget<'_>,
    deadline_epoch_millis: i64,
    phase_started: Instant,
) -> Result<(), ResourceFetchProblem> {
    let mut remaining = FETCH_BACKOFF_MILLIS;
    while remaining > 0 {
        checkpoint(target, deadline_epoch_millis, phase_started)?;
        let slice = remaining.min(FETCH_CHECKPOINT_MILLIS);
        thread::sleep(Duration::from_millis(slice));
        remaining -= slice;
    }
    checkpoint(target, deadline_epoch_millis, phase_started)
}

fn now_epoch_millis() -> i64 {
    match SystemTime::now().duration_since(UNIX_EPOCH) {
        Ok(duration) => i64::try_from(duration.as_millis()).unwrap_or(i64::MAX),
        Err(_) => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rcgen::{CertifiedKey, generate_simple_self_signed};
    use renderweave_renderer_document::{AdmittedRenderDocument, validate_render_document};
    use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
    use rustls::{ServerConfig, ServerConnection, StreamOwned};
    use serde::Deserialize;
    use sha2::Digest as _;
    use std::io::Write as _;
    use std::net::{TcpListener, TcpStream};
    use std::sync::{Arc, Mutex};

    const VECTORS: &str = include_str!("../../../resource-fetch-transport-vectors-v1.json");
    const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Vectors {
        limits: Limits,
        egress_cases: Vec<EgressCase>,
        response_cases: Vec<ResponseCase>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Limits {
        allowed_ip_count_max: usize,
        attempts: usize,
        backoff_millis: u64,
        attempt_millis: u64,
        resource_phase_millis: u64,
        stream_chunk_bytes: usize,
        physical_fetch_bytes: u64,
        response_header_bytes: usize,
    }

    #[derive(Deserialize)]
    struct EgressCase {
        values: Vec<String>,
        accepted: bool,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ResponseCase {
        status: u16,
        content_lengths: Vec<String>,
        content_encodings: Vec<String>,
        transfer_encodings: Vec<String>,
        declared_length: u64,
        outcome: String,
        retryable: bool,
    }

    #[test]
    fn shared_egress_and_response_vectors_match_the_rust_kernel() {
        let vectors: Vectors = serde_json::from_str(VECTORS).unwrap();
        assert_eq!(
            vectors.limits.allowed_ip_count_max,
            FETCH_ALLOWED_IP_COUNT_MAX
        );
        assert_eq!(vectors.limits.attempts, FETCH_ATTEMPT_LIMIT);
        assert_eq!(vectors.limits.backoff_millis, FETCH_BACKOFF_MILLIS);
        assert_eq!(vectors.limits.attempt_millis, FETCH_ATTEMPT_MILLIS);
        assert_eq!(vectors.limits.resource_phase_millis, RESOURCE_PHASE_MILLIS);
        assert_eq!(vectors.limits.stream_chunk_bytes, FETCH_STREAM_CHUNK_BYTES);
        assert_eq!(
            vectors.limits.physical_fetch_bytes,
            super::super::MAX_PHYSICAL_FETCH_BYTES
        );
        assert_eq!(
            vectors.limits.response_header_bytes,
            FETCH_RESPONSE_HEADER_BYTES
        );
        for case in vectors.egress_cases {
            assert_eq!(FetchEgressPolicy::new(case.values).is_ok(), case.accepted);
        }
        for case in vectors.response_cases {
            let mut headers = HeaderMap::new();
            for value in case.content_lengths {
                headers.append(CONTENT_LENGTH, value.parse().unwrap());
            }
            for value in case.content_encodings {
                headers.append(CONTENT_ENCODING, value.parse().unwrap());
            }
            for value in case.transfer_encodings {
                headers.append(TRANSFER_ENCODING, value.parse().unwrap());
            }
            let disposition =
                classify_response(case.status, &headers, case.declared_length, "rwres_test");
            let (outcome, retryable) = match disposition {
                ResponseDisposition::Body => ("BODY", false),
                ResponseDisposition::RetryableStatus => ("FETCH_FAILED", true),
                ResponseDisposition::Terminal(problem) => (problem.code().as_str(), false),
            };
            assert_eq!(outcome, case.outcome);
            assert_eq!(retryable, case.retryable);
        }
    }

    #[test]
    fn only_network_and_tls_call_failures_are_retryable() {
        assert!(matches!(
            classify_call_error(Error::HostNotFound, "rwres_test"),
            AttemptFailure::Retryable
        ));
        for error in [
            Error::TooManyRedirects,
            Error::LargeResponseHeader(
                FETCH_RESPONSE_HEADER_BYTES + 1,
                FETCH_RESPONSE_HEADER_BYTES,
            ),
        ] {
            let AttemptFailure::Terminal(problem) = classify_call_error(error, "rwres_test") else {
                panic!("protocol and envelope failures must be terminal");
            };
            assert_eq!(problem.code(), ResourceFetchProblemCode::FetchFailed);
            assert_eq!(problem.resource_id(), Some("rwres_test"));
        }
    }

    #[test]
    fn real_rustls_transport_sends_only_fixed_headers_retries_503_and_verifies_bytes() {
        let body = b"hello world";
        let fixture = tls_fixture();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let server = spawn_tls_server(
            fixture.server_config,
            vec![response(503, b""), response(200, body)],
            requests.clone(),
        );
        let origin = format!("https://localhost:{}", server.port);
        let document = document_for(&origin, body);
        let policy =
            super::super::FetchTargetPolicy::new(&origin, super::super::ASSET_FETCH_PATH_PREFIX)
                .unwrap();
        let targets: Vec<_> = document
            .resources()
            .iter()
            .map(|resource| policy.admit(resource).unwrap())
            .collect();
        let fetcher = HttpsResourceFetcher::with_root_certs(
            FetchEgressPolicy::new(["127.0.0.1"]).unwrap(),
            RootCerts::new_with_certs(&[ureq::tls::Certificate::from_der(
                fixture.root_der.as_ref(),
            )
            .to_owned()]),
        );
        let fetched = fetcher
            .fetch_resources(&targets, now_epoch_millis() + 10_000)
            .unwrap();
        assert_eq!(fetched.len(), 1);
        assert_eq!(fetched[0].bytes(), body);
        assert_eq!(fetched[0].verified_body().byte_length(), 11);
        server.thread.join().unwrap();
        let requests = requests.lock().unwrap();
        assert_eq!(requests.len(), 2);
        for request in requests.iter() {
            let lowercase = request.to_ascii_lowercase();
            assert!(lowercase.starts_with("get /internal/render-assets/token http/1.1\r\n"));
            assert!(lowercase.contains("\r\naccept-encoding: identity\r\n"));
            assert!(lowercase.contains("\r\nconnection: close\r\n"));
            assert!(!lowercase.contains("\r\nrange:"));
            assert!(!lowercase.contains("\r\ncookie:"));
            assert!(!lowercase.contains("\r\nauthorization:"));
            assert!(!lowercase.contains("\r\nuser-agent:"));
            assert!(!lowercase.contains("\r\naccept:"));
        }
    }

    struct TlsFixture {
        server_config: Arc<ServerConfig>,
        root_der: CertificateDer<'static>,
    }

    fn tls_fixture() -> TlsFixture {
        let CertifiedKey { cert, signing_key } =
            generate_simple_self_signed(vec!["localhost".to_owned()]).unwrap();
        let root_der = cert.der().clone();
        let key = PrivateKeyDer::from(PrivatePkcs8KeyDer::from(signing_key.serialize_der()));
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let server_config = ServerConfig::builder_with_provider(provider)
            .with_protocol_versions(&[&rustls::version::TLS13])
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(vec![root_der.clone()], key)
            .unwrap();
        TlsFixture {
            server_config: Arc::new(server_config),
            root_der,
        }
    }

    struct TestServer {
        port: u16,
        thread: thread::JoinHandle<()>,
    }

    fn spawn_tls_server(
        config: Arc<ServerConfig>,
        responses: Vec<Vec<u8>>,
        requests: Arc<Mutex<Vec<String>>>,
    ) -> TestServer {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let thread = thread::spawn(move || {
            for response in responses {
                let (stream, _) = listener.accept().unwrap();
                serve_tls_connection(stream, config.clone(), response, &requests);
            }
        });
        TestServer { port, thread }
    }

    fn serve_tls_connection(
        stream: TcpStream,
        config: Arc<ServerConfig>,
        response: Vec<u8>,
        requests: &Arc<Mutex<Vec<String>>>,
    ) {
        let connection = ServerConnection::new(config).unwrap();
        let mut tls = StreamOwned::new(connection, stream);
        let mut request = Vec::new();
        let mut byte = [0_u8; 1];
        while !request.ends_with(b"\r\n\r\n") {
            tls.read_exact(&mut byte).unwrap();
            request.push(byte[0]);
            assert!(request.len() <= FETCH_RESPONSE_HEADER_BYTES);
        }
        requests
            .lock()
            .unwrap()
            .push(String::from_utf8(request).unwrap());
        tls.write_all(&response).unwrap();
        tls.flush().unwrap();
    }

    fn response(status: u16, body: &[u8]) -> Vec<u8> {
        let reason = if status == 200 {
            "OK"
        } else {
            "Service Unavailable"
        };
        format!(
            "HTTP/1.1 {status} {reason}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            body.len()
        )
        .into_bytes()
        .into_iter()
        .chain(body.iter().copied())
        .collect()
    }

    fn document_for(origin: &str, body: &[u8]) -> AdmittedRenderDocument {
        let sha = format!("sha256:{}", hex::encode(sha2::Sha256::digest(body)));
        let mut value: serde_json::Value = serde_json::from_str(ALL_KINDS).unwrap();
        let image = value["canvas"]["children"][5].clone();
        value["canvas"]["children"] = serde_json::json!([image]);
        value["canvas"]["children"][0]["occurrenceId"] =
            serde_json::Value::String("rwocc_0000000000000001".to_owned());
        let image_resource = value["resources"][1].clone();
        value["resources"] = serde_json::json!([image_resource]);
        value["resources"][0]["fetchUrl"] =
            serde_json::Value::String(format!("{origin}/internal/render-assets/token"));
        value["resources"][0]["expiresAt"] = serde_json::Value::from(4_090_912_502_u64);
        value["resources"][0]["sha256"] = serde_json::Value::String(sha);
        value["resources"][0]["byteLength"] =
            serde_json::Value::from(u64::try_from(body.len()).unwrap());
        let document = serde_json::to_string(&value).unwrap();
        validate_render_document(&document).unwrap()
    }
}
