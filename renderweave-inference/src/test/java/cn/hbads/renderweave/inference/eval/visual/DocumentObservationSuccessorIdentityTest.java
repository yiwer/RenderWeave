package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.DocumentObservationCompatibilityProjection;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentObservationSuccessorIdentityTest {
    private static final String CAPABILITY =
            "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1";

    @Test
    void identityBindsTheExactR0ContractPolicyProjectionAndShapeCatalog() {
        var identity = new DocumentObservationSuccessorIdentity(policy("a".repeat(64), 30_000));
        var replay = new DocumentObservationSuccessorIdentity(policy("a".repeat(64), 30_000));

        assertEquals(identity.identity(), replay.identity());
        assertEquals(
                "renderweave-document-observation-successor/1.0:"
                        + "302917d557bf7df9326b9a7d4af840c190be471041712806c19f932e24e1a3a2",
                identity.identity()
        );
        assertTrue(identity.identity().matches(
                "renderweave-document-observation-successor/1\\.0:[0-9a-f]{64}"
        ));
        assertEquals(DocumentObservationIR.VERSION, identity.observationContractVersion());
        assertEquals(DocumentObservationCompatibilityProjection.VERSION, identity.projectionIdentity());
        assertEquals(CAPABILITY, identity.capabilityIdentity());
        assertEquals(new StageResponseShapeCatalog().identity(), identity.shapeCatalogIdentity());

        assertNotEquals(identity.identity(),
                new DocumentObservationSuccessorIdentity(policy("b".repeat(64), 30_000)).identity());
        assertNotEquals(identity.identity(),
                new DocumentObservationSuccessorIdentity(policy("a".repeat(64), 30_001)).identity());
        assertFalse(identity.toString().contains("OCR_SENTINEL"));
    }

    private static AcquisitionPolicy policy(String modelManifest, int timeoutMillis) {
        return new AcquisitionPolicy(
                AcquisitionPolicy.VERSION, DocumentObservationIR.VERSION, CAPABILITY,
                "rapidocr-local-process/1.0", "rapidocr-openvino-ppocrv6-small",
                "rapidocr-3.9.2+openvino-2026.0.0", modelManifest,
                "explicit-bgr/1.0", "rapidocr-lines/1.0", "source-pixel-top-left/1.0",
                "half-open-box/1.0", DocumentObservationCompatibilityProjection.VERSION,
                "top-left-canonical/1.0", "unicode-nfc-whitespace-collapse/1.0",
                "basis-points/1.0", "v45-confidence-buckets/1.0",
                AcquisitionPolicy.TextExposure.EPHEMERAL_STAGE_CONTEXT_ONLY,
                10, 512, 256, 32 * 1024, 512 * 1024, timeoutMillis
        );
    }
}
