use renderweave_renderer_document::AdmittedRenderResource;
use sha2::{Digest, Sha256};

mod fetch;
mod font;
mod image;
mod media;
mod pipeline;

pub use fetch::{
    FETCH_ALLOWED_IP_COUNT_MAX, FETCH_ATTEMPT_LIMIT, FETCH_ATTEMPT_MILLIS, FETCH_BACKOFF_MILLIS,
    FETCH_RESPONSE_HEADER_BYTES, FETCH_STREAM_CHUNK_BYTES, FetchEgressPolicy,
    FetchEgressPolicyError, FetchedResource, HttpsResourceFetcher, RESOURCE_PHASE_MILLIS,
    RequestResourceFetchState, ResourceFetchProblem, ResourceFetchProblemCode, ResourceFetcher,
};
pub use font::{
    FONT_TABLES_PER_CONTENT_LIMIT_ID, MAX_FONT_TABLES_PER_CONTENT, MAX_REQUEST_FONT_TABLES,
    MAX_REQUEST_UNIQUE_FONTS, PreparedFontResource, REQUEST_FONT_TABLES_LIMIT_ID,
    REQUEST_UNIQUE_FONTS_LIMIT_ID, RequestPreparedFontCache,
};
pub use image::{
    DECODER_SCRATCH_BYTES_LIMIT_ID, MAX_DECODER_SCRATCH_BYTES, MAX_REQUEST_DECODED_CACHE_BYTES,
    PreparedDecodedImage, REQUEST_DECODED_CACHE_BYTES_LIMIT_ID, RequestDecodedImageCache,
};
pub use media::{
    MAX_REQUEST_RAW_CACHE_BYTES, PreparedRawResource, REQUEST_RAW_CACHE_BYTES_LIMIT_ID,
    RequestRawResourceCache, ResourcePreparationProblem, ResourcePreparationProblemCode,
    ResourcePreparationProfile, VerifiedResourceMedia, verify_resource_media,
};
pub use pipeline::{
    ManifestResourcePreparer, PreparedRenderResource, PreparedResourceManifest,
    PreparedResourceManifestStats, ResourcePipelineProblem,
};

pub const MAX_PHYSICAL_FETCH_BYTES: u64 = 536_870_912;
pub const PHYSICAL_FETCH_BYTES_LIMIT_ID: &str = "assetsAndFetch.physicalFetchBytesIncludingRetries";
pub const RESOURCE_PREPARATION_STAGE: &str = "RESOURCE_PREPARATION";
pub const ASSET_FETCH_PATH_PREFIX: &str = "/internal/render-assets";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct FetchTargetPolicyError;

#[derive(Clone, Eq, PartialEq)]
pub struct FetchTargetPolicy {
    canonical_origin: Box<str>,
    path_prefix: Box<str>,
    target_prefix: Box<str>,
}

impl std::fmt::Debug for FetchTargetPolicy {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("FetchTargetPolicy")
            .field("path_prefix", &self.path_prefix)
            .finish_non_exhaustive()
    }
}

impl FetchTargetPolicy {
    pub fn new(canonical_origin: &str, path_prefix: &str) -> Result<Self, FetchTargetPolicyError> {
        if !is_canonical_https_origin(canonical_origin) || !is_canonical_absolute_path(path_prefix)
        {
            return Err(FetchTargetPolicyError);
        }
        let target_prefix = format!("{canonical_origin}{path_prefix}/").into_boxed_str();
        Ok(Self {
            canonical_origin: canonical_origin.into(),
            path_prefix: path_prefix.into(),
            target_prefix,
        })
    }

    pub fn canonical_origin(&self) -> &str {
        &self.canonical_origin
    }

    pub fn path_prefix(&self) -> &str {
        &self.path_prefix
    }

