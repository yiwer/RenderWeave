use crate::{
    FetchTargetPolicy, PreparedDecodedImage, PreparedFontResource, RESOURCE_PREPARATION_STAGE,
    RequestDecodedImageCache, RequestPreparedFontCache, RequestRawResourceCache,
    RequestResourceFetchState, ResourceFetchProblem, ResourceFetcher, ResourcePreparationControl,
    ResourcePreparationInterruption, ResourcePreparationProblem, ResourcePreparationProblemCode,
    ResourcePreparationProfile,
};
use renderweave_renderer_document::{AdmittedRenderResource, RenderResourceKind};
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::{Debug, Formatter};
use std::time::Instant;

#[derive(Clone)]
pub enum PreparedRenderResource {
    Image {
        raw_cache_hit: bool,
        image: PreparedDecodedImage,
    },
    Font {
        raw_cache_hit: bool,
        font: PreparedFontResource,
    },
}

impl PreparedRenderResource {
    pub fn resource_id(&self) -> &str {
        match self {
            Self::Image { image, .. } => image.resource_id(),
            Self::Font { font, .. } => font.resource_id(),
        }
    }

    pub fn kind(&self) -> RenderResourceKind {
        match self {
            Self::Image { .. } => RenderResourceKind::Image,
            Self::Font { .. } => RenderResourceKind::Font,
        }
    }

    pub fn raw_cache_hit(&self) -> bool {
        match self {
            Self::Image { raw_cache_hit, .. } | Self::Font { raw_cache_hit, .. } => *raw_cache_hit,
        }
    }

    pub fn semantic_cache_hit(&self) -> bool {
        match self {
            Self::Image { image, .. } => image.cache_hit(),
            Self::Font { font, .. } => font.cache_hit(),
        }
    }

    pub fn as_image(&self) -> Option<&PreparedDecodedImage> {
        match self {
            Self::Image { image, .. } => Some(image),
            Self::Font { .. } => None,
        }
    }

    pub fn as_font(&self) -> Option<&PreparedFontResource> {
        match self {
            Self::Font { font, .. } => Some(font),
            Self::Image { .. } => None,
        }
    }
}

impl Debug for PreparedRenderResource {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PreparedRenderResource")
            .field("resource_id", &self.resource_id())
            .field("kind", &self.kind())
            .field("raw_cache_hit", &self.raw_cache_hit())
            .field("semantic_cache_hit", &self.semantic_cache_hit())
            .finish_non_exhaustive()
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct PreparedResourceManifestStats {
    physical_fetch_bytes: u64,
    raw_unique_content: usize,
    raw_retained_bytes: u64,
    decoded_image_unique_content: usize,
    decoded_image_retained_bytes: u64,
    prepared_font_unique_content: usize,
    prepared_font_retained_tables: usize,
}

impl PreparedResourceManifestStats {
    pub fn physical_fetch_bytes(self) -> u64 {
        self.physical_fetch_bytes
    }

    pub fn raw_unique_content(self) -> usize {
        self.raw_unique_content
    }

    pub fn raw_retained_bytes(self) -> u64 {
        self.raw_retained_bytes
    }

    pub fn decoded_image_unique_content(self) -> usize {
        self.decoded_image_unique_content
    }

    pub fn decoded_image_retained_bytes(self) -> u64 {
        self.decoded_image_retained_bytes
    }

    pub fn prepared_font_unique_content(self) -> usize {
        self.prepared_font_unique_content
    }

    pub fn prepared_font_retained_tables(self) -> usize {
        self.prepared_font_retained_tables
    }
}

#[derive(Clone)]
pub struct PreparedResourceManifest {
    profile: ResourcePreparationProfile,
    resources: Vec<PreparedRenderResource>,
    index_by_resource_id: BTreeMap<Box<str>, usize>,
    stats: PreparedResourceManifestStats,
}

impl PreparedResourceManifest {
    pub fn profile(&self) -> ResourcePreparationProfile {
        self.profile
    }

    pub fn resources(&self) -> &[PreparedRenderResource] {
        &self.resources
    }

    pub fn get(&self, resource_id: &str) -> Option<&PreparedRenderResource> {
        self.index_by_resource_id
            .get(resource_id)
            .and_then(|index| self.resources.get(*index))
    }

