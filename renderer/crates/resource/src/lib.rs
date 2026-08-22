use renderweave_renderer_document::AdmittedRenderResource;
use sha2::{Digest, Sha256};

pub const MAX_PHYSICAL_FETCH_BYTES: u64 = 536_870_912;
pub const PHYSICAL_FETCH_BYTES_LIMIT_ID: &str = "assetsAndFetch.physicalFetchBytesIncludingRetries";
pub const RESOURCE_PREPARATION_STAGE: &str = "RESOURCE_PREPARATION";

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

pub fn verify_resource_body<I, B>(
    resource: &AdmittedRenderResource,
    budget: &mut PhysicalFetchBudget,
    chunks: I,
) -> Result<VerifiedResourceBody, ResourceBodyProblem>
where
    I: IntoIterator<Item = B>,
    B: AsRef<[u8]>,
{
    let mut actual_length = 0_u64;
    let mut hasher = Sha256::new();
    for chunk in chunks {
        let bytes = chunk.as_ref();
        let chunk_length = u64::try_from(bytes.len()).expect("usize must fit in u64");
        budget.accept_chunk_bytes(resource, chunk_length)?;
        actual_length += chunk_length;
        if actual_length > resource.byte_length() {
            return Err(length_mismatch_problem(resource.resource_id()));
        }
        hasher.update(bytes);
    }
    if actual_length != resource.byte_length() {
        return Err(length_mismatch_problem(resource.resource_id()));
    }
    let actual_sha256 = format!("sha256:{}", hex::encode(hasher.finalize()));
    if actual_sha256 != resource.sha256() {
        return Err(hash_mismatch_problem(resource.resource_id()));
    }
    Ok(VerifiedResourceBody {
        resource_id: resource.resource_id().into(),
        byte_length: actual_length,
        sha256: actual_sha256.into_boxed_str(),
    })
}

#[cfg(test)]
mod tests {
    use renderweave_renderer_document::{AdmittedRenderResource, validate_render_document};
    use serde::Deserialize;
    use serde_json::{Value, json};

    use super::{
        MAX_PHYSICAL_FETCH_BYTES, PhysicalFetchBudget, ResourceProblemCode, verify_resource_body,
    };

    const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");
    const VECTORS: &str = include_str!("../../../resource-body-vectors-v1.json");

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

    fn admitted_resource(byte_length: u64, sha256: &str) -> AdmittedRenderResource {
        let mut document: Value = serde_json::from_str(ALL_KINDS).unwrap();
        document["resources"][0]["byteLength"] = json!(byte_length);
        document["resources"][0]["sha256"] = json!(sha256);
        let canonical = serde_json::to_string(&document).unwrap();
        validate_render_document(&canonical).unwrap().resources()[0].clone()
    }
}
