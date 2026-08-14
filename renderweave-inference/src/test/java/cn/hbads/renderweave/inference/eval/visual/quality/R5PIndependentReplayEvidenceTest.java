package cn.hbads.renderweave.inference.eval.visual.quality;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class R5PIndependentReplayEvidenceTest {
    @Test
    void canonicalRoundTripKeepsTheOnlyQualityFailureTerminal() {
        var codec = new R5PIndependentReplayEvidence.Codec();
        var evidence = evidence(false);

        var encoded = codec.write(evidence);
        var decoded = codec.read(encoded, codec.identity(evidence));

        assertEquals(evidence, decoded);
        assertEquals("R5P_PAIRED_VIEW_NOT_QUALIFIED", decoded.terminalCode());
    }

    @Test
    void measurementFailureCannotBePresentedAsAQualityDecision() {
        assertThrows(IllegalArgumentException.class, () -> new R5PIndependentReplayEvidence(
                R5PIndependentReplayEvidence.VERSION,
                R5PIndependentReplayEvidence.ASSURANCE,
                R5PIndependentReplayEvidence.AUTHORITY_IDENTITY,
                assignment().identity(), assignment().evaluationIdentity(),
                R5PIndependentReplayEvidence.INDEPENDENT_EVALUATOR_IDENTITY,
                R5PIndependentReplayEvidence.CAPABILITY_IDENTITY,
                2, 8, 32, 32, 16, 16,
                determinism(), decisions(false), summary(false), summary(false),
                false, false, zeroProvider(), 0, 0, 0, payloadBoundary(),
                "R5P_PAIRED_VIEW_NOT_QUALIFIED"));
    }

    @Test
    void strictReaderRejectsUnknownDuplicateTrailingCoercionOverflowAndPayloadMarkers() {
        var codec = new R5PIndependentReplayEvidence.Codec();
        var valid = new String(codec.write(evidence(false)), StandardCharsets.UTF_8);
        var identity = codec.identity(evidence(false));
        var mutations = List.of(
                valid.replaceFirst("\\{", "{\"unknown\":0,"),
                valid.replaceFirst("\"apiKeyReads\":0", "\"apiKeyReads\":0,\"apiKeyReads\":0"),
                valid + "{}",
                valid.replaceFirst("\"apiKeyReads\":0", "\"apiKeyReads\":false"),
                valid.replaceFirst("\"apiKeyReads\":0", "\"apiKeyReads\":0.0"),
                valid.replaceFirst("\"apiKeyReads\":0", "\"apiKeyReads\":\"0\""),
                valid.replaceFirst("\"apiKeyReads\":0", "\"apiKeyReads\":2147483648"),
                valid.replaceFirst("\"caseId\":\"transit-board-v3\"",
                        "\"caseId\":\"data:image/png;base64,AAAA\"")
        );
        for (var mutation : mutations) {
            assertThrows(IllegalArgumentException.class,
                    () -> codec.read(mutation.getBytes(StandardCharsets.UTF_8), identity));
        }
    }

    private static R5PIndependentReplayEvidence evidence(boolean qualityPass) {
        return new R5PIndependentReplayEvidence(
                R5PIndependentReplayEvidence.VERSION,
                R5PIndependentReplayEvidence.ASSURANCE,
                R5PIndependentReplayEvidence.AUTHORITY_IDENTITY,
                assignment().identity(), assignment().evaluationIdentity(),
                R5PIndependentReplayEvidence.INDEPENDENT_EVALUATOR_IDENTITY,
                R5PIndependentReplayEvidence.CAPABILITY_IDENTITY,
                2, 8, 32, 32, 16, 16,
                determinism(), decisions(qualityPass), summary(qualityPass), summary(qualityPass),
                true, qualityPass, zeroProvider(), 0, 0, 0, payloadBoundary(),
                qualityPass ? "R5P_ACTION_IMPLEMENTATION_ALLOWED"
                        : "R5P_PAIRED_VIEW_NOT_QUALIFIED");
    }

    private static List<R5PIndependentReplayEvidence.CaseDecision> decisions(boolean qualityPass) {
        var assignment = assignment();
        var result = new ArrayList<R5PIndependentReplayEvidence.CaseDecision>();
        for (var index = 0; index < assignment.cases().size(); index++) {
            var item = assignment.cases().get(index);
            var improved = qualityPass || index != 0 && index != 4;
            result.add(new R5PIndependentReplayEvidence.CaseDecision(
                    item.caseId(), item.caseIdentity(), item.cohort().name(),
                    id("normalization", item.caseId()),
                    id("baseline", item.caseId()), id("successor", item.caseId()),
                    1, 3, improved ? 1 : 0, 500, improved ? 1 : 0, 0, 0, 0,
                    improved, true, true));
        }
        return List.copyOf(result);
    }

    private static R5PIndependentReplayEvidence.CohortSummary summary(boolean pass) {
        return new R5PIndependentReplayEvidence.CohortSummary(
                4, pass ? 4 : 3, 4, 10, 14, 5_000, 7_000, 2_000,
                40, pass ? 36 : 37, pass ? 4 : 3, 0, 0, 10_000, 10_000, 0,
                10_000, 10_000, 0, pass);
    }

    private static R5PIndependentReplayEvidence.Determinism determinism() {
        return new R5PIndependentReplayEvidence.Determinism(
                8, 8, 16, 16, true, "R5P_A2_TWO_RUN_DETERMINISTIC");
    }

    private static R5PIndependentReplayEvidence.ExternalProviderUsage zeroProvider() {
        return new R5PIndependentReplayEvidence.ExternalProviderUsage(0, 0, 0);
    }

    private static R5PIndependentReplayEvidence.PayloadBoundary payloadBoundary() {
        return new R5PIndependentReplayEvidence.PayloadBoundary(
                false, false, false, false, false, false);
    }

    private static R5PairedProductViewAssignment assignment() {
        return R5PairedProductViewAssignment.load();
    }

    private static String id(String kind, String caseId) {
        return "renderweave-r5p-a2-" + kind + "/1.0:"
                + "0".repeat(63) + Integer.toHexString(Math.floorMod(caseId.hashCode(), 16));
    }
}