    pub fn stats(&self) -> PreparedResourceManifestStats {
        self.stats
    }
}

impl Debug for PreparedResourceManifest {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PreparedResourceManifest")
            .field("profile", &self.profile)
            .field("resource_count", &self.resources.len())
            .field("stats", &self.stats)
            .finish()
    }
}

#[derive(Clone, Eq, PartialEq)]
enum PipelineFailure {
    Fetch(ResourceFetchProblem),
    Preparation(ResourcePreparationProblem),
    Deadline,
    Cancelled,
    Internal,
}

struct UnrestrictedResourcePreparation;

impl ResourcePreparationControl for UnrestrictedResourcePreparation {
    fn checkpoint(&self) -> Result<(), ResourcePreparationInterruption> {
        Ok(())
    }
}

static UNRESTRICTED_RESOURCE_PREPARATION: UnrestrictedResourcePreparation =
    UnrestrictedResourcePreparation;

#[derive(Clone, Eq, PartialEq)]
pub struct ResourcePipelineProblem {
    failure: PipelineFailure,
}

impl ResourcePipelineProblem {
    pub fn code(&self) -> &'static str {
        match &self.failure {
            PipelineFailure::Fetch(problem) => problem.code().as_str(),
            PipelineFailure::Preparation(problem) => problem.code().as_str(),
            PipelineFailure::Deadline => "RENDER_DEADLINE_EXCEEDED",
            PipelineFailure::Cancelled => "RENDER_CANCELLED",
            PipelineFailure::Internal => "RENDER_INTERNAL_ERROR",
        }
    }

    pub fn engine_stage(&self) -> &'static str {
        RESOURCE_PREPARATION_STAGE
    }

    pub fn resource_id(&self) -> Option<&str> {
        match &self.failure {
            PipelineFailure::Fetch(problem) => problem.resource_id(),
            PipelineFailure::Preparation(problem)
                if problem.code() != ResourcePreparationProblemCode::RenderInternalError =>
            {
                Some(problem.resource_id())
            }
            PipelineFailure::Preparation(_)
            | PipelineFailure::Deadline
            | PipelineFailure::Cancelled
            | PipelineFailure::Internal => None,
        }
    }

    pub fn limit_id(&self) -> Option<&'static str> {
        match &self.failure {
            PipelineFailure::Fetch(problem) => problem.limit_id(),
            PipelineFailure::Preparation(problem) => problem.limit_id(),
            PipelineFailure::Deadline | PipelineFailure::Cancelled | PipelineFailure::Internal => {
                None
            }
        }
    }

    fn deadline() -> Self {
        Self {
            failure: PipelineFailure::Deadline,
        }
    }

    fn internal() -> Self {
        Self {
            failure: PipelineFailure::Internal,
        }
    }

    fn interrupted(interruption: ResourcePreparationInterruption) -> Self {
        Self {
            failure: match interruption {
                ResourcePreparationInterruption::Cancelled => PipelineFailure::Cancelled,
                ResourcePreparationInterruption::DeadlineExceeded => PipelineFailure::Deadline,
            },
        }
    }
}

impl From<ResourceFetchProblem> for ResourcePipelineProblem {
    fn from(problem: ResourceFetchProblem) -> Self {
        Self {
            failure: PipelineFailure::Fetch(problem),
        }
    }
}

impl From<ResourcePreparationProblem> for ResourcePipelineProblem {
    fn from(problem: ResourcePreparationProblem) -> Self {
        Self {
            failure: PipelineFailure::Preparation(problem),
        }
    }
}

impl Debug for ResourcePipelineProblem {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ResourcePipelineProblem")
            .field("code", &self.code())
            .field("resource_id", &self.resource_id())
            .field("limit_id", &self.limit_id())
            .finish()
    }
}

pub struct ManifestResourcePreparer<'dependency> {
    target_policy: &'dependency FetchTargetPolicy,
    fetcher: &'dependency dyn ResourceFetcher,
    profile: ResourcePreparationProfile,
}

impl<'dependency> ManifestResourcePreparer<'dependency> {
    pub fn new(
        target_policy: &'dependency FetchTargetPolicy,
        fetcher: &'dependency dyn ResourceFetcher,
        profile: ResourcePreparationProfile,
    ) -> Self {
        Self {
            target_policy,
            fetcher,
            profile,
        }
    }

