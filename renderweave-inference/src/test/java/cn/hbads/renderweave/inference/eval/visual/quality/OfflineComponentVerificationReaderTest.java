package cn.hbads.renderweave.inference.eval.visual.quality;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfflineComponentVerificationReaderTest {
    private static final FrozenQualityEvidencePack.Component COMPONENT =
            FrozenQualityEvidencePack.Component.RAPIDOCR_CAUSAL;
    private static final String IDENTITY = COMPONENT.evidenceIdentityVersion() + ":" + "a".repeat(64);
    private static final String REVISION = "b".repeat(40);
    private final OfflineComponentVerificationReader reader = new OfflineComponentVerificationReader();

    @Test
    void bindsPassingA2SummaryAndExactRepositoryRevisionToEvidenceBytes() {
        var evidence = evidence();
        var summary = summary(REVISION, 0);

        var result = reader.read(COMPONENT, evidence, IDENTITY, summary, REVISION);

        assertEquals(FrozenQualityEvidencePack.VerificationResult.PASS, result.result());
        assertEquals(IDENTITY, result.evidenceIdentity());
        assertEquals(sha256(evidence), result.evidenceSha256());
        assertEquals(sha256(summary), result.verificationSummarySha256());
        assertEquals(REVISION, result.repositoryRevision());
        assertEquals(0, result.externalProviderUsage().attempts());
    }

    @Test
    void revisionDriftAndProviderUseFailClosed() {
        assertEquals("QUALITY_REPAIR_COMPONENT_SUMMARY_AUTHORITY_INVALID",
                assertThrows(IllegalArgumentException.class, () -> reader.read(
                        COMPONENT, evidence(), IDENTITY, summary("c".repeat(40), 0), REVISION)).getMessage());
        assertEquals("QUALITY_REPAIR_COMPONENT_PROVIDER_USAGE_NONZERO",
                assertThrows(IllegalArgumentException.class, () -> reader.read(
                        COMPONENT, evidence(), IDENTITY, summary(REVISION, 1), REVISION)).getMessage());
    }

    private static byte[] evidence() {
        return ("{\"envelopeVersion\":\"" + COMPONENT.evidenceEnvelopeVersion()
                + "\",\"evidenceIdentity\":\"" + IDENTITY + "\",\"evidence\":{}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] summary(String revision, long attempts) {
        return ("{\"verifierVersion\":\"" + COMPONENT.verifierVersion()
                + "\",\"result\":\"PASS\",\"assurance\":\"A2_CROSS_IMPLEMENTATION_RECOMPUTE\""
                + ",\"evidenceIdentity\":\"" + IDENTITY + "\",\"repositoryRevision\":\"" + revision
                + "\",\"providerAttempts\":" + attempts
                + ",\"providerReservations\":0,\"externalProviderCostMicrosCny\":0}\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