    pub fn admit<'resource>(
        &self,
        resource: &'resource AdmittedRenderResource,
    ) -> Result<AdmittedFetchTarget<'resource>, FetchTargetViolation> {
        let Some(suffix) = resource.fetch_url().strip_prefix(&*self.target_prefix) else {
            return Err(FetchTargetViolation::new(resource.resource_id()));
        };
        if !is_canonical_relative_path(suffix) {
            return Err(FetchTargetViolation::new(resource.resource_id()));
        }
        Ok(AdmittedFetchTarget { resource })
    }
}

#[derive(Eq, PartialEq)]
pub struct AdmittedFetchTarget<'resource> {
    resource: &'resource AdmittedRenderResource,
}

impl std::fmt::Debug for AdmittedFetchTarget<'_> {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("AdmittedFetchTarget")
            .field("resource_id", &self.resource.resource_id())
            .finish_non_exhaustive()
    }
}

impl AdmittedFetchTarget<'_> {
    pub fn resource_id(&self) -> &str {
        self.resource.resource_id()
    }

    pub fn fetch_url(&self) -> &str {
        self.resource.fetch_url()
    }

    pub fn resource(&self) -> &AdmittedRenderResource {
        self.resource
    }
}

#[derive(Clone, Eq, PartialEq)]
pub struct FetchTargetViolation {
    resource_id: Box<str>,
}

impl FetchTargetViolation {
    fn new(resource_id: &str) -> Self {
        Self {
            resource_id: resource_id.into(),
        }
    }

    pub fn resource_id(&self) -> &str {
        &self.resource_id
    }
}

impl std::fmt::Debug for FetchTargetViolation {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("FetchTargetViolation")
            .field("resource_id", &self.resource_id)
            .finish_non_exhaustive()
    }
}

fn is_canonical_https_origin(origin: &str) -> bool {
    if !origin.is_ascii() || origin.len() > 2_048 {
        return false;
    }
    let Some(authority) = origin.strip_prefix("https://") else {
        return false;
    };
    if authority.is_empty() {
        return false;
    }
    let (host, port) = match authority.rsplit_once(':') {
        Some((host, port)) => {
            if host.contains(':') || !is_canonical_port(port) {
                return false;
            }
            (host, Some(port))
        }
        None => (authority, None),
    };
    is_canonical_host(host) && port.is_none_or(|value| value != "443")
}

fn is_canonical_port(port: &str) -> bool {
    if port.is_empty()
        || !port.bytes().all(|byte| byte.is_ascii_digit())
        || (port.len() > 1 && port.starts_with('0'))
    {
        return false;
    }
    matches!(port.parse::<u16>(), Ok(1..=u16::MAX))
}

fn is_canonical_host(host: &str) -> bool {
    if host.is_empty() || host.len() > 253 || host.starts_with('.') || host.ends_with('.') {
        return false;
    }
    host.split('.').all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && label
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
            && label
                .as_bytes()
                .first()
                .is_some_and(u8::is_ascii_alphanumeric)
            && label
                .as_bytes()
                .last()
                .is_some_and(u8::is_ascii_alphanumeric)
    })
}

fn is_canonical_absolute_path(path: &str) -> bool {
    if !path.is_ascii()
        || path.len() < 2
        || path.len() > 2_048
        || !path.starts_with('/')
        || path.ends_with('/')
    {
        return false;
    }
    is_canonical_relative_path(&path[1..])
}

fn is_canonical_relative_path(path: &str) -> bool {
    !path.is_empty() && path.split('/').all(is_canonical_path_segment)
}

fn is_canonical_path_segment(segment: &str) -> bool {
    !segment.is_empty()
        && segment != "."
        && segment != ".."
        && segment
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'.' | b'_' | b'~'))
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ResourceProblemCode {
    ResourceBudgetExceeded,
    LengthMismatch,
    HashMismatch,
}

impl ResourceProblemCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::ResourceBudgetExceeded => "RESOURCE_BUDGET_EXCEEDED",
            Self::LengthMismatch => "LENGTH_MISMATCH",
            Self::HashMismatch => "HASH_MISMATCH",
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ResourceBodyProblem {
    code: ResourceProblemCode,
    resource_id: Box<str>,
    limit_id: Option<&'static str>,
}