    pub fn prepare(
        &self,
        resources: &[AdmittedRenderResource],
        deadline_epoch_millis: i64,
        request_started_epoch_millis: i64,
    ) -> Result<PreparedResourceManifest, ResourcePipelineProblem> {
        self.prepare_controlled(
            resources,
            deadline_epoch_millis,
            request_started_epoch_millis,
            &UNRESTRICTED_RESOURCE_PREPARATION,
        )
    }

    pub fn prepare_controlled(
        &self,
        resources: &[AdmittedRenderResource],
        deadline_epoch_millis: i64,
        request_started_epoch_millis: i64,
        control: &dyn ResourcePreparationControl,
    ) -> Result<PreparedResourceManifest, ResourcePipelineProblem> {
        resource_checkpoint(control)?;
        require_unique_resource_ids(resources)?;
        let phase_started = Instant::now();
        ensure_deadline(
            deadline_epoch_millis,
            wall_now(request_started_epoch_millis, phase_started),
        )?;

        let mut fetch_state = RequestResourceFetchState::new();
        let mut raw_cache = RequestRawResourceCache::new();
        let mut image_cache = RequestDecodedImageCache::new();
        let mut font_cache = RequestPreparedFontCache::new();
        let mut prepared = Vec::new();
        prepared
            .try_reserve_exact(resources.len())
            .map_err(|_| ResourcePipelineProblem::internal())?;

        for resource in resources {
            resource_checkpoint(control)?;
            ensure_deadline(
                deadline_epoch_millis,
                wall_now(request_started_epoch_millis, phase_started),
            )?;
            let target = self
                .target_policy
                .admit(resource)
                .map_err(|_| ResourcePipelineProblem::internal())?;
            let fetched = self.fetcher.fetch_resource(
                &target,
                deadline_epoch_millis,
                &mut fetch_state,
                control,
            )?;
            resource_checkpoint(control)?;
            let after_fetch = wall_now(request_started_epoch_millis, phase_started);
            ensure_deadline(deadline_epoch_millis, after_fetch)?;
            let raw = raw_cache.insert_fetched(resource, self.profile, fetched, after_fetch)?;
            resource_checkpoint(control)?;
            let raw_cache_hit = raw.cache_hit();
            let prepared_resource = match resource.kind() {
                RenderResourceKind::Image => PreparedRenderResource::Image {
                    raw_cache_hit,
                    image: image_cache.decode_or_lookup(
                        resource,
                        self.profile,
                        &raw,
                        after_fetch,
                    )?,
                },
                RenderResourceKind::Font => PreparedRenderResource::Font {
                    raw_cache_hit,
                    font: font_cache.prepare_or_lookup(
                        resource,
                        self.profile,
                        &raw,
                        after_fetch,
                    )?,
                },
            };
            resource_checkpoint(control)?;
            ensure_deadline(
                deadline_epoch_millis,
                wall_now(request_started_epoch_millis, phase_started),
            )?;
            prepared.push(prepared_resource);
        }

        resource_checkpoint(control)?;

        let mut index_by_resource_id = BTreeMap::new();
        for (index, resource) in prepared.iter().enumerate() {
            if index_by_resource_id
                .insert(resource.resource_id().into(), index)
                .is_some()
            {
                return Err(ResourcePipelineProblem::internal());
            }
        }
        let stats = PreparedResourceManifestStats {
            physical_fetch_bytes: fetch_state.physical_bytes(),
            raw_unique_content: raw_cache.unique_content_count(),
            raw_retained_bytes: raw_cache.retained_bytes(),
            decoded_image_unique_content: image_cache.unique_content_count(),
            decoded_image_retained_bytes: image_cache.retained_bytes(),
            prepared_font_unique_content: font_cache.unique_content_count(),
            prepared_font_retained_tables: font_cache.retained_table_count(),
        };
        Ok(PreparedResourceManifest {
            profile: self.profile,
            resources: prepared,
            index_by_resource_id,
            stats,
        })
    }
}

fn resource_checkpoint(
    control: &dyn ResourcePreparationControl,
) -> Result<(), ResourcePipelineProblem> {
    control
        .checkpoint()
        .map_err(ResourcePipelineProblem::interrupted)
}

