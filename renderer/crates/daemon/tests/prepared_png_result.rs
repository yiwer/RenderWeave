#[cfg(feature = "native-jpeg-turbo")]
use renderweave_renderer_daemon::seal_prepared_jpeg_result;
use renderweave_renderer_daemon::{
    PreparedPngResultError, TerminalResponse, seal_prepared_png_result,
};
use renderweave_renderer_document::{AdmittedRenderDocument, validate_render_document};
use renderweave_renderer_protocol::{
    AdmittedCommand, DOCUMENT_DIGEST_DOMAIN, SealedResult, digest_with_domain, parse_command,
};
use renderweave_renderer_resource::{
    ASSET_FETCH_PATH_PREFIX, AdmittedFetchTarget, FetchTargetPolicy, FetchedResource,
    ManifestResourcePreparer, PreparedResourceManifest, RequestResourceFetchState,
    ResourceFetchProblem, ResourceFetcher, ResourcePreparationProfile,
};
use serde_json::Value;

const PROTOCOL_VECTORS: &str = include_str!("../../../protocol-vectors-v1.json");
const ENGINE_VECTORS: &str = include_str!("../../../engine-png-vectors-v1.json");
#[cfg(feature = "native-jpeg-turbo")]
const JPEG_VECTORS: &str = include_str!("../../../output-jpeg-vectors-v1.json");
const PREPARED_IMAGE_VECTORS: &str =
    include_str!("../../../engine-prepared-image-png-vectors-v1.json");
const FETCH_ORIGIN: &str = "https://render.internal.example";
const STARTED_EPOCH_MILLIS: i64 = 1_800_000_000_000;

#[test]
fn seals_real_engine_png_into_exact_result_payloads_through_the_public_interface() {
    let engine_vectors: Value = serde_json::from_str(ENGINE_VECTORS).unwrap();
    let document_json = serde_json::to_string(&engine_vectors["documents"]["transparent1x1"])
        .expect("engine document must serialize canonically");
    let document = validate_render_document(&document_json).unwrap();
    let command_json = command_with_document(&document_json);
    let command = parse_command(command_json.as_bytes()).unwrap();
    let fetcher = NoFetch;
    let prepared = prepare_resources(&document, &command, &fetcher);

    let sealed = seal_prepared_png_result(&command, &document, &prepared).unwrap();
    let expected_case = engine_vectors["renderedCases"]
        .as_array()
        .unwrap()
        .iter()
        .find(|case| case["id"] == "transparent-empty-canvas-1x1")
        .unwrap();
    assert_sealed_png(sealed, &expected_case["expected"]);
}

#[cfg(feature = "native-jpeg-turbo")]
#[test]
fn seals_real_engine_jpeg_into_exact_result_payloads_through_the_public_interface() {
    let engine_vectors: Value = serde_json::from_str(ENGINE_VECTORS).unwrap();
    let jpeg_vectors: Value = serde_json::from_str(JPEG_VECTORS).unwrap();
    let document_json = serde_json::to_string(&engine_vectors["documents"]["transparent1x1"])
        .expect("engine document must serialize canonically");
    let document = validate_render_document(&document_json).unwrap();
    let command_json = command_with_document(&document_json).replace(
        "\"output\":{\"profile\":\"renderweave-output-png/1.0\",\"dpi\":96}",
        "\"output\":{\"profile\":\"renderweave-output-jpeg/1.0\",\"dpi\":96,\"quality\":90}",
    );
    let command = parse_command(command_json.as_bytes()).unwrap();
    let fetcher = NoFetch;
    let prepared = prepare_resources(&document, &command, &fetcher);

    let sealed = seal_prepared_jpeg_result(&command, &document, &prepared).unwrap();
    let expected = jpeg_vectors["jpegCases"]
        .as_array()
        .unwrap()
        .iter()
        .find(|case| case["id"] == "transparent-white-matte-1x1-q90")
        .unwrap();
    assert_sealed_jpeg(sealed, &expected["expected"]);
}

