use std::collections::BTreeMap;

use renderweave_renderer_document::{AdmittedRenderDocument, validate_render_document};
use renderweave_renderer_engine::render_png_with_prepared_resources;
use renderweave_renderer_resource::{
    ASSET_FETCH_PATH_PREFIX, AdmittedFetchTarget, FetchTargetPolicy, ManifestResourcePreparer,
    PreparedRenderResource, PreparedResourceManifest, RequestResourceFetchState,
    ResourceFetchProblem, ResourceFetcher, ResourcePreparationProfile,
};
use serde::Deserialize;
use serde_json::{Value, value::RawValue};

const FETCH_ORIGIN: &str = "https://render.internal.example";
const DEADLINE_EPOCH_MILLIS: i64 = 2_000_000_000_000;
const STARTED_EPOCH_MILLIS: i64 = 1_900_000_000_000;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Vectors {
    vector_version: String,
    authority_context: AuthorityContext,
    resource_fixtures: BTreeMap<String, ResourceFixture>,
    document_template: Box<RawValue>,
    rendered_cases: Vec<RenderedCase>,
    unsupported_cases: Vec<UnsupportedCase>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AuthorityContext {
    layout_profile: String,
    resource_preparation_profile: String,
    image_pixels: String,
    degenerate_mapping: String,
    engine_prepared_image_kernel: String,
    profile_availability: String,
    certification_status: String,
    process_raster_implementation: String,
    daemon_output_path: String,
    product_route: String,
    provider_attempts: u32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ResourceFixture {
    resource: Box<RawValue>,
    body_hex: String,
    logical_width_px: u32,
    logical_height_px: u32,
    straight_rgba8_hex: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RenderedCase {
    id: String,
    resource_fixture_id: String,
    mutations: Vec<Mutation>,
    dpi: u32,
    expected: RenderedExpected,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct UnsupportedCase {
    id: String,
    resource_fixture_id: String,
    mutations: Vec<Mutation>,
    dpi: u32,
    expected_feature: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Mutation {
    operation: String,
    pointer: String,
    value: Value,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RenderedExpected {
    width_px: u32,
    height_px: u32,
    media_type: String,
    output_profile: String,
    byte_length: usize,
    content_sha256: String,
    pixel_sha256: String,
    exact_hex: String,
}

#[test]
fn renders_prepared_image_vectors_through_the_public_engine_interface() {
    let vectors = vectors();
    assert_contract(&vectors);
    for case in &vectors.rendered_cases {
        let fixture = fixture(&vectors, &case.resource_fixture_id);
        let document = admitted_document(&vectors, fixture, &case.mutations);
        let manifest = prepared_manifest(&document, fixture);
        assert_prepared_fixture(&manifest, fixture, &case.id);

        let output = render_png_with_prepared_resources(&document, &manifest, case.dpi)
            .unwrap_or_else(|error| panic!("{} unexpectedly rejected: {error}", case.id));
        assert_eq!(case.expected.width_px, output.width_px(), "{}", case.id);
        assert_eq!(case.expected.height_px, output.height_px(), "{}", case.id);
        assert_eq!(case.dpi, output.dpi(), "{}", case.id);
        assert_eq!(case.expected.media_type, output.media_type(), "{}", case.id);
        assert_eq!(
            case.expected.output_profile,
            output.output_profile(),
            "{}",
            case.id
        );
        assert_eq!(
            case.expected.byte_length,
            output.byte_length(),
            "{}",
            case.id
        );
        assert_eq!(
            case.expected.content_sha256,
            output.content_sha256(),
            "{}",
            case.id
        );
        assert_eq!(
            case.expected.pixel_sha256,
            output.pixel_sha256(),
            "{}",
            case.id
        );
        assert_eq!(
            case.expected.exact_hex,
            hex::encode(output.bytes()),
            "{}",
            case.id
        );
    }
}

#[test]
fn rejects_prepared_images_outside_the_frozen_exact_copy_subset() {
    let vectors = vectors();
    for case in &vectors.unsupported_cases {
        let fixture = fixture(&vectors, &case.resource_fixture_id);
        let document = admitted_document(&vectors, fixture, &case.mutations);
        let manifest = prepared_manifest(&document, fixture);
        assert_prepared_fixture(&manifest, fixture, &case.id);

        let error =
            render_png_with_prepared_resources(&document, &manifest, case.dpi).expect_err(&case.id);
        assert_eq!(
            Some(case.expected_feature.as_str()),
            error.unsupported_feature(),
            "{}",
            case.id
        );
    }
}

fn assert_contract(vectors: &Vectors) {
    assert_eq!(
        "renderweave-engine-prepared-image-png-vectors/1",
        vectors.vector_version
    );
    let authority = &vectors.authority_context;
    assert_eq!("renderweave-layout/1.0", authority.layout_profile);
    assert_eq!(
        "renderweave-renderer/1.0",
        authority.resource_preparation_profile
    );
    assert_eq!(
        "EXACT_ORIENTATION_NORMALIZED_STRAIGHT_RGBA8",
        authority.image_pixels
    );
    assert_eq!(
        "SOURCE_AND_INTEGER_DEVICE_BOX_EXACT_1_TO_1_NO_RESAMPLE",
        authority.degenerate_mapping
    );
    assert_eq!(
        "PREPARED_IMAGE_OPAQUE_1_TO_1_AUTHORED_ORDER_RECTANGULAR_CLIP_EXACT_PNG_AUTOMATED_VERIFIED_UNWIRED",
        authority.engine_prepared_image_kernel
    );
    assert_eq!("NOT_REGISTERED", authority.profile_availability);
    assert_eq!("NOT_CERTIFIED", authority.certification_status);
    assert_eq!("ABSENT", authority.process_raster_implementation);
    assert_eq!("UNWIRED", authority.daemon_output_path);
    assert_eq!("CLOSED", authority.product_route);
    assert_eq!(0, authority.provider_attempts);
    assert_eq!(9, vectors.rendered_cases.len());
    assert_eq!(5, vectors.unsupported_cases.len());
}

fn vectors() -> Vectors {
    serde_json::from_str(include_str!(
        "../../../engine-prepared-image-png-vectors-v1.json"
    ))
    .expect("shared prepared IMAGE Engine PNG vectors")
}

fn fixture<'vectors>(vectors: &'vectors Vectors, id: &str) -> &'vectors ResourceFixture {
    vectors
        .resource_fixtures
        .get(id)
        .unwrap_or_else(|| panic!("missing resource fixture {id}"))
}

fn admitted_document(
    vectors: &Vectors,
    fixture: &ResourceFixture,
    mutations: &[Mutation],
) -> AdmittedRenderDocument {
    let mut document: Value = serde_json::from_str(vectors.document_template.get()).unwrap();
    let resource: Value = serde_json::from_str(fixture.resource.get()).unwrap();
    let resource_id = resource["resourceId"].as_str().unwrap().to_owned();
    document["resources"] = Value::Array(vec![resource]);
    replace_image_resource_ids(&mut document["canvas"], &resource_id);
    for mutation in mutations {
        assert_eq!("replace", mutation.operation, "unsupported vector mutation");
        let target = document
            .pointer_mut(&mutation.pointer)
            .unwrap_or_else(|| panic!("missing mutation pointer {}", mutation.pointer));
        *target = mutation.value.clone();
    }
    let raw = serde_json::to_string(&document).unwrap();
    validate_render_document(&raw)
        .unwrap_or_else(|error| panic!("mutated prepared IMAGE document is not admitted: {error}"))
}

fn replace_image_resource_ids(value: &mut Value, resource_id: &str) {
    match value {
        Value::Array(values) => {
            for value in values {
                replace_image_resource_ids(value, resource_id);
            }
        }
        Value::Object(values) => {
            if let Some(Value::String(current)) = values.get_mut("imageResourceId") {
                *current = resource_id.to_owned();
            }
            for value in values.values_mut() {
                replace_image_resource_ids(value, resource_id);
            }
        }
        _ => {}
    }
}

fn prepared_manifest(
    document: &AdmittedRenderDocument,
    fixture: &ResourceFixture,
) -> PreparedResourceManifest {
    let policy = FetchTargetPolicy::new(FETCH_ORIGIN, ASSET_FETCH_PATH_PREFIX).unwrap();
    let fetcher = FixtureFetcher {
        body: hex::decode(&fixture.body_hex).unwrap(),
    };
    ManifestResourcePreparer::new(&policy, &fetcher, ResourcePreparationProfile::RendererV1)
        .prepare(
            document.resources(),
            DEADLINE_EPOCH_MILLIS,
            STARTED_EPOCH_MILLIS,
        )
        .expect("prepared IMAGE manifest")
}

fn assert_prepared_fixture(
    manifest: &PreparedResourceManifest,
    fixture: &ResourceFixture,
    case_id: &str,
) {
    assert_eq!(1, manifest.resources().len(), "{case_id}");
    let PreparedRenderResource::Image { image, .. } = &manifest.resources()[0] else {
        panic!("{case_id}: prepared resource is not an IMAGE");
    };
    assert_eq!(fixture.logical_width_px, image.width_px(), "{case_id}");
    assert_eq!(fixture.logical_height_px, image.height_px(), "{case_id}");
    assert_eq!(
        fixture.straight_rgba8_hex,
        hex::encode(image.straight_rgba8()),
        "{case_id}"
    );
}

struct FixtureFetcher {
    body: Vec<u8>,
}

impl ResourceFetcher for FixtureFetcher {
    fn fetch_resource(
        &self,
        target: &AdmittedFetchTarget<'_>,
        _deadline_epoch_millis: i64,
        state: &mut RequestResourceFetchState,
    ) -> Result<renderweave_renderer_resource::FetchedResource, ResourceFetchProblem> {
        state.verify_owned_body(target, self.body.clone().into_boxed_slice())
    }
}