impl Debug for ManifestResourcePreparer<'_> {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ManifestResourcePreparer")
            .field("profile", &self.profile)
            .finish_non_exhaustive()
    }
}

fn require_unique_resource_ids(
    resources: &[AdmittedRenderResource],
) -> Result<(), ResourcePipelineProblem> {
    let mut seen = BTreeSet::new();
    for resource in resources {
        if !seen.insert(resource.resource_id()) {
            return Err(ResourcePipelineProblem::internal());
        }
    }
    Ok(())
}

fn ensure_deadline(
    deadline_epoch_millis: i64,
    now_epoch_millis: i64,
) -> Result<(), ResourcePipelineProblem> {
    if deadline_epoch_millis <= now_epoch_millis {
        return Err(ResourcePipelineProblem::deadline());
    }
    Ok(())
}

fn wall_now(request_started_epoch_millis: i64, phase_started: Instant) -> i64 {
    let elapsed_millis = i64::try_from(phase_started.elapsed().as_millis()).unwrap_or(i64::MAX);
    request_started_epoch_millis.saturating_add(elapsed_millis)
}

#[cfg(test)]
mod tests {
    use super::*;
    use renderweave_renderer_document::{AdmittedRenderDocument, validate_render_document};
    use serde_json::{Value, json};
    use sha2::{Digest, Sha256};
    use std::sync::Mutex;

    const PIPELINE_VECTORS: &str =
        include_str!("../../../resource-preparation-pipeline-vectors-v1.json");
    const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");
    const ASSET_VECTORS: &str = include_str!(
        "../../../../renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/acceptance-kernel-v1/vectors.json"
    );
    const ASSET_FETCH_ORIGIN: &str = "https://render.internal.example";
    const DEADLINE: i64 = 2_000_000_000_000;
    const STARTED: i64 = 1_900_000_000_000;

    #[test]
    fn prepares_complete_manifest_in_order_with_request_local_semantic_reuse() {
        let vectors: Value = serde_json::from_str(PIPELINE_VECTORS).unwrap();
        for case in vectors["successCases"].as_array().unwrap() {
            let fixtures = fixtures(case, None);
            let (document, bodies) = admitted_document(&fixtures, None);
            let fetcher = FixtureFetcher::new(bodies, None);
            let policy = target_policy();
            let manifest = ManifestResourcePreparer::new(
                &policy,
                &fetcher,
                ResourcePreparationProfile::RendererV1,
            )
            .prepare(document.resources(), DEADLINE, STARTED)
            .unwrap();

            let expected_resources = case["resources"].as_array().unwrap();
            assert_eq!(manifest.resources().len(), expected_resources.len());
            for (actual, expected) in manifest.resources().iter().zip(expected_resources) {
                assert_eq!(actual.resource_id(), expected["resourceId"]);
                assert_eq!(
                    actual.kind().as_str().to_ascii_uppercase(),
                    expected["expectedKind"].as_str().unwrap_or("")
                );
                assert_eq!(
                    actual.raw_cache_hit(),
                    expected["expectedRawCacheHit"].as_bool().unwrap_or(false)
                );
                assert_eq!(
                    actual.semantic_cache_hit(),
                    expected["expectedSemanticCacheHit"]
                        .as_bool()
                        .unwrap_or(false)
                );
                assert_eq!(
                    manifest.get(actual.resource_id()).unwrap().kind(),
                    actual.kind()
                );
            }
            assert_eq!(
                fetcher.observations(),
                expected_resources
                    .iter()
                    .map(|resource| resource["resourceId"].as_str().unwrap().to_owned())
                    .collect::<Vec<_>>()
            );
            assert_stats(manifest.stats(), &case["expectedStats"]);
        }
    }