#[test]
fn seals_a_real_fetched_image_and_isolated_partial_opacity_subtree_without_a_profile_bypass() {
    let vectors: Value = serde_json::from_str(PREPARED_IMAGE_VECTORS).unwrap();
    let fixture = &vectors["resourceFixtures"]["opaque2x2"];
    let expected_case = vectors["renderedCases"]
        .as_array()
        .unwrap()
        .iter()
        .find(|case| case["id"] == "partial-container-image-and-rect-isolate-before-composite")
        .unwrap();
    let mut document_value = vectors["documentTemplate"].clone();
    document_value["resources"] = serde_json::json!([fixture["resource"].clone()]);
    document_value["canvas"]["children"] = expected_case["mutations"][0]["value"].clone();
    let document_json = serde_json::to_string(&document_value).unwrap();
    let document = validate_render_document(&document_json).unwrap();
    let command_json =
        command_with_document_and_deadline(&document_json, Some("2033-05-18T03:33:20.000Z"));
    let command = parse_command(command_json.as_bytes()).unwrap();
    assert_eq!(command.deadline_epoch_millis, 2_000_000_000_000);
    let fetcher = StaticFetch {
        body: decode_hex(fixture["bodyHex"].as_str().unwrap()),
    };
    let prepared = prepare_resources(&document, &command, &fetcher);

    let sealed = seal_prepared_png_result(&command, &document, &prepared).unwrap();
    assert_sealed_png(sealed, &expected_case["expected"]);
}

#[test]
fn rejects_identity_drift_trace_and_jpeg_before_constructing_result_frames() {
    let engine_vectors: Value = serde_json::from_str(ENGINE_VECTORS).unwrap();
    let document_json = serde_json::to_string(&engine_vectors["documents"]["transparent1x1"])
        .expect("engine document must serialize canonically");
    let document = validate_render_document(&document_json).unwrap();
    let fetcher = NoFetch;

    let command_json = command_with_document(&document_json);
    let command = parse_command(command_json.as_bytes()).unwrap();
    let prepared = prepare_resources(&document, &command, &fetcher);
    let other_document_json = serde_json::to_string(&engine_vectors["documents"]["opaque2x1"])
        .expect("engine document must serialize canonically");
    let other_document = validate_render_document(&other_document_json).unwrap();
    assert!(matches!(
        seal_prepared_png_result(&command, &other_document, &prepared),
        Err(PreparedPngResultError::Contract(_))
    ));

    let trace_command = parse_command(
        command_json
            .replace("\"layoutTrace\":false", "\"layoutTrace\":true")
            .as_bytes(),
    )
    .unwrap();
    assert!(matches!(
        seal_prepared_png_result(&trace_command, &document, &prepared),
        Err(PreparedPngResultError::Contract(_))
    ));

    let jpeg_command = parse_command(
        command_json
            .replace(
                "\"output\":{\"profile\":\"renderweave-output-png/1.0\",\"dpi\":96}",
                "\"output\":{\"profile\":\"renderweave-output-jpeg/1.0\",\"dpi\":96,\"quality\":90}",
            )
            .as_bytes(),
    )
    .unwrap();
    assert!(matches!(
        seal_prepared_png_result(&jpeg_command, &document, &prepared),
        Err(PreparedPngResultError::Contract(_))
    ));

    let wrong_profile_command = parse_command(
        command_json
            .replace("renderweave-renderer/1.0", "renderweave-renderer/2.0")
            .as_bytes(),
    )
    .unwrap();
    assert!(matches!(
        seal_prepared_png_result(&wrong_profile_command, &document, &prepared),
        Err(PreparedPngResultError::Contract(_))
    ));
}

