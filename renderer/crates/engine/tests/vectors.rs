use std::collections::BTreeMap;

use renderweave_renderer_document::validate_render_document;
use renderweave_renderer_engine::render_png;
use serde::Deserialize;
use serde_json::value::RawValue;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Vectors {
    vector_version: String,
    boundary: Boundary,
    documents: BTreeMap<String, Box<RawValue>>,
    rendered_cases: Vec<RenderedCase>,
    unsupported_cases: Vec<UnsupportedCase>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Boundary {
    profile_availability: String,
    certification_status: String,
    engine_png_kernel: String,
    process_raster_implementation: String,
    daemon_output_path: String,
    product_route: String,
    provider_attempts: u32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RenderedCase {
    id: String,
    document_id: String,
    dpi: u32,
    expected: RenderedExpected,
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

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct UnsupportedCase {
    id: String,
    document_id: String,
    dpi: u32,
    expected: UnsupportedExpected,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct UnsupportedExpected {
    #[serde(default)]
    feature: Option<String>,
    #[serde(default)]
    code: Option<String>,
    #[serde(default)]
    stage: Option<String>,
    #[serde(default)]
    limit_id: Option<String>,
}

#[test]
fn renders_shared_engine_png_vectors() {
    let vectors = vectors();
    assert_eq!("renderweave-engine-png-vectors/1", vectors.vector_version);
    assert_honest_boundary(&vectors.boundary);

    for case in &vectors.rendered_cases {
        let document = admitted_document(&vectors, &case.document_id);
        let output = render_png(&document, case.dpi)
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

fn assert_honest_boundary(boundary: &Boundary) {
    assert_eq!("NOT_REGISTERED", boundary.profile_availability);
    assert_eq!("NOT_CERTIFIED", boundary.certification_status);
    assert_eq!(
        "PREORDER_FIXED_IDENTITY_FRAME_RECT_PIXEL_ALIGNED_OPAQUE_PNG_KERNEL_UNWIRED",
        boundary.engine_png_kernel
    );
    assert_eq!("ABSENT", boundary.process_raster_implementation);
    assert_eq!("UNWIRED", boundary.daemon_output_path);
    assert_eq!("CLOSED", boundary.product_route);
    assert_eq!(0, boundary.provider_attempts);
}

#[test]
fn rejects_inputs_outside_the_frozen_engine_png_subset() {
    let vectors = vectors();
    for case in &vectors.unsupported_cases {
        let document = admitted_document(&vectors, &case.document_id);
        let error = render_png(&document, case.dpi).unwrap_err();
        assert_eq!(
            case.expected.feature.as_deref(),
            error.unsupported_feature(),
            "{}",
            case.id
        );
        assert_eq!(case.expected.code.as_deref(), error.code(), "{}", case.id);
        assert_eq!(case.expected.stage.as_deref(), error.stage(), "{}", case.id);
        assert_eq!(
            case.expected.limit_id.as_deref(),
            error.limit_id(),
            "{}",
            case.id
        );
    }
}

fn vectors() -> Vectors {
    serde_json::from_str(include_str!("../../../engine-png-vectors-v1.json"))
        .expect("shared Engine PNG vectors")
}

fn admitted_document(
    vectors: &Vectors,
    document_id: &str,
) -> renderweave_renderer_document::AdmittedRenderDocument {
    let raw = vectors
        .documents
        .get(document_id)
        .unwrap_or_else(|| panic!("missing document {document_id}"));
    validate_render_document(raw.get())
        .unwrap_or_else(|error| panic!("document {document_id} is not admitted: {error}"))
}