    #[test]
    fn preparation_failure_is_first_and_stops_before_the_next_fetch() {
        let vectors: Value = serde_json::from_str(PIPELINE_VECTORS).unwrap();
        for case in vectors["preparationFailureCases"].as_array().unwrap() {
            let mutation = case["mutation"].as_str();
            let fixtures = fixtures(case, mutation);
            let (document, bodies) = admitted_document(&fixtures, None);
            let fetcher = FixtureFetcher::new(bodies, None);
            let policy = target_policy();
            let problem = ManifestResourcePreparer::new(
                &policy,
                &fetcher,
                ResourcePreparationProfile::RendererV1,
            )
            .prepare(document.resources(), DEADLINE, STARTED)
            .unwrap_err();
            assert_eq!(problem.code(), case["expectedCode"]);
            assert_eq!(problem.resource_id(), case["expectedResourceId"].as_str());
            assert_eq!(
                fetcher.observations(),
                string_array(&case["expectedFetchedResourceIds"])
            );
        }
    }

    #[test]
    fn fetch_failure_is_first_and_stops_before_the_next_manifest_entry() {
        let vectors: Value = serde_json::from_str(PIPELINE_VECTORS).unwrap();
        for case in vectors["fetchFailureCases"].as_array().unwrap() {
            let fixtures = fixtures(case, None);
            let (document, bodies) = admitted_document(&fixtures, None);
            let failure_index = case["failureIndex"].as_u64().unwrap() as usize;
            let fetcher = FixtureFetcher::new(bodies, Some(failure_index));
            let policy = target_policy();
            let problem = ManifestResourcePreparer::new(
                &policy,
                &fetcher,
                ResourcePreparationProfile::RendererV1,
            )
            .prepare(document.resources(), DEADLINE, STARTED)
            .unwrap_err();
            assert_eq!(problem.code(), case["expectedCode"]);
            assert_eq!(problem.resource_id(), case["expectedResourceId"].as_str());
            assert_eq!(
                fetcher.observations(),
                string_array(&case["expectedFetchedResourceIds"])
            );
        }
    }

    #[test]
    fn deadline_and_target_invariants_fail_before_transport() {
        let vectors: Value = serde_json::from_str(PIPELINE_VECTORS).unwrap();
        let document_case = &vectors["controlCases"][0];
        let fixture = ResourceFixture {
            resource_id: "rwres_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                .to_owned(),
            document_asset_case_id: "font-ttf-admitted".to_owned(),
            body_asset_case_id: "font-ttf-admitted".to_owned(),
            flip_last_byte: false,
        };
        let (document, bodies) = admitted_document(std::slice::from_ref(&fixture), None);
        let fetcher = FixtureFetcher::new(bodies, None);
        let policy = target_policy();
        let problem = ManifestResourcePreparer::new(
            &policy,
            &fetcher,
            ResourcePreparationProfile::RendererV1,
        )
        .prepare(document.resources(), STARTED, STARTED)
        .unwrap_err();
        assert_eq!(problem.code(), document_case["expectedCode"]);
        assert_eq!(problem.resource_id(), None);
        assert!(fetcher.observations().is_empty());

        let target_case = &vectors["controlCases"][1];
        let (document, bodies) =
            admitted_document(&[fixture], Some(target_case["fetchUrl"].as_str().unwrap()));
        let fetcher = FixtureFetcher::new(bodies, None);
        let problem = ManifestResourcePreparer::new(
            &policy,
            &fetcher,
            ResourcePreparationProfile::RendererV1,
        )
        .prepare(document.resources(), DEADLINE, STARTED)
        .unwrap_err();
        assert_eq!(problem.code(), target_case["expectedCode"]);
        assert_eq!(problem.resource_id(), None);
        assert!(fetcher.observations().is_empty());
    }

    #[test]
    fn vector_problem_projection_and_honest_boundary_are_closed() {
        let vectors: Value = serde_json::from_str(PIPELINE_VECTORS).unwrap();
        assert_eq!(
            vectors["problemProjection"]["engineStage"],
            RESOURCE_PREPARATION_STAGE
        );
        assert_eq!(vectors["boundary"]["fontShaping"], "UNWIRED");
        assert_eq!(vectors["boundary"]["sceneConsumer"], "UNWIRED");
        assert_eq!(vectors["boundary"]["daemonOutputPath"], "UNWIRED");
        assert_eq!(vectors["boundary"]["profileAvailability"], "NOT_REGISTERED");
        assert_eq!(vectors["boundary"]["processRasterImplementation"], "ABSENT");
        assert_eq!(vectors["boundary"]["productRoute"], "CLOSED");
        assert_eq!(vectors["boundary"]["providerAttempts"], 0);
    }

