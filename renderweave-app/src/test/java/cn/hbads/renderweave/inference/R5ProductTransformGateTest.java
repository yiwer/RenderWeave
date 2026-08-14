package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluation;
import cn.hbads.renderweave.inference.eval.visual.quality.R5ProductTransformEvidenceJsonCodec;
import cn.hbads.renderweave.inference.live.R5ProductTransformEvaluation;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit opt-in, zero-Provider local OCR gate for the exact product raster transform. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_R5_PRODUCT_TRANSFORM", matches = "true")
class R5ProductTransformGateTest {
    @Test
    void executesTheFrozenTwoRunAssignmentAndWritesPayloadSafeEvidence() throws Exception {
        var output = requiredOutput();
        var result = new R5ProductTransformEvaluation().evaluate(runOrdinal -> {
            var configured = LocalProcessDocumentVisionPreprocessor.fromConfiguration(
                    true,
                    required("RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE"),
                    required("RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT"),
                    required("RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT"),
                    30,
                    LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID);
            assertTrue(configured.capability().available(), configured.capability().diagnosticCode());
            var adapter = (LocalProcessDocumentVisionPreprocessor) configured;
            assertEquals(RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS),
                    adapter.acquisitionPolicy());
            return RapidOcrShadowEvaluation.RunSession.of(adapter.acquisitionPolicy(), adapter);
        });
        Files.write(output, result.encodedEvidence(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        var codec = new R5ProductTransformEvidenceJsonCodec();
        assertEquals(result.evidenceIdentity(), codec.evidenceIdentity(
                codec.read(Files.readAllBytes(output), result.evidenceIdentity())));
        assertEquals(16, result.evidence().actualAcquisitions());
        assertEquals(0, result.evidence().externalProviderUsage().attempts());
    }

    private static Path requiredOutput() throws Exception {
        var requested = required("RENDERWEAVE_R5_PRODUCT_TRANSFORM_EVIDENCE");
        var repository = repositoryRoot();
        var evidenceRootPath = repository.resolve(".sdlc/evidence").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(evidenceRootPath)
                || !Files.isDirectory(evidenceRootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5_PRODUCT_EVIDENCE_ROOT_INVALID");
        }
        var evidenceRoot = evidenceRootPath.toRealPath();
        var output = Path.of(requested).toAbsolutePath().normalize();
        var parent = output.getParent();
        if (!output.startsWith(evidenceRootPath) || output.equals(evidenceRootPath)
                || !"r5-product-transform-evidence.json".equals(output.getFileName().toString())
                || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.toRealPath().startsWith(evidenceRoot)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5_PRODUCT_EVIDENCE_PATH_INVALID");
        }
        return output;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the R5 product-transform gate");
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