impl ResourceBodyProblem {
    pub fn code(&self) -> ResourceProblemCode {
        self.code
    }

    pub fn engine_stage(&self) -> &'static str {
        RESOURCE_PREPARATION_STAGE
    }

    pub fn resource_id(&self) -> &str {
        &self.resource_id
    }

    pub fn limit_id(&self) -> Option<&'static str> {
        self.limit_id
    }
}

#[derive(Debug, Default)]
pub struct PhysicalFetchBudget {
    accepted_bytes: u64,
}

impl PhysicalFetchBudget {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn accepted_bytes(&self) -> u64 {
        self.accepted_bytes
    }

    pub fn accept_chunk_bytes(
        &mut self,
        resource: &AdmittedRenderResource,
        chunk_bytes: u64,
    ) -> Result<(), ResourceBodyProblem> {
        let Some(next) = self.accepted_bytes.checked_add(chunk_bytes) else {
            return Err(resource_budget_problem(resource.resource_id()));
        };
        if next > MAX_PHYSICAL_FETCH_BYTES {
            return Err(resource_budget_problem(resource.resource_id()));
        }
        self.accepted_bytes = next;
        Ok(())
    }
}

fn resource_budget_problem(resource_id: &str) -> ResourceBodyProblem {
    ResourceBodyProblem {
        code: ResourceProblemCode::ResourceBudgetExceeded,
        resource_id: resource_id.into(),
        limit_id: Some(PHYSICAL_FETCH_BYTES_LIMIT_ID),
    }
}

fn length_mismatch_problem(resource_id: &str) -> ResourceBodyProblem {
    ResourceBodyProblem {
        code: ResourceProblemCode::LengthMismatch,
        resource_id: resource_id.into(),
        limit_id: None,
    }
}

fn hash_mismatch_problem(resource_id: &str) -> ResourceBodyProblem {
    ResourceBodyProblem {
        code: ResourceProblemCode::HashMismatch,
        resource_id: resource_id.into(),
        limit_id: None,
    }
}

#[derive(Clone, Eq, PartialEq)]
pub struct VerifiedResourceBody {
    resource_id: Box<str>,
    byte_length: u64,
    sha256: Box<str>,
}

impl std::fmt::Debug for VerifiedResourceBody {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("VerifiedResourceBody")
            .field("resource_id", &self.resource_id)
            .field("byte_length", &self.byte_length)
            .finish_non_exhaustive()
    }
}

impl VerifiedResourceBody {
    pub fn resource_id(&self) -> &str {
        &self.resource_id
    }

    pub fn byte_length(&self) -> u64 {
        self.byte_length
    }

    pub fn sha256(&self) -> &str {
        &self.sha256
    }
}

pub struct ResourceBodyVerifier<'resource, 'budget> {
    resource: &'resource AdmittedRenderResource,
    budget: &'budget mut PhysicalFetchBudget,
    actual_length: u64,
    hasher: Sha256,
}

impl<'resource, 'budget> ResourceBodyVerifier<'resource, 'budget> {
    pub fn new(
        resource: &'resource AdmittedRenderResource,
        budget: &'budget mut PhysicalFetchBudget,
    ) -> Self {
        Self {
            resource,
            budget,
            actual_length: 0,
            hasher: Sha256::new(),
        }
    }

    pub fn accept_chunk(&mut self, bytes: &[u8]) -> Result<(), ResourceBodyProblem> {
        let chunk_length = u64::try_from(bytes.len()).expect("usize must fit in u64");
        self.budget
            .accept_chunk_bytes(self.resource, chunk_length)?;
        self.actual_length += chunk_length;
        if self.actual_length > self.resource.byte_length() {
            return Err(length_mismatch_problem(self.resource.resource_id()));
        }
        self.hasher.update(bytes);
        Ok(())
    }

