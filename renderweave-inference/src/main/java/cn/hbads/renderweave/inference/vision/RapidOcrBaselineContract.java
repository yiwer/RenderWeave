package cn.hbads.renderweave.inference.vision;

/**
 * Exact provider-neutral contract for the single R0 RapidOCR/OpenVINO baseline.
 *
 * <p>The application adapter consumes this contract; evaluation code may bind its identity without
 * loading the Python process, model files, credentials, or image payloads.</p>
 */
public final class RapidOcrBaselineContract {
    public static final String CAPABILITY_IDENTITY =
            "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1";
    public static final String ADAPTER_IDENTITY = "rapidocr-local-process/1.0";
    public static final String ENGINE = "rapidocr-openvino-ppocrv6-small";
    public static final String ENGINE_VERSION = "rapidocr-3.9.2+openvino-2026.0.0";
    public static final String MODEL_MANIFEST_SHA256 =
            "c05805399d7d10b1d1e32f2f52faf2a9fe6617db50f6b96221cb3b7be47e58a5";
    public static final String PREPROCESSING_IDENTITY = "explicit-bgr/1.0";
    public static final String POSTPROCESSING_IDENTITY = "rapidocr-lines/1.0";
    public static final String COORDINATE_SPACE_IDENTITY = "source-pixel-top-left/1.0";
    public static final String BOX_SEMANTICS_IDENTITY = "half-open-box/1.0";
    public static final String PROJECTION_IDENTITY = DocumentObservationCompatibilityProjection.VERSION;
    public static final String READING_ORDER_IDENTITY = "top-left-canonical/1.0";
    public static final String CANONICALIZATION_IDENTITY = "unicode-nfc-whitespace-collapse/1.0";
    public static final String CONFIDENCE_SCALE_IDENTITY = "basis-points/1.0";
    public static final String CONFIDENCE_BUCKET_IDENTITY = "v45-confidence-buckets/1.0";
    public static final int MAXIMUM_RESPONSE_BYTES = 512 * 1024;
    public static final int DEFAULT_TIMEOUT_MILLIS = 30_000;

    private RapidOcrBaselineContract() { }

    public static AcquisitionPolicy policy(int timeoutMillis) {
        return new AcquisitionPolicy(
                AcquisitionPolicy.VERSION,
                DocumentObservationIR.VERSION,
                CAPABILITY_IDENTITY,
                ADAPTER_IDENTITY,
                ENGINE,
                ENGINE_VERSION,
                MODEL_MANIFEST_SHA256,
                PREPROCESSING_IDENTITY,
                POSTPROCESSING_IDENTITY,
                COORDINATE_SPACE_IDENTITY,
                BOX_SEMANTICS_IDENTITY,
                PROJECTION_IDENTITY,
                READING_ORDER_IDENTITY,
                CANONICALIZATION_IDENTITY,
                CONFIDENCE_SCALE_IDENTITY,
                CONFIDENCE_BUCKET_IDENTITY,
                AcquisitionPolicy.TextExposure.EPHEMERAL_STAGE_CONTEXT_ONLY,
                DocumentVisionObservation.MAX_ARTIFACTS,
                DocumentVisionObservation.MAX_LINES,
                DocumentVisionObservation.MAX_LINE_TEXT_BYTES,
                DocumentVisionObservation.MAX_TOTAL_TEXT_BYTES,
                MAXIMUM_RESPONSE_BYTES,
                timeoutMillis);
    }
}