    #[derive(Clone)]
    struct ResourceFixture {
        resource_id: String,
        document_asset_case_id: String,
        body_asset_case_id: String,
        flip_last_byte: bool,
    }

    fn fixtures(case: &Value, mutation: Option<&str>) -> Vec<ResourceFixture> {
        case["resources"]
            .as_array()
            .unwrap()
            .iter()
            .enumerate()
            .map(|(index, resource)| ResourceFixture {
                resource_id: resource["resourceId"].as_str().unwrap().to_owned(),
                document_asset_case_id: resource["documentAssetCaseId"]
                    .as_str()
                    .unwrap()
                    .to_owned(),
                body_asset_case_id: resource["bodyAssetCaseId"].as_str().unwrap().to_owned(),
                flip_last_byte: index == 0 && mutation == Some("FLIP_LAST_BYTE"),
            })
            .collect()
    }

    fn admitted_document(
        fixtures: &[ResourceFixture],
        first_fetch_url_override: Option<&str>,
    ) -> (AdmittedRenderDocument, BTreeMap<String, Vec<u8>>) {
        let asset_vectors: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let mut document: Value = serde_json::from_str(ALL_KINDS).unwrap();
        let text_template = document["canvas"]["children"][4].clone();
        let image_template = document["canvas"]["children"][5].clone();
        let mut children = Vec::new();
        let mut resources = Vec::new();
        let mut bodies = BTreeMap::new();

        for (index, fixture) in fixtures.iter().enumerate() {
            let document_case = asset_case(&asset_vectors, &fixture.document_asset_case_id);
            let body_case = asset_case(&asset_vectors, &fixture.body_asset_case_id);
            let mut bytes = decode_base64(body_case["input"]["data"].as_str().unwrap());
            if fixture.flip_last_byte {
                let last = bytes.last_mut().unwrap();
                *last ^= 1;
            }
            let kind = document_case["expected"]["kind"].as_str().unwrap();
            let mut node = match kind {
                "FONT" => text_template.clone(),
                "IMAGE" => image_template.clone(),
                _ => panic!("unexpected fixture kind"),
            };
            node["occurrenceId"] = Value::String(format!("rwocc_{:016x}", index + 1));
            if kind == "FONT" {
                node["runs"][0]["fontResourceId"] = Value::String(fixture.resource_id.clone());
            } else {
                node["imageResourceId"] = Value::String(fixture.resource_id.clone());
            }
            children.push(node);
            let fetch_url = if index == 0 {
                first_fetch_url_override
                    .map(str::to_owned)
                    .unwrap_or_else(|| {
                        format!("{ASSET_FETCH_ORIGIN}/internal/render-assets/{index}")
                    })
            } else {
                format!("{ASSET_FETCH_ORIGIN}/internal/render-assets/{index}")
            };
            resources.push(resource_value(
                document_case,
                &fixture.resource_id,
                &fetch_url,
                &bytes,
            ));
            bodies.insert(fixture.resource_id.clone(), bytes);
        }
        document["canvas"]["children"] = Value::Array(children);
        document["resources"] = Value::Array(resources);
        let canonical = serde_json::to_string(&document).unwrap();
        (validate_render_document(&canonical).unwrap(), bodies)
    }

    fn resource_value(
        document_case: &Value,
        resource_id: &str,
        fetch_url: &str,
        body: &[u8],
    ) -> Value {
        let expected = &document_case["expected"];
        let kind = expected["kind"].as_str().unwrap();
        let mut descriptor = expected["descriptor"].as_object().unwrap().clone();
        let descriptor_kind = descriptor.remove("type").unwrap();
        descriptor.insert(
            "kind".to_owned(),
            Value::String(descriptor_kind.as_str().unwrap().to_ascii_lowercase()),
        );
        json!({
            "acceptanceProfileId": "renderweave-asset-acceptance/1.0",
            "byteLength": body.len(),
            "expiresAt": 4_090_912_502_u64,
            "fetchUrl": fetch_url,
            "kind": kind.to_ascii_lowercase(),
            "mediaType": media_type(document_case["id"].as_str().unwrap()),
            "resourceId": resource_id,
            "sha256": format!("sha256:{}", hex::encode(Sha256::digest(body))),
            "technicalDescriptor": Value::Object(descriptor)
        })
    }

