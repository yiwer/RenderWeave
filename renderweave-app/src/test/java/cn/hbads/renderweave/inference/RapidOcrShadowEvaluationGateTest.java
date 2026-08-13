package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluation;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowReportJsonCodec;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit opt-in actual RapidOCR corpus-v2 double-run gate; it never constructs a Provider. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_RAPIDOCR_SHADOW_EVALUATION", matches = "true")
class RapidOcrShadowEvaluationGateTest {
    @Test
    void runsTheFrozenLocalAdapterTwiceAndWritesOnlyTheCanonicalPayloadSafeReport() throws Exception {
        var output = requiredOutput();
        var evaluation = new RapidOcrShadowEvaluation().evaluate(runOrdinal -> {
            var configured = LocalProcessDocumentVisionPreprocessor.fromConfiguration(
                    true,
                    required("RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE"),
                    required("RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT"),
                    required("RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT"),
                    30,
                    LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID
            );
            assertTrue(configured.capability().available(), configured.capability().diagnosticCode());
            var adapter = (LocalProcessDocumentVisionPreprocessor) configured;
            assertEquals(RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS),
                    adapter.acquisitionPolicy());
            return RapidOcrShadowEvaluation.RunSession.of(adapter.acquisitionPolicy(), adapter);
        });
        Files.write(output, evaluation.encodedReport(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        var codec = new RapidOcrShadowReportJsonCodec();
        assertEquals(evaluation.reportIdentity(), codec.reportIdentity(
                codec.read(Files.readAllBytes(output), evaluation.reportIdentity())));
    }

    private static Path requiredOutput() throws Exception {
        var requested = required("RENDERWEAVE_RAPIDOCR_SHADOW_REPORT");
        var repository = repositoryRoot();
        var evidenceRootPath = repository.resolve(".sdlc/evidence").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(evidenceRootPath)
                || !Files.isDirectory(evidenceRootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("RAPIDOCR_SHADOW_EVIDENCE_ROOT_INVALID");
        }
        var evidenceRoot = evidenceRootPath.toRealPath();
        var output = Path.of(requested).toAbsolutePath().normalize();
        if (!output.startsWith(evidenceRootPath) || output.equals(evidenceRootPath)
                || !"rapidocr-shadow-report.json".equals(output.getFileName().toString())) {
            throw new IllegalArgumentException("RAPIDOCR_SHADOW_REPORT_PATH_INVALID");
        }
        var parentPath = output.getParent();
        if (Files.isSymbolicLink(parentPath)
                || !Files.isDirectory(parentPath, LinkOption.NOFOLLOW_LINKS)
                || !parentPath.toRealPath().startsWith(evidenceRoot)) {
            throw new IllegalArgumentException("RAPIDOCR_SHADOW_REPORT_PATH_INVALID");
        }
        return output;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the RapidOCR shadow evaluation");
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