    pub fn finish(self) -> Result<VerifiedResourceBody, ResourceBodyProblem> {
        if self.actual_length != self.resource.byte_length() {
            return Err(length_mismatch_problem(self.resource.resource_id()));
        }
        let actual_sha256 = format!("sha256:{}", hex::encode(self.hasher.finalize()));
        if actual_sha256 != self.resource.sha256() {
            return Err(hash_mismatch_problem(self.resource.resource_id()));
        }
        Ok(VerifiedResourceBody {
            resource_id: self.resource.resource_id().into(),
            byte_length: self.actual_length,
            sha256: actual_sha256.into_boxed_str(),
        })
    }
}

pub fn verify_resource_body<I, B>(
    resource: &AdmittedRenderResource,
    budget: &mut PhysicalFetchBudget,
    chunks: I,
) -> Result<VerifiedResourceBody, ResourceBodyProblem>
where
    I: IntoIterator<Item = B>,
    B: AsRef<[u8]>,
{
    let mut verifier = ResourceBodyVerifier::new(resource, budget);
    for chunk in chunks {
        verifier.accept_chunk(chunk.as_ref())?;
    }
    verifier.finish()
}

#[cfg(test)]
mod tests {
    use renderweave_renderer_document::{AdmittedRenderResource, validate_render_document};
    use serde::Deserialize;
    use serde_json::{Value, json};

    use super::{
        FetchTargetPolicy, MAX_PHYSICAL_FETCH_BYTES, PhysicalFetchBudget, ResourceProblemCode,
        verify_resource_body,
    };

    const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");
    const VECTORS: &str = include_str!("../../../resource-body-vectors-v1.json");
    const FETCH_TARGET_VECTORS: &str =
        include_str!("../../../resource-fetch-target-vectors-v1.json");

    const RESOURCE_ID: &str =
        "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    #[test]
    fn physical_fetch_budget_accepts_the_exact_inclusive_limit() {
        let resource = admitted_resource(
            1,
            "sha256:0000000000000000000000000000000000000000000000000000000000000000",
        );
        let mut budget = PhysicalFetchBudget::new();

        assert_eq!(
            budget.accept_chunk_bytes(&resource, MAX_PHYSICAL_FETCH_BYTES),
            Ok(())
        );
        assert_eq!(budget.accepted_bytes(), MAX_PHYSICAL_FETCH_BYTES);
    }

    #[test]
    fn complete_body_is_verified_independently_of_chunk_boundaries() {
        let body = b"renderweave";
        let resource = admitted_resource(
            body.len() as u64,
            "sha256:06396476e9013c186af788f8032f4135dd48068e8531db9b00c088c1616b3c98",
        );
        let mut budget = PhysicalFetchBudget::new();

        let verified =
            verify_resource_body(&resource, &mut budget, [&body[..6], &body[6..]]).unwrap();

        assert_eq!(verified.resource_id(), RESOURCE_ID);
        assert_eq!(verified.byte_length(), 11);
        assert_eq!(
            verified.sha256(),
            "sha256:06396476e9013c186af788f8032f4135dd48068e8531db9b00c088c1616b3c98"
        );
        assert_eq!(budget.accepted_bytes(), 11);
    }

    #[test]
    fn short_body_fails_length_first_and_keeps_received_bytes_charged() {
        let resource = admitted_resource(
            11,
            "sha256:06396476e9013c186af788f8032f4135dd48068e8531db9b00c088c1616b3c98",
        );
        let mut budget = PhysicalFetchBudget::new();

        let problem =
            verify_resource_body(&resource, &mut budget, [b"render".as_slice()]).unwrap_err();

        assert_eq!(problem.code(), ResourceProblemCode::LengthMismatch);
        assert_eq!(problem.code().as_str(), "LENGTH_MISMATCH");
        assert_eq!(problem.engine_stage(), "RESOURCE_PREPARATION");
        assert_eq!(problem.resource_id(), RESOURCE_ID);
        assert_eq!(problem.limit_id(), None);
        assert_eq!(budget.accepted_bytes(), 6);
    }

