package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.live.R5P2PairedProductViewEvaluation;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit opt-in, offline-only producer for the frozen R5P2 paired A1 report. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_R5P2_PAIRED_PRODUCER", matches = "true")
class R5P2PairedProductViewEvaluationGateTest {
    @Test
    void runsFortyEightFreshBranchProcessesAndWritesPayloadSafeEvidence() throws Exception {
        var output = requiredOutput();
        var branchProcesses = new AtomicInteger();
        var artifactViews = new AtomicInteger();
        var evaluation = new R5P2PairedProductViewEvaluation();
        var result = evaluation.evaluate(runOrdinal -> {
            var configured = LocalProcessDocumentVisionPreprocessor.fromConfiguration(
                    true,
                    required("RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE"),
                    required("RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT"),
                    required("RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT"),
                    30,
                    LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID);
            assertTrue(configured.capability().available(),
                    configured.capability().diagnosticCode());
            var adapter = (LocalProcessDocumentVisionPreprocessor) configured;
            var policy = adapter.acquisitionPolicy();
            assertEquals(RapidOcrBaselineContract.policy(
                    RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS), policy);
            return R5P2PairedProductViewEvaluation.RunSession.of(policy,
                    (artifacts, requestedPolicy) -> {
                        branchProcesses.incrementAndGet();
                        artifactViews.addAndGet(artifacts.artifacts().size());
                        return adapter.acquire(artifacts, requestedPolicy);
                    });
        });

        assertEquals(48, branchProcesses.get());
        assertEquals(48, result.report().accounting().branchAcquisitionProcesses());
        assertEquals(2, result.report().accounting().capabilityProbeProcesses());
        assertEquals(artifactViews.get(), result.report().accounting().artifactViews());
        assertEquals(12, result.report().determinism().equivalentCases());
        assertEquals(24, result.report().determinism().equivalentBranches());
        assertEquals(0, result.report().externalProviderUsage().attempts());
        assertEquals(0, result.report().apiKeyReads());
        assertFalse(result.report().finalTerminalClaimed());
        assertEquals("R5P2_PAIRED_PRODUCER_COMPLETE", result.report().terminalCode());

        Files.write(output, result.encodedReport(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        assertEquals(result.report(), evaluation.readReport(
                Files.readAllBytes(output), result.reportIdentity()));
    }

    private static Path requiredOutput() throws Exception {
        var requested = required("RENDERWEAVE_R5P2_PAIRED_REPORT");
        var evidenceRootPath = repositoryRoot().resolve(".sdlc/evidence")
                .toAbsolutePath().normalize();
        if (Files.isSymbolicLink(evidenceRootPath)
                || !Files.isDirectory(evidenceRootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5P2_PAIRED_EVIDENCE_ROOT_INVALID");
        }
        var evidenceRoot = evidenceRootPath.toRealPath();
        var output = Path.of(requested).toAbsolutePath().normalize();
        if (!output.startsWith(evidenceRootPath) || output.equals(evidenceRootPath)
                || !"r5p2-paired-product-view-report.json".equals(
                        output.getFileName().toString())) {
            throw new IllegalArgumentException("R5P2_PAIRED_REPORT_PATH_INVALID");
        }
        var parent = output.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.toRealPath().startsWith(evidenceRoot)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5P2_PAIRED_REPORT_PATH_INVALID");
        }
        return output;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for R5P2 paired producer");
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
