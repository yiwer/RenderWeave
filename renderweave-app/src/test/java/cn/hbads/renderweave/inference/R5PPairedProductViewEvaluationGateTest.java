package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.live.PairedProductViewEvaluation;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit opt-in local-only producer for the complete R5P paired A1 report. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_R5P_PAIRED_EVALUATION", matches = "true")
class R5PPairedProductViewEvaluationGateTest {
    @Test
    void runsTwoIsolatedCompletePairsAndWritesCanonicalPayloadSafeEvidence() throws Exception {
        var output = requiredOutput();
        var evaluation = new PairedProductViewEvaluation();
        var result = evaluation.evaluate(runOrdinal -> {
            var configured = LocalProcessDocumentVisionPreprocessor.fromConfiguration(
                    true,
                    required("RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE"),
                    required("RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT"),
                    required("RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT"),
                    30,
                    LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID);
            assertTrue(configured.capability().available(), configured.capability().diagnosticCode());
            var adapter = (LocalProcessDocumentVisionPreprocessor) configured;
            assertEquals(RapidOcrBaselineContract.policy(
                    RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS),
                    adapter.acquisitionPolicy());
            return PairedProductViewEvaluation.RunSession.of(
                    adapter.acquisitionPolicy(), adapter);
        });
        assertEquals(2, result.report().runs().size());
        assertEquals(32, result.report().actualAcquisitionCalls());
        assertTrue(result.report().determinism().deterministic());
        assertEquals(0, result.report().externalProviderUsage().attempts());
        assertEquals(0, result.report().apiKeyReads());
        assertEquals("R5P_PAIRED_EXECUTION_COMPLETE", result.report().terminalCode());
        Files.write(output, result.encodedReport(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        assertEquals(result.report(), evaluation.readReport(
                Files.readAllBytes(output), result.reportIdentity()));
    }

    private static Path requiredOutput() throws Exception {
        var requested = required("RENDERWEAVE_R5P_PAIRED_REPORT");
        var evidenceRootPath = repositoryRoot().resolve(".sdlc/evidence")
                .toAbsolutePath().normalize();
        if (Files.isSymbolicLink(evidenceRootPath)
                || !Files.isDirectory(evidenceRootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5P_PAIRED_EVIDENCE_ROOT_INVALID");
        }
        var evidenceRoot = evidenceRootPath.toRealPath();
        var output = Path.of(requested).toAbsolutePath().normalize();
        if (!output.startsWith(evidenceRootPath) || output.equals(evidenceRootPath)
                || !"r5p-paired-product-view-report.json"
                .equals(output.getFileName().toString())) {
            throw new IllegalArgumentException("R5P_PAIRED_REPORT_PATH_INVALID");
        }
        var parent = output.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.toRealPath().startsWith(evidenceRoot)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5P_PAIRED_REPORT_PATH_INVALID");
        }
        return output;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for R5P paired evaluation");
        }
        return value;
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
