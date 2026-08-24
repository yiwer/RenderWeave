use renderweave_renderer_document::{RenderResourceMediaType, validate_render_document};
use renderweave_renderer_resource::{
    MAX_REQUEST_RAW_CACHE_BYTES, REQUEST_RAW_CACHE_BYTES_LIMIT_ID, ResourcePreparationProfile,
    verify_resource_media,
};
use serde_json::{Value, json};

const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");
const PNG_RGBA_HEX: &str = "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c63103209ab0000020d0115a97ea5c60000000049454e44ae426082";
const PNG_SHA256: &str = "sha256:7bab3dc79240cb795432412f3e01c906fabd58d4f67e11b1ed9fdbb9782f0c04";

#[test]
fn raw_cache_profile_and_limit_are_public_but_do_not_register_availability() {
    assert_eq!(
        ResourcePreparationProfile::RendererV1.as_str(),
        "renderweave-renderer/1.0"
    );
    assert_eq!(MAX_REQUEST_RAW_CACHE_BYTES, 268_435_456);
    assert_eq!(
        REQUEST_RAW_CACHE_BYTES_LIMIT_ID,
        "assetsAndFetch.requestRawCacheBytes"
    );
}

#[test]
fn valid_png_bytes_produce_an_opaque_verified_media_token() {
    let bytes = hex::decode(PNG_RGBA_HEX).unwrap();
    let mut document: Value = serde_json::from_str(ALL_KINDS).unwrap();
    let image = &mut document["resources"].as_array_mut().unwrap()[1];
    image["byteLength"] = json!(bytes.len());
    image["sha256"] = json!(PNG_SHA256);
    image["technicalDescriptor"]["encodedWidthPx"] = json!(1);
    image["technicalDescriptor"]["encodedHeightPx"] = json!(1);
    image["technicalDescriptor"]["logicalWidthPx"] = json!(1);
    image["technicalDescriptor"]["logicalHeightPx"] = json!(1);
    let canonical = serde_json::to_string(&document).unwrap();
    let admitted = validate_render_document(&canonical).unwrap();
    let resource = &admitted.resources()[1];

    let verified = verify_resource_media(resource, &bytes).unwrap();

    assert_eq!(verified.resource_id(), resource.resource_id());
    assert_eq!(verified.media_type(), RenderResourceMediaType::ImagePng);
    assert_eq!(verified.byte_length(), 70);
}