fn assert_sealed_png(sealed: SealedResult, expected: &Value) {
    let image_bytes = decode_hex(expected["exactHex"].as_str().unwrap());
    let content_sha256 = expected["contentSha256"]
        .as_str()
        .unwrap()
        .strip_prefix("sha256:")
        .unwrap();
    let expected_metadata = format!(
        concat!(
            "{{\"contractVersion\":\"renderweave-render-result/1.0\",",
            "\"requestId\":\"123e4567-e89b-42d3-a456-426614174000\",",
            "\"rendererProfile\":\"renderweave-renderer/1.0\",",
            "\"dslVersion\":\"renderweave-render/1.0\",",
            "\"layoutProfile\":\"renderweave-layout/1.0\",",
            "\"outputProfile\":\"renderweave-output-png/1.0\",",
            "\"format\":\"PNG\",\"mediaType\":\"image/png\",",
            "\"widthPx\":{},\"heightPx\":{},\"dpi\":96,",
            "\"byteLength\":{},\"contentSha256\":\"{}\"}}"
        ),
        expected["widthPx"].as_u64().unwrap(),
        expected["heightPx"].as_u64().unwrap(),
        image_bytes.len(),
        content_sha256
    );
    assert_eq!(expected_metadata.as_bytes(), sealed.metadata_payload());

    let mut expected_image_payload = decode_hex("123e4567e89b42d3a456426614174000");
    expected_image_payload.extend_from_slice(&image_bytes);
    assert_eq!(expected_image_payload, sealed.image_payload());
    assert_eq!(image_bytes.len() as u64, sealed.byte_length());
    assert_eq!(content_sha256, sealed.content_sha256());

    let terminal = TerminalResponse::sealed_result(sealed);
    assert_eq!(2, terminal.frames().len());
    assert_eq!(
        renderweave_renderer_protocol::FrameType::ResultMetadata,
        terminal.frames()[0].frame_type
    );
    assert_eq!(expected_metadata.as_bytes(), terminal.frames()[0].payload);
    assert_eq!(
        renderweave_renderer_protocol::FrameType::ResultImage,
        terminal.frames()[1].frame_type
    );
    assert_eq!(expected_image_payload, terminal.frames()[1].payload);
}

#[cfg(feature = "native-jpeg-turbo")]
fn assert_sealed_jpeg(sealed: SealedResult, expected: &Value) {
    let image_bytes = sealed.image_payload()[16..].to_vec();
    assert_eq!(
        expected["byteLength"].as_u64().unwrap(),
        image_bytes.len() as u64
    );
    assert_eq!(
        decode_hex(expected["entropyHex"].as_str().unwrap()),
        entropy_bytes(&image_bytes)
    );
    let content_sha256 = expected["sha256"]
        .as_str()
        .unwrap()
        .strip_prefix("sha256:")
        .unwrap();
    let expected_metadata = format!(
        concat!(
            "{{\"contractVersion\":\"renderweave-render-result/1.0\",",
            "\"requestId\":\"123e4567-e89b-42d3-a456-426614174000\",",
            "\"rendererProfile\":\"renderweave-renderer/1.0\",",
            "\"dslVersion\":\"renderweave-render/1.0\",",
            "\"layoutProfile\":\"renderweave-layout/1.0\",",
            "\"outputProfile\":\"renderweave-output-jpeg/1.0\",",
            "\"format\":\"JPEG\",\"mediaType\":\"image/jpeg\",",
            "\"widthPx\":1,\"heightPx\":1,\"dpi\":96,",
            "\"byteLength\":{},\"contentSha256\":\"{}\",\"quality\":90}}"
        ),
        image_bytes.len(),
        content_sha256
    );
    assert_eq!(expected_metadata.as_bytes(), sealed.metadata_payload());

    let mut expected_image_payload = decode_hex("123e4567e89b42d3a456426614174000");
    expected_image_payload.extend_from_slice(&image_bytes);
    assert_eq!(expected_image_payload, sealed.image_payload());
    assert_eq!(image_bytes.len() as u64, sealed.byte_length());
    assert_eq!(content_sha256, sealed.content_sha256());

    let terminal = TerminalResponse::sealed_result(sealed);
    assert_eq!(2, terminal.frames().len());
    assert_eq!(expected_metadata.as_bytes(), terminal.frames()[0].payload);
    assert_eq!(expected_image_payload, terminal.frames()[1].payload);
}

