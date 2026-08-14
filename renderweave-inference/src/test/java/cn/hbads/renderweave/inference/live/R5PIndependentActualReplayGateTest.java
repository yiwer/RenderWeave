package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.eval.visual.quality.R5PIndependentReplayEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in bridge: product view materialization in Java, independent OCR and decision in Python. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_R5P_INDEPENDENT_A2", matches = "true")
class R5PIndependentActualReplayGateTest {
    @Test
    void independentlyReplaysEveryActualBranchAndWritesOnlyPayloadSafeEvidence() throws Exception {
        var output = requiredOutput();
        var repository = repositoryRoot();
        var builder = new ProcessBuilder(List.of(
                required("RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE"),
                repository.resolve("tools/replay_r5p_paired_a2.py").toString(),
                "--adapter", required("RENDERWEAVE_DOCUMENT_VISION_ADAPTER_SCRIPT"),
                "--model-root", required("RENDERWEAVE_DOCUMENT_VISION_MODEL_ROOT"),
                "--output", output.toString())).directory(repository.toFile());
        var inherited = System.getenv();
        var environment = builder.environment();
        environment.clear();
        for (var name : List.of(
                "SystemRoot", "WINDIR", "ComSpec", "PATHEXT", "TEMP", "TMP", "LANG")) {
            var value = inherited.get(name);
            if (value != null && !value.isBlank()) environment.put(name, value);
        }
        environment.put("PYTHONUTF8", "1");
        environment.put("PYTHONNOUSERSITE", "1");
        environment.put("PYTHONDONTWRITEBYTECODE", "1");
        environment.put("NO_PROXY", "*");
        var process = builder.start();
        try (var input = process.getOutputStream()) {
            input.write(new R5PIndependentReplayProtocol().build());
        }
        assertTrue(process.waitFor(10, TimeUnit.MINUTES), "R5P_A2_PROCESS_TIMEOUT");
        var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.exitValue(), "R5P_A2_PROCESS_FAILED:" + stderr);
        assertTrue(stdout.matches("R5P_(ACTION_IMPLEMENTATION_ALLOWED|PAIRED_VIEW_NOT_QUALIFIED|MEASUREMENT_INVALID)"));
        assertTrue(Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS));
        var evidence = new R5PIndependentReplayEvidence.Codec().read(Files.readAllBytes(output));
        assertEquals(stdout, evidence.terminalCode());
        assertEquals(0, evidence.externalProviderUsage().attempts());
        assertEquals(0, evidence.apiKeyReads());
        assertEquals(0, evidence.producerDecisionEngineCalls());
        assertEquals(0, evidence.producerReportReads());
    }

    private static Path requiredOutput() throws Exception {
        var repository = repositoryRoot();
        var evidenceRootPath = repository.resolve(".sdlc/evidence").toAbsolutePath().normalize();
        var evidenceRoot = evidenceRootPath.toRealPath();
        var output = Path.of(required("RENDERWEAVE_R5P_A2_EVIDENCE")).toAbsolutePath().normalize();
        if (!output.startsWith(evidenceRootPath) || output.equals(evidenceRootPath)
                || !"r5p-independent-actual-replay.json".equals(output.getFileName().toString())
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5P_A2_EVIDENCE_PATH_INVALID");
        }
        var parent = output.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.toRealPath().startsWith(evidenceRoot)) {
            throw new IllegalArgumentException("R5P_A2_EVIDENCE_PATH_INVALID");
        }
        return output;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("renderweave-inference"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("REPOSITORY_ROOT_NOT_FOUND");
    }
}