    fn media_type(asset_case_id: &str) -> &'static str {
        if asset_case_id.starts_with("png-") {
            "image/png"
        } else if asset_case_id.starts_with("jpeg-") {
            "image/jpeg"
        } else if asset_case_id.starts_with("webp-") {
            "image/webp"
        } else if asset_case_id.starts_with("font-ttf-") {
            "font/ttf"
        } else if asset_case_id.starts_with("font-otf-") {
            "font/otf"
        } else {
            panic!("fixture media type is not mapped")
        }
    }

    fn asset_case<'a>(vectors: &'a Value, id: &str) -> &'a Value {
        vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == id)
            .unwrap()
    }

    fn target_policy() -> FetchTargetPolicy {
        FetchTargetPolicy::new(ASSET_FETCH_ORIGIN, crate::ASSET_FETCH_PATH_PREFIX).unwrap()
    }

    fn assert_stats(actual: PreparedResourceManifestStats, expected: &Value) {
        assert_eq!(
            actual.physical_fetch_bytes(),
            expected["physicalFetchBytes"]
        );
        assert_eq!(actual.raw_unique_content(), expected["rawUniqueContent"]);
        assert_eq!(actual.raw_retained_bytes(), expected["rawRetainedBytes"]);
        assert_eq!(
            actual.decoded_image_unique_content(),
            expected["decodedImageUniqueContent"]
        );
        assert_eq!(
            actual.decoded_image_retained_bytes(),
            expected["decodedImageRetainedBytes"]
        );
        assert_eq!(
            actual.prepared_font_unique_content(),
            expected["preparedFontUniqueContent"]
        );
        assert_eq!(
            actual.prepared_font_retained_tables(),
            expected["preparedFontRetainedTables"]
        );
    }

    fn string_array(value: &Value) -> Vec<String> {
        value
            .as_array()
            .unwrap()
            .iter()
            .map(|entry| entry.as_str().unwrap().to_owned())
            .collect()
    }

    struct FixtureFetcher {
        bodies: BTreeMap<String, Vec<u8>>,
        observations: Mutex<Vec<String>>,
        failure_index: Option<usize>,
    }

    impl FixtureFetcher {
        fn new(bodies: BTreeMap<String, Vec<u8>>, failure_index: Option<usize>) -> Self {
            Self {
                bodies,
                observations: Mutex::new(Vec::new()),
                failure_index,
            }
        }

        fn observations(&self) -> Vec<String> {
            self.observations.lock().unwrap().clone()
        }
    }

    impl ResourceFetcher for FixtureFetcher {
        fn fetch_resource(
            &self,
            target: &crate::AdmittedFetchTarget<'_>,
            _deadline_epoch_millis: i64,
            state: &mut RequestResourceFetchState,
            _control: &dyn ResourcePreparationControl,
        ) -> Result<crate::FetchedResource, ResourceFetchProblem> {
            let mut observations = self.observations.lock().unwrap();
            let index = observations.len();
            observations.push(target.resource_id().to_owned());
            drop(observations);
            if self.failure_index == Some(index) {
                return Err(ResourceFetchProblem::fetch_failed(target.resource_id()));
            }
            state.verify_owned_body(
                target,
                self.bodies
                    .get(target.resource_id())
                    .unwrap()
                    .clone()
                    .into_boxed_slice(),
            )
        }
    }

    fn decode_base64(value: &str) -> Vec<u8> {
        let mut output = Vec::new();
        let mut accumulator = 0_u32;
        let mut bits = 0_u8;
        for byte in value.bytes() {
            if byte == b'=' {
                break;
            }
            let value = match byte {
                b'A'..=b'Z' => byte - b'A',
                b'a'..=b'z' => byte - b'a' + 26,
                b'0'..=b'9' => byte - b'0' + 52,
                b'+' => 62,
                b'/' => 63,
                _ => panic!("invalid base64 fixture"),
            };
            accumulator = (accumulator << 6) | u32::from(value);
            bits += 6;
            if bits >= 8 {
                bits -= 8;
                output.push((accumulator >> bits) as u8);
                accumulator &= (1_u32 << bits) - 1;
            }
        }
        output
    }
}
