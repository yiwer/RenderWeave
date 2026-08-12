package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredR1EvaluationGateTest {
    private static final Set<String> PRODUCT_V45 = Set.of(
            "dashscope-qwen37-flash-product-v45-hybrid-generic",
            "dashscope-qwen37-plus-product-v45-hybrid-generic",
            "dashscope-qwen38-max-product-v45-hybrid-generic"
    );

    @Test
    void completeReplayIsDeterministicPayloadSafeAndKeepsProductV45Experimental() {
        var first = new LayeredR1Evaluation().evaluate();
        var second = new LayeredR1Evaluation().evaluate();
        var report = first.report();
        var identity = LayeredEvaluationIdentity.fromComponents(
                report.evaluationComponents(), report.evaluationIdentity());

        assertArrayEquals(first.encodedReport(), second.encodedReport());
        assertEquals(first.reportIdentity(), second.reportIdentity());
        assertEquals("renderweave-layered-evaluation/1.0:"
                        + "1d775f298377b8e3e45eec6a61d32bc9364fc27e6db5894072b6d9cfb31a0f17",
                report.evaluationIdentity());
        assertEquals("renderweave-layered-evaluation-report/1.0:"
                        + "ca647ba6c56aca35d6262c1984dfb6a5b8f9024840566e97033df4c9dcff6a2a",
                first.reportIdentity());
        assertEquals(60, report.expectedCaseCount());
        assertEquals(60, report.observedCaseCount());
        assertTrue(report.complete());
        assertEquals(Set.of("DEV", "HOLDOUT"), report.partitions().keySet());
        assertEquals(Set.of("generic", "transit-board"), report.domains().keySet());
        assertEquals(45, report.partitions().get("DEV").caseCount());
        assertEquals(15, report.partitions().get("HOLDOUT").caseCount());
        assertEquals(55, report.domains().get("generic").caseCount());
        assertEquals(5, report.domains().get("transit-board").caseCount());
        assertEquals(LayeredVisualAnnotation.RegionKind.values().length,
                report.global().layout().byKind().size());
        assertTrue(report.global().metricsBps().size() >= 40);

        assertEquals(new LayeredVisualCorpus().corpusIdentity(),
                identity.components().get("inputSetIdentity"));
        assertEquals(LayeredVisualAnnotation.VERSION,
                identity.components().get("annotationVersion"));
        assertEquals(RapidOcrBaselineContract.ADAPTER_IDENTITY,
                identity.components().get("adapterIdentity"));
        assertEquals("weight-sha256:" + RapidOcrBaselineContract.MODEL_MANIFEST_SHA256,
                identity.components().get("weightIdentity"));
        assertEquals("v45-source-to-candidate/1.0", identity.components().get("projectionIdentity"));
        assertEquals("top-left-canonical/1.0", identity.components().get("orderIdentity"));
        assertTrue(identity.components().get("shapeCatalogIdentity")
                .matches("renderweave-stage-response-shape-catalog/1\\.0:[0-9a-f]{64}"));
        assertTrue(identity.components().get("providerProfileReplayIdentity")
                .matches("renderweave-product-v45-layered-replay/1\\.0:[0-9a-f]{64}"));
        assertTrue(identity.components().get("promptIdentity")
                .matches("renderweave-product-v45-prompt-set/1\\.0:[0-9a-f]{64}"));
        assertTrue(identity.components().get("validatorIdentity")
                .matches("renderweave-product-v45-validator-set/1\\.0:[0-9a-f]{64}"));
        assertTrue(identity.components().get("materializerIdentity")
                .matches("renderweave-product-v45-materializer/1\\.0:[0-9a-f]{64}"));
        assertTrue(identity.components().get("evaluatorIdentity")
                .matches("renderweave-layered-evaluator/1\\.0:[0-9a-f]{64}"));
        assertTrue(identity.components().get("budgetIdentity")
                .matches("renderweave-zero-provider-budget/1\\.0:[0-9a-f]{64}"));
        assertTrue(identity.components().get("decodingModeIdentity")
                .matches("renderweave-layered-decoding-mode/1\\.0:[0-9a-f]{64}"));

        var runtime = report.global().runtime();
        assertEquals(0, runtime.inputTokens());
        assertEquals(0, runtime.outputTokens());
        assertEquals(0, runtime.estimatedCostMicrosCny());
        assertEquals(0, runtime.settledCostMicrosCny());
        assertEquals(0, runtime.providerAttempts());
        assertEquals(0, runtime.providerReservations());
        assertEquals(0, runtime.externalProviderCostMicrosCny());
        assertEquals(180, runtime.scriptedCalls());

        var profiles = new InferenceProfileRegistry().productLiveProfiles();
        assertEquals(PRODUCT_V45, profiles.stream().map(item -> item.profile().profileId())
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(profiles.stream().allMatch(item -> "EXPERIMENTAL".equals(item.profile().certification())));
        assertTrue(profiles.stream().allMatch(item ->
                "renderweave-inference-pipeline/4.28".equals(item.profile().pipelineVersion())));

        var payload = new String(first.encodedReport(), StandardCharsets.UTF_8).toLowerCase();
        for (var forbidden : new String[]{
                "ignore prior instructions", "runtime_ocr_sentinel", "ocrtext", "ocr_text",
                "rootdocument", "root_document", "providerrequest", "providerresponse",
                "candidatepayload", "boundingbox", "\"bbox\"", "\"polygon\"", "data:image", "base64"
        }) {
            assertFalse(payload.contains(forbidden), forbidden);
        }
        assertFalse(first.toString().contains("records="));

        var corrupted = first.encodedReport();
        corrupted[corrupted.length / 2] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> new LayeredEvaluationReportJsonCodec().read(corrupted, first.reportIdentity()));
    }

    @Test
    void explicitGateModeWritesOnlyTheCanonicalReportInsideSdlcEvidence() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("RENDERWEAVE_RUN_LAYERED_R1_EVALUATION")));
        var requested = System.getenv("RENDERWEAVE_LAYERED_R1_REPORT");
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("LAYERED_R1_REPORT_PATH_REQUIRED");
        }
        var repository = repositoryRoot();
        var evidenceRootPath = repository.resolve(".sdlc/evidence").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(evidenceRootPath)
                || !Files.isDirectory(evidenceRootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("LAYERED_R1_EVIDENCE_ROOT_INVALID");
        }
        var evidenceRoot = evidenceRootPath.toRealPath();
        var output = Path.of(requested).toAbsolutePath().normalize();
        if (!output.startsWith(evidenceRootPath) || output.equals(evidenceRootPath)
                || !"layered-report.json".equals(output.getFileName().toString())) {
            throw new IllegalArgumentException("LAYERED_R1_REPORT_PATH_INVALID");
        }
        var parentPath = output.getParent();
        if (Files.isSymbolicLink(parentPath)
                || !Files.isDirectory(parentPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("LAYERED_R1_REPORT_PATH_INVALID");
        }
        var parent = parentPath.toRealPath();
        if (!parent.startsWith(evidenceRoot)) {
            throw new IllegalArgumentException("LAYERED_R1_REPORT_PATH_INVALID");
        }

        var evaluation = new LayeredR1Evaluation().evaluate();
        Files.write(output, evaluation.encodedReport(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        assertEquals(evaluation.reportIdentity(), new LayeredEvaluationReportJsonCodec()
                .reportIdentity(new LayeredEvaluationReportJsonCodec().read(
                        Files.readAllBytes(output), evaluation.reportIdentity())));
    }

    private static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("renderweave-inference"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("REPOSITORY_ROOT_NOT_FOUND");
    }
}