#[cfg(feature = "native-jpeg-turbo")]
fn entropy_bytes(encoded: &[u8]) -> Vec<u8> {
    let mut offset = 2_usize;
    while offset + 4 <= encoded.len() {
        assert_eq!(0xff, encoded[offset]);
        let marker = encoded[offset + 1];
        let length = u16::from_be_bytes([encoded[offset + 2], encoded[offset + 3]]) as usize;
        if marker == 0xda {
            return encoded[offset + 2 + length..encoded.len() - 2].to_vec();
        }
        offset += 2 + length;
    }
    panic!("JPEG SOS is absent")
}

fn prepare_resources(
    document: &AdmittedRenderDocument,
    command: &AdmittedCommand,
    fetcher: &dyn ResourceFetcher,
) -> PreparedResourceManifest {
    let fetch_policy = FetchTargetPolicy::new(FETCH_ORIGIN, ASSET_FETCH_PATH_PREFIX).unwrap();
    ManifestResourcePreparer::new(
        &fetch_policy,
        fetcher,
        ResourcePreparationProfile::RendererV1,
    )
    .prepare(
        document.resources(),
        command.deadline_epoch_millis,
        STARTED_EPOCH_MILLIS,
    )
    .unwrap()
}

fn command_with_document(document: &str) -> String {
    command_with_document_and_deadline(document, None)
}

fn command_with_document_and_deadline(document: &str, deadline_at: Option<&str>) -> String {
    let protocol_vectors: Value = serde_json::from_str(PROTOCOL_VECTORS).unwrap();
    let command_case = protocol_vectors["cases"]
        .as_array()
        .unwrap()
        .iter()
        .find(|case| case["id"] == "png-command")
        .unwrap();
    let command = command_case["canonicalJson"].as_str().unwrap();
    let original_document = command_case["documentCanonicalJson"].as_str().unwrap();
    let old_digest = command_case["renderDocumentDigest"].as_str().unwrap();
    let digest = digest_with_domain(DOCUMENT_DIGEST_DOMAIN, document.as_bytes());
    let command = command
        .replace(original_document, document)
        .replace(old_digest, &digest);
    match deadline_at {
        Some(deadline_at) => command.replace("2099-08-20T12:34:56.789Z", deadline_at),
        None => command,
    }
}

fn decode_hex(value: &str) -> Vec<u8> {
    assert!(value.len().is_multiple_of(2));
    value
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| (hex_nibble(pair[0]) << 4) | hex_nibble(pair[1]))
        .collect()
}

fn hex_nibble(value: u8) -> u8 {
    match value {
        b'0'..=b'9' => value - b'0',
        b'a'..=b'f' => value - b'a' + 10,
        _ => panic!("fixture hex must be lowercase"),
    }
}

struct NoFetch;

impl ResourceFetcher for NoFetch {
    fn fetch_resource(
        &self,
        _target: &AdmittedFetchTarget<'_>,
        _deadline_epoch_millis: i64,
        _state: &mut RequestResourceFetchState,
        _control: &dyn renderweave_renderer_resource::ResourcePreparationControl,
    ) -> Result<FetchedResource, ResourceFetchProblem> {
        panic!("resource-free fixture must not fetch")
    }
}

struct StaticFetch {
    body: Vec<u8>,
}

impl ResourceFetcher for StaticFetch {
    fn fetch_resource(
        &self,
        target: &AdmittedFetchTarget<'_>,
        _deadline_epoch_millis: i64,
        state: &mut RequestResourceFetchState,
        _control: &dyn renderweave_renderer_resource::ResourcePreparationControl,
    ) -> Result<FetchedResource, ResourceFetchProblem> {
        state.verify_owned_body(target, self.body.clone().into_boxed_slice())
    }
}