    #[test]
    fn overlong_body_stops_at_the_first_offending_chunk() {
        let resource = admitted_resource(
            5,
            "sha256:06396476e9013c186af788f8032f4135dd48068e8531db9b00c088c1616b3c98",
        );
        let mut budget = PhysicalFetchBudget::new();
        let chunks = std::iter::once(b"render".as_slice()).chain(std::iter::once_with(|| {
            panic!("body read after length mismatch")
        }));

        let problem = verify_resource_body(&resource, &mut budget, chunks).unwrap_err();

        assert_eq!(problem.code(), ResourceProblemCode::LengthMismatch);
        assert_eq!(budget.accepted_bytes(), 6);
    }

    #[test]
    fn equal_length_body_with_wrong_digest_fails_hash_after_length() {
        let resource = admitted_resource(
            11,
            "sha256:0000000000000000000000000000000000000000000000000000000000000000",
        );
        let mut budget = PhysicalFetchBudget::new();

        let problem =
            verify_resource_body(&resource, &mut budget, [b"renderweave".as_slice()]).unwrap_err();

        assert_eq!(problem.code(), ResourceProblemCode::HashMismatch);
        assert_eq!(problem.code().as_str(), "HASH_MISMATCH");
        assert_eq!(problem.engine_stage(), "RESOURCE_PREPARATION");
        assert_eq!(problem.resource_id(), RESOURCE_ID);
        assert_eq!(problem.limit_id(), None);
        assert_eq!(budget.accepted_bytes(), 11);
    }

