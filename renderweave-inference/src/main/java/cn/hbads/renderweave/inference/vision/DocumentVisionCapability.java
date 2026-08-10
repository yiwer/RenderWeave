package cn.hbads.renderweave.inference.vision;

import java.util.Objects;

/** Startup-probed local adapter identity. A Profile may use it only by exact capability id. */
public record DocumentVisionCapability(
        String capabilityVersion,
        String capabilityId,
        boolean available,
        String engine,
        String engineVersion,
        String modelManifestSha256,
        String diagnosticCode
) {
    public static final String VERSION = "renderweave-document-vision-capability/1.0";
    public static final String UNAVAILABLE_ID = "document-vision-unavailable";

    public DocumentVisionCapability {
        if (!VERSION.equals(capabilityVersion)) {
            throw new IllegalArgumentException("Document vision capability version is unsupported");
        }
        if (capabilityId == null || !capabilityId.matches("[a-z0-9][a-z0-9._:-]{0,190}")) {
            throw new IllegalArgumentException("Document vision capability id is invalid");
        }
        diagnosticCode = requireCode(diagnosticCode);
        if (available) {
            if (engine == null || engine.isBlank() || engineVersion == null || engineVersion.isBlank()
                    || modelManifestSha256 == null || !modelManifestSha256.matches("[0-9a-f]{64}")
                    || !"DOCUMENT_VISION_AVAILABLE".equals(diagnosticCode)) {
                throw new IllegalArgumentException("Available document vision capability is incomplete");
            }
        } else {
            if (!UNAVAILABLE_ID.equals(capabilityId) || "DOCUMENT_VISION_AVAILABLE".equals(diagnosticCode)) {
                throw new IllegalArgumentException("Unavailable document vision capability is inconsistent");
            }
            engine = null;
            engineVersion = null;
            modelManifestSha256 = null;
        }
    }

    public static DocumentVisionCapability available(
            String capabilityId,
            String engine,
            String engineVersion,
            String modelManifestSha256
    ) {
        return new DocumentVisionCapability(
                VERSION, capabilityId, true, Objects.requireNonNull(engine, "engine"),
                Objects.requireNonNull(engineVersion, "engineVersion"),
                Objects.requireNonNull(modelManifestSha256, "modelManifestSha256"),
                "DOCUMENT_VISION_AVAILABLE"
        );
    }

    public static DocumentVisionCapability unavailable(String diagnosticCode) {
        return new DocumentVisionCapability(
                VERSION, UNAVAILABLE_ID, false, null, null, null, requireCode(diagnosticCode)
        );
    }

    private static String requireCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("Document vision diagnostic code is invalid");
        }
        return value;
    }
}
