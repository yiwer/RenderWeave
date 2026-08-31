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
    sampling_mapping: String,
    nearest_tie_rule: String,
    linear_arithmetic: String,
    alpha_arithmetic: String,
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
    #[serde(default)]
    additional_resource_fixture_ids: Vec<String>,
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

#[derive(Debug, Deserialize, Eq, PartialEq)]
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
        let fixtures = fixtures_for(
            &vectors,
            &case.resource_fixture_id,
            &case.additional_resource_fixture_ids,
        );
        let document = admitted_document(&vectors, &fixtures, &case.mutations);
        let manifest = prepared_manifest(&document, &fixtures);
        assert_prepared_fixtures(&manifest, &fixtures, &case.id);

        let output = render_png_with_prepared_resources(&document, &manifest, case.dpi)
            .unwrap_or_else(|error| panic!("{} unexpectedly rejected: {error}", case.id));
        assert_eq!(case.dpi, output.dpi(), "{}", case.id);
        let actual = RenderedExpected {
            width_px: output.width_px(),
            height_px: output.height_px(),
            media_type: output.media_type().to_owned(),
            output_profile: output.output_profile().to_owned(),
            byte_length: output.byte_length(),
            content_sha256: output.content_sha256().to_owned(),
            pixel_sha256: output.pixel_sha256().to_owned(),
            exact_hex: hex::encode(output.bytes()),
        };
        assert_eq!(case.expected, actual, "{}", case.id);
    }
}

#[test]
fn rejects_prepared_images_outside_the_frozen_quarter_turn_1_to_1_subset() {
    let vectors = vectors();
    for case in &vectors.unsupported_cases {
        let fixtures = fixtures_for(&vectors, &case.resource_fixture_id, &[]);
        let document = admitted_document(&vectors, &fixtures, &case.mutations);
        let manifest = prepared_manifest(&document, &fixtures);
        assert_prepared_fixtures(&manifest, &fixtures, &case.id);

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
        "renderweave-engine-prepared-image-png-vectors/4",
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
        "SOURCE_AND_INTEGER_DEVICE_BOX_EXACT_1_TO_1_CENTERED_UNIT_QUARTER_TURN_NO_RESAMPLE",
        authority.degenerate_mapping
    );
    assert_eq!(
        "INTEGER_DEVICE_BOX_HALF_INTEGER_CENTER_INVERSE_EDGE_COORDINATE_CONTAIN_COVER_FILL",
        authority.sampling_mapping
    );
    assert_eq!(
        "EXACT_EQUAL_DISTANCE_TO_LOWER_SOURCE_INDEX_EDGE_CLAMP",
        authority.nearest_tie_rule
    );
    assert_eq!(
        "SOURCE_PREMULTIPLY_RGBA8_EXACT_RATIONAL_BILINEAR_SINGLE_ROUND_HALF_UP_EDGE_CLAMP",
        authority.linear_arithmetic
    );
    assert_eq!(
        "STRAIGHT_TO_PREMULTIPLIED_MUL255_SOURCE_OVER_AUTHORED_ORDER_SUBTREE_OPACITY_ROUND_HALF_UP_255_SINGLE_FINAL_UNPREMULTIPLY",
        authority.alpha_arithmetic
    );
    assert_eq!(
        "PREPARED_IMAGE_INTEGER_BOX_CONTAIN_COVER_FILL_NEAREST_LINEAR_EXACT_RATIONAL_PREMULTIPLIED_SOURCE_OVER_CENTERED_UNIT_QUARTER_TURN_SUBTREE_OPACITY_EXACT_PNG_AUTOMATED_VERIFIED_PROFILE_GATED",
        authority.engine_prepared_image_kernel
    );
    assert_eq!("NOT_REGISTERED", authority.profile_availability);
    assert_eq!("NOT_CERTIFIED", authority.certification_status);
    assert_eq!("ABSENT", authority.process_raster_implementation);
    assert_eq!("UNWIRED", authority.daemon_output_path);
    assert_eq!("CLOSED", authority.product_route);
    assert_eq!(0, authority.provider_attempts);
    assert_eq!(31, vectors.rendered_cases.len());
    assert_eq!(2, vectors.unsupported_cases.len());
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

fn fixtures_for<'vectors>(
    vectors: &'vectors Vectors,
    primary_id: &str,
    additional_ids: &[String],
) -> Vec<&'vectors ResourceFixture> {
    let mut fixtures = Vec::with_capacity(1 + additional_ids.len());
    fixtures.push(fixture(vectors, primary_id));
    fixtures.extend(additional_ids.iter().map(|id| fixture(vectors, id)));
    fixtures
}

fn admitted_document(
    vectors: &Vectors,
    fixtures: &[&ResourceFixture],
    mutations: &[Mutation],
) -> AdmittedRenderDocument {
    let mut document: Value = serde_json::from_str(vectors.document_template.get()).unwrap();
    let resources = fixtures
        .iter()
        .map(|fixture| serde_json::from_str(fixture.resource.get()).unwrap())
        .collect::<Vec<Value>>();
    let resource_id = resources[0]["resourceId"].as_str().unwrap().to_owned();
    document["resources"] = Value::Array(resources);
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
    fixtures: &[&ResourceFixture],
) -> PreparedResourceManifest {
    let policy = FetchTargetPolicy::new(FETCH_ORIGIN, ASSET_FETCH_PATH_PREFIX).unwrap();
    let bodies = fixtures
        .iter()
        .map(|fixture| {
            let resource: Value = serde_json::from_str(fixture.resource.get()).unwrap();
            (
                resource["resourceId"].as_str().unwrap().to_owned(),
                hex::decode(&fixture.body_hex).unwrap(),
            )
        })
        .collect();
    let fetcher = FixtureFetcher { bodies };
    ManifestResourcePreparer::new(&policy, &fetcher, ResourcePreparationProfile::RendererV1)
        .prepare(
            document.resources(),
            DEADLINE_EPOCH_MILLIS,
            STARTED_EPOCH_MILLIS,
        )
        .expect("prepared IMAGE manifest")
}

fn assert_prepared_fixtures(
    manifest: &PreparedResourceManifest,
    fixtures: &[&ResourceFixture],
    case_id: &str,
) {
    assert_eq!(fixtures.len(), manifest.resources().len(), "{case_id}");
    for fixture in fixtures {
        let resource: Value = serde_json::from_str(fixture.resource.get()).unwrap();
        let resource_id = resource["resourceId"].as_str().unwrap();
        let Some(PreparedRenderResource::Image { image, .. }) = manifest.get(resource_id) else {
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
}

struct FixtureFetcher {
    bodies: BTreeMap<String, Vec<u8>>,
}

impl ResourceFetcher for FixtureFetcher {
    fn fetch_resource(
        &self,
        target: &AdmittedFetchTarget<'_>,
        _deadline_epoch_millis: i64,
        state: &mut RequestResourceFetchState,
        _control: &dyn renderweave_renderer_resource::ResourcePreparationControl,
    ) -> Result<renderweave_renderer_resource::FetchedResource, ResourceFetchProblem> {
        state.verify_owned_body(
            target,
            self.bodies[target.resource_id()].clone().into_boxed_slice(),
        )
    }
}