    #[test]
    fn shared_resource_body_vectors_match_the_public_interface() {
        let vectors: Vectors = serde_json::from_str(VECTORS).unwrap();
        assert_eq!(
            vectors.vector_version,
            "renderweave-resource-body-vectors/1"
        );
        assert_eq!(
            vectors.authority_context.physical_fetch_bytes_limit,
            MAX_PHYSICAL_FETCH_BYTES
        );
        assert_eq!(
            vectors.authority_context.physical_fetch_bytes_limit_id,
            super::PHYSICAL_FETCH_BYTES_LIMIT_ID
        );
        assert_eq!(
            vectors.authority_context.engine_stage,
            super::RESOURCE_PREPARATION_STAGE
        );
        assert_eq!(
            vectors.authority_context.integrity_order,
            [
                "PHYSICAL_FETCH_BUDGET",
                "DECLARED_LENGTH",
                "LOWERCASE_SHA256"
            ]
        );
        assert_eq!(
            vectors.authority_context.resource_input,
            "CALLER_SUPPLIED_CHUNKS"
        );
        assert_eq!(vectors.authority_context.resource_bytes, "UNFETCHED");
        assert_eq!(vectors.authority_context.daemon_output_path, "UNWIRED");
        assert_eq!(
            vectors.authority_context.profile_availability,
            "NOT_REGISTERED"
        );
        assert_eq!(
            vectors.authority_context.certification_status,
            "NOT_CERTIFIED"
        );
        assert_eq!(vectors.authority_context.provider_attempts, 0);

        for case in vectors.budget_cases {
            let resource = admitted_resource(
                1,
                "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            );
            let mut budget = PhysicalFetchBudget::new();
            let mut problem = None;
            for (index, chunk_bytes) in case.chunk_byte_counts.into_iter().enumerate() {
                if let Err(actual) = budget.accept_chunk_bytes(&resource, chunk_bytes) {
                    problem = Some((index, actual));
                    break;
                }
            }
            assert_outcome(
                &case.id,
                &case.expected,
                budget.accepted_bytes(),
                problem,
                None,
            );
        }

        for case in vectors.body_cases {
            let resource = admitted_resource(case.declared_byte_length, &case.declared_sha256);
            let mut budget = PhysicalFetchBudget::new();
            for chunk_bytes in case.initial_chunk_byte_counts {
                budget.accept_chunk_bytes(&resource, chunk_bytes).unwrap();
            }
            let chunks = case
                .chunks_hex
                .iter()
                .map(|chunk| hex::decode(chunk).unwrap())
                .collect::<Vec<_>>();
            match verify_resource_body(&resource, &mut budget, chunks.iter().map(Vec::as_slice)) {
                Ok(verified) => assert_outcome(
                    &case.id,
                    &case.expected,
                    budget.accepted_bytes(),
                    None,
                    Some(verified),
                ),
                Err(problem) => assert_outcome(
                    &case.id,
                    &case.expected,
                    budget.accepted_bytes(),
                    Some((0, problem)),
                    None,
                ),
            }
        }
    }

    #[test]
    fn shared_fetch_target_vectors_match_the_public_interface() {
        let vectors: FetchTargetVectors = serde_json::from_str(FETCH_TARGET_VECTORS).unwrap();
        assert_eq!(
            vectors.vector_version,
            "renderweave-resource-fetch-target-vectors/1"
        );
        assert_eq!(
            vectors.authority_context.asset_fetch_path_prefix,
            super::ASSET_FETCH_PATH_PREFIX
        );
        assert_eq!(
            vectors.authority_context.engine_stage,
            super::RESOURCE_PREPARATION_STAGE
        );
        assert_eq!(
            vectors.authority_context.target_input,
            "TYPED_RENDER_RESOURCE"
        );
        assert_eq!(
            vectors.authority_context.transport_implementation,
            "UNWIRED"
        );
        assert_eq!(vectors.authority_context.resource_bytes, "UNFETCHED");
        assert_eq!(vectors.authority_context.daemon_output_path, "UNWIRED");
        assert_eq!(
            vectors.authority_context.profile_availability,
            "NOT_REGISTERED"
        );
        assert_eq!(
            vectors.authority_context.certification_status,
            "NOT_CERTIFIED"
        );
        assert_eq!(
            vectors.authority_context.process_raster_implementation,
            "ABSENT"
        );
        assert_eq!(vectors.authority_context.product_route, "CLOSED");
        assert_eq!(vectors.authority_context.provider_attempts, 0);

        for case in vectors.policy_cases {
            let actual = FetchTargetPolicy::new(
                &case.origin,
                &vectors.authority_context.asset_fetch_path_prefix,
            );
            if case.expected.outcome == "ADMITTED" {
                let policy = actual.unwrap_or_else(|_| panic!("{}: expected admitted", case.id));
                assert_eq!(policy.canonical_origin(), case.origin, "{}", case.id);
                assert_eq!(
                    policy.path_prefix(),
                    vectors.authority_context.asset_fetch_path_prefix,
                    "{}",
                    case.id
                );
            } else {
                assert!(actual.is_err(), "{}: expected rejected", case.id);
            }
        }

        for case in vectors.target_cases {
            let policy = FetchTargetPolicy::new(
                &case.origin,
                &vectors.authority_context.asset_fetch_path_prefix,
            )
            .unwrap();
            let resource = admitted_resource_with_fetch_url(&case.fetch_url);
            let actual = policy.admit(&resource);
            if case.expected.outcome == "ADMITTED" {
                let target = actual.unwrap_or_else(|_| panic!("{}: expected admitted", case.id));
                assert_eq!(target.resource_id(), RESOURCE_ID, "{}", case.id);
                assert_eq!(target.fetch_url(), case.fetch_url, "{}", case.id);
            } else {
                let violation = actual
                    .err()
                    .unwrap_or_else(|| panic!("{}: expected rejected", case.id));
                assert_eq!(violation.resource_id(), RESOURCE_ID, "{}", case.id);
            }
        }
    }

    fn assert_outcome(
        case_id: &str,
        expected: &Expected,
        accepted_bytes: u64,
        problem: Option<(usize, super::ResourceBodyProblem)>,
        verified: Option<super::VerifiedResourceBody>,
    ) {
        assert_eq!(accepted_bytes, expected.accepted_bytes, "{case_id}");
        if expected.outcome == "VERIFIED" {
            let verified = verified.unwrap_or_else(|| panic!("{case_id}: expected verified"));
            assert!(problem.is_none(), "{case_id}");
            assert_eq!(
                verified.resource_id(),
                expected.resource_id.as_deref().unwrap(),
                "{case_id}"
            );
            assert_eq!(
                verified.byte_length(),
                expected.byte_length.unwrap(),
                "{case_id}"
            );
            assert_eq!(
                verified.sha256(),
                expected.sha256.as_deref().unwrap(),
                "{case_id}"
            );
            return;
        }
        if expected.outcome == "ACCEPTED" {
            assert!(problem.is_none(), "{case_id}");
            assert!(verified.is_none(), "{case_id}");
            return;
        }
        let (failed_index, problem) =
            problem.unwrap_or_else(|| panic!("{case_id}: expected problem"));
        if let Some(expected_index) = expected.failed_chunk_index {
            assert_eq!(failed_index, expected_index, "{case_id}");
        }
        assert!(verified.is_none(), "{case_id}");
        assert_eq!(problem.code().as_str(), expected.outcome, "{case_id}");
        assert_eq!(
            problem.engine_stage(),
            expected.engine_stage.as_deref().unwrap(),
            "{case_id}"
        );
        assert_eq!(
            problem.resource_id(),
            expected.resource_id.as_deref().unwrap(),
            "{case_id}"
        );
        assert_eq!(
            problem.limit_id(),
            expected.limit_id.as_deref(),
            "{case_id}"
        );
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct Vectors {
        vector_version: String,
        authority_context: AuthorityContext,
        budget_cases: Vec<BudgetCase>,
        body_cases: Vec<BodyCase>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct AuthorityContext {
        physical_fetch_bytes_limit: u64,
        physical_fetch_bytes_limit_id: String,
        engine_stage: String,
        integrity_order: Vec<String>,
        resource_input: String,
        resource_bytes: String,
        daemon_output_path: String,
        profile_availability: String,
        certification_status: String,
        provider_attempts: u64,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct BudgetCase {
        id: String,
        chunk_byte_counts: Vec<u64>,
        expected: Expected,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct BodyCase {
        id: String,
        initial_chunk_byte_counts: Vec<u64>,
        chunks_hex: Vec<String>,
        declared_byte_length: u64,
        declared_sha256: String,
        expected: Expected,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct Expected {
        outcome: String,
        accepted_bytes: u64,
        failed_chunk_index: Option<usize>,
        engine_stage: Option<String>,
        resource_id: Option<String>,
        limit_id: Option<String>,
        byte_length: Option<u64>,
        sha256: Option<String>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct FetchTargetVectors {
        vector_version: String,
        authority_context: FetchTargetAuthorityContext,
        policy_cases: Vec<FetchTargetPolicyCase>,
        target_cases: Vec<FetchTargetCase>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct FetchTargetAuthorityContext {
        engine_stage: String,
        asset_fetch_path_prefix: String,
        target_input: String,
        transport_implementation: String,
        resource_bytes: String,
        daemon_output_path: String,
        profile_availability: String,
        certification_status: String,
        process_raster_implementation: String,
        product_route: String,
        provider_attempts: u64,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct FetchTargetPolicyCase {
        id: String,
        origin: String,
        expected: FetchTargetExpected,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct FetchTargetCase {
        id: String,
        origin: String,
        fetch_url: String,
        expected: FetchTargetExpected,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct FetchTargetExpected {
        outcome: String,
    }

    fn admitted_resource(byte_length: u64, sha256: &str) -> AdmittedRenderResource {
        let mut document: Value = serde_json::from_str(ALL_KINDS).unwrap();
        document["resources"][0]["byteLength"] = json!(byte_length);
        document["resources"][0]["sha256"] = json!(sha256);
        let canonical = serde_json::to_string(&document).unwrap();
        validate_render_document(&canonical).unwrap().resources()[0].clone()
    }

    fn admitted_resource_with_fetch_url(fetch_url: &str) -> AdmittedRenderResource {
        let mut document: Value = serde_json::from_str(ALL_KINDS).unwrap();
        document["resources"][0]["fetchUrl"] = json!(fetch_url);
        let canonical = serde_json::to_string(&document).unwrap();
        validate_render_document(&canonical).unwrap().resources()[0].clone()
    }
}
