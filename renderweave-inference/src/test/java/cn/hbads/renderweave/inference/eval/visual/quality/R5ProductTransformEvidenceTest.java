package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R5ProductTransformEvidenceTest {
    @Test
    void frozenAssignmentIsExactlyThreeDevOneHoldoutAndTwoPredeclaredRegions() {
        var assignment = R5ProductTransformAssignment.load();

        assertEquals(List.of(
                        "transit-board-v3", "restaurant-menu-v3", "hospital-schedule-v3", "transit-board-v5"),
                assignment.cases().stream().map(R5ProductTransformAssignment.CaseAssignment::caseId).toList());
        assertEquals(3, assignment.cases().stream().filter(item -> item.partition().name().equals("DEV")).count());
        assertEquals(1, assignment.cases().stream().filter(item -> item.partition().name().equals("HOLDOUT")).count());
        assertTrue(assignment.cases().stream().allMatch(item -> item.regions().size() == 2));
        assertTrue(assignment.identity().matches(
                "renderweave-r5-product-transform-assignment/1\\.0:[0-9a-f]{64}"));
    }

    @Test
    void exactThresholdsQualifyAndCanonicalEvidenceIsPayloadSafe() {
        var first = new R5ProductTransformEvidence.RunRecord(1, improvedCases(1_000));
        var second = new R5ProductTransformEvidence.RunRecord(2, improvedCases(2_000));

        var evidence = R5ProductTransformEvidence.decide(identity("evaluation"), List.of(first, second), 4);

        assertEquals(R5ProductTransformEvidence.Disposition.QUALIFIED, evidence.disposition());
        assertTrue(evidence.qualified());
        assertEquals(2_500, evidence.aggregateLineRecallGainBps());
        assertEquals(400, evidence.aggregateStaticCharacterErrors());
        assertEquals(200, evidence.aggregateInspectedCharacterErrors());
        assertTrue(evidence.predicates().values().stream()
                .allMatch(value -> value == R5ProductTransformEvidence.PredicateResult.PASS));

        var codec = new R5ProductTransformEvidenceJsonCodec();
        var bytes = codec.write(evidence);
        assertArrayEquals(bytes, codec.write(codec.read(bytes, codec.evidenceIdentity(evidence))));
        var payload = new String(bytes, StandardCharsets.UTF_8).toLowerCase();
        for (var forbidden : new String[]{
                "base64", "data:image", "ocrtext", "ocr_text", "prompttext", "modeloutput",
                "providerrequest", "providerresponse", "candidatejson", "rootdocument", "boundingbox", "\"bbox\""
        }) {
            assertFalse(payload.contains(forbidden), forbidden);
        }
    }

    @Test
    void aSingleNonImprovingCaseForcesTheFixedStopDisposition() {
        var first = new ArrayList<>(improvedCases(1_000));
        var item = first.getFirst();
        first.set(0, copy(item, item.staticView(), item.staticView()));
        var second = new ArrayList<>(first);

        var evidence = R5ProductTransformEvidence.decide(identity("not-qualified"), List.of(
                new R5ProductTransformEvidence.RunRecord(1, first),
                new R5ProductTransformEvidence.RunRecord(2, second)), 4);

        assertFalse(evidence.qualified());
        assertEquals(R5ProductTransformEvidence.Disposition.NOT_QUALIFIED, evidence.disposition());
        assertEquals("R5_PRODUCT_TRANSFORM_NOT_QUALIFIED", evidence.reasonCode());
    }

    @Test
    void strictCodecRejectsUnknownMembersAndBooleanIntegerConfusion() {
        var evidence = R5ProductTransformEvidence.decide(identity("codec"), List.of(
                new R5ProductTransformEvidence.RunRecord(1, improvedCases(1_000)),
                new R5ProductTransformEvidence.RunRecord(2, improvedCases(2_000))), 4);
        var codec = new R5ProductTransformEvidenceJsonCodec();
        var encoded = new String(codec.write(evidence), StandardCharsets.UTF_8);

        var unknown = encoded.replaceFirst("\\{\"acquisitionPolicyIdentity\"",
                "{\"unexpectedEvidence\":0,\"acquisitionPolicyIdentity\"");
        assertThrows(IllegalArgumentException.class,
                () -> codec.read(unknown.getBytes(StandardCharsets.UTF_8)));
        var confused = encoded.replaceFirst("\"actualAcquisitions\":16", "\"actualAcquisitions\":true");
        assertThrows(IllegalArgumentException.class,
                () -> codec.read(confused.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<R5ProductTransformEvidence.CaseRecord> improvedCases(long micros) {
        var corpus = new LayeredVisualCorpus();
        var assignment = R5ProductTransformAssignment.load();
        return assignment.cases().stream().map(item -> {
            var evaluationCase = corpus.require(item.caseId());
            var staticResource = new R5ProductTransformEvidence.ViewResource(
                    identity("static-view-" + item.caseId()), hex("static-artifact-" + item.caseId()),
                    768, 576, 10_000);
            var inspected = List.of(
                    new R5ProductTransformEvidence.ViewResource(
                            identity("inspected-a-" + item.caseId()), hex("inspected-a-" + item.caseId()),
                            2_400, 600, 20_000),
                    new R5ProductTransformEvidence.ViewResource(
                            identity("inspected-b-" + item.caseId()), hex("inspected-b-" + item.caseId()),
                            2_400, 1_200, 30_000));
            return new R5ProductTransformEvidence.CaseRecord(
                    item.caseId(), evaluationCase.caseIdentity(), item.partition(),
                    evaluationCase.renderCase().width(), evaluationCase.renderCase().height(),
                    identity("static-plan-" + item.caseId()), identity("request-" + item.caseId()),
                    identity("inspected-plan-" + item.caseId()), 1, 2,
                    staticResource.decodedPixels(), inspected.stream().mapToLong(
                            R5ProductTransformEvidence.ViewResource::decodedPixels).sum(),
                    staticResource.encodedBytes(), inspected.stream().mapToLong(
                            R5ProductTransformEvidence.ViewResource::encodedBytes).sum(),
                    micros, micros + 1, staticResource, inspected,
                    metrics(10, 100), metrics(15, 50));
        }).toList();
    }

    private static R5ProductTransformEvidence.CaseRecord copy(
            R5ProductTransformEvidence.CaseRecord source,
            R5ProductTransformEvidence.CaseMetrics staticMetrics,
            R5ProductTransformEvidence.CaseMetrics inspectedMetrics
    ) {
        return new R5ProductTransformEvidence.CaseRecord(
                source.caseId(), source.caseIdentity(), source.partition(), source.sourceWidth(), source.sourceHeight(),
                source.staticPlanIdentity(), source.requestIdentity(), source.inspectedPlanIdentity(),
                source.staticViewCount(), source.inspectedViewCount(), source.staticDecodedPixels(),
                source.inspectedDecodedPixels(), source.staticEncodedBytes(), source.inspectedEncodedBytes(),
                source.staticAcquisitionMicros(), source.inspectedAcquisitionMicros(), source.staticResource(),
                source.inspectedResources(), staticMetrics, inspectedMetrics);
    }

    private static R5ProductTransformEvidence.CaseMetrics metrics(long matched, long errors) {
        return new R5ProductTransformEvidence.CaseMetrics(
                matched, 20, matched, matched * 5, errors, 1, 10, 8, 7, 12, 10);
    }

    private static String identity(String seed) {
        return "renderweave-test/1.0:" + hex(seed);
    }

    private static String hex(String seed) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
