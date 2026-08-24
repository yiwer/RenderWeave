use renderweave_renderer_resource::{
    DECODER_SCRATCH_BYTES_LIMIT_ID, MAX_DECODER_SCRATCH_BYTES, MAX_REQUEST_DECODED_CACHE_BYTES,
    REQUEST_DECODED_CACHE_BYTES_LIMIT_ID, RequestDecodedImageCache,
};

#[test]
fn decoded_image_cache_limits_are_public_and_exact() {
    assert_eq!(MAX_REQUEST_DECODED_CACHE_BYTES, 536_870_912);
    assert_eq!(
        REQUEST_DECODED_CACHE_BYTES_LIMIT_ID,
        "assetsAndFetch.requestDecodedCacheBytes"
    );
    assert_eq!(MAX_DECODER_SCRATCH_BYTES, 134_217_728);
    assert_eq!(
        DECODER_SCRATCH_BYTES_LIMIT_ID,
        "rendererSurfaceAndOutput.decoderScratchBytes"
    );

    let cache = RequestDecodedImageCache::new();
    assert_eq!(cache.retained_bytes(), 0);
    assert_eq!(cache.unique_content_count(), 0);
}
