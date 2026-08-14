package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluation;
import cn.hbads.renderweave.inference.eval.visual.quality.R5OracleProbeEvaluation;
import cn.hbads.renderweave.inference.eval.visual.quality.R5OracleProbeEvidenceJsonCodec;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit opt-in actual local OCR gate for the fixed VRQ-06 R5 oracle differential. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_VRQ06_R5_PROBE", matches = "true")
class R5OracleProbeGateTest {
    @Test
    void runsTwoFixedBaselineAndOraclePassesAndWritesOnlyPayloadSafeEvidence() throws Exception {
        var output = requiredOutput();
        var result = new R5OracleProbeEvaluation().evaluate(runOrdinal -> {
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
        var codec = new R5OracleProbeEvidenceJsonCodec();
        assertEquals(result.evidenceIdentity(), codec.evidenceIdentity(
                codec.read(Files.readAllBytes(output), result.evidenceIdentity())));
        assertEquals(16, result.evidence().actualAcquisitions());
        assertEquals(0, result.evidence().externalProviderUsage().attempts());
    }

    private static Path requiredOutput() throws Exception {
        var requested = required("RENDERWEAVE_VRQ06_R5_EVIDENCE");
        var repository = repositoryRoot();
        var evidenceRootPath = repository.resolve(".sdlc/evidence").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(evidenceRootPath)
                || !Files.isDirectory(evidenceRootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("VRQ06_EVIDENCE_ROOT_INVALID");
        }
        var evidenceRoot = evidenceRootPath.toRealPath();
        var output = Path.of(requested).toAbsolutePath().normalize();
        var parentPath = output.getParent();
        if (!output.startsWith(evidenceRootPath) || output.equals(evidenceRootPath)
                || !"vrq06-r5-oracle-evidence.json".equals(output.getFileName().toString())
                || Files.isSymbolicLink(parentPath)
                || !Files.isDirectory(parentPath, LinkOption.NOFOLLOW_LINKS)
                || !parentPath.toRealPath().startsWith(evidenceRoot)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("VRQ06_EVIDENCE_PATH_INVALID");
        }
        return output;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the VRQ-06 evidence gate");
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
