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

    @Test
    void unicodeEscapesCannotHideForbiddenDecodedSummaryMembers() {
        var original = new String(summary(REVISION, 0), StandardCharsets.UTF_8);
        var escapedForbiddenMember = "\"" + "\\" + "u006dodelOutput\":\"secret\"";
        var injected = original.replace("}\n", "," + escapedForbiddenMember + "}\n")
                .getBytes(StandardCharsets.UTF_8);

        assertEquals("QUALITY_REPAIR_COMPONENT_PAYLOAD_FORBIDDEN",
                assertThrows(IllegalArgumentException.class, () -> reader.read(
                        COMPONENT, evidence(), IDENTITY, injected, REVISION)).getMessage());
    }

    private static byte[] evidence() {
        return ("{\"envelopeVersion\":\"" + COMPONENT.evidenceEnvelopeVersion()
                + "\",\"evidenceIdentity\":\"" + IDENTITY + "\",\"evidence\":{}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] summary(String revision, long attempts) {
        return ("{\"actualAcquisitions\":120,\"assurance\":\"A2_CROSS_IMPLEMENTATION_RECOMPUTE\""
                + ",\"attributionResults\":{\"LAYOUT\":\"OBSERVED_CONTRIBUTOR\""
                + ",\"MATERIALIZER\":\"MISSING\",\"OBSERVATION\":\"OBSERVED_CONTRIBUTOR\""
                + ",\"ORDER_REPEAT\":\"MISSING\",\"SCORER\":\"EXCLUDED_BY_CURRENT_EVIDENCE\""
                + ",\"SEMANTIC\":\"OBSERVED_CONTRIBUTOR\",\"SHAPE_CODEC\":\"EXCLUDED_BY_CURRENT_EVIDENCE\""
                + ",\"STATIC_VIEW\":\"MISSING\"},\"caseCount\":60"
                + ",\"evaluationIdentity\":\"renderweave-rapidocr-shadow-evaluation/1.0:"
                + "e".repeat(64) + "\",\"evidenceIdentity\":\"" + IDENTITY
                + "\",\"externalProviderCostMicrosCny\":0,\"metricsEquivalentCases\":60"
                + ",\"observationEquivalentCases\":60,\"protocolIdentity\":\""
                + "renderweave-offline-quality-evaluation-protocol/1.0:" + "f".repeat(64)
                + "\",\"providerAttempts\":" + attempts + ",\"providerReservations\":0"
                + ",\"repositoryRevision\":\"" + revision + "\",\"result\":\"PASS\""
                + ",\"runCount\":2,\"verifierVersion\":\"" + COMPONENT.verifierVersion() + "\"}\n")
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
