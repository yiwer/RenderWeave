package cn.hbads.renderweave.inference.live;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit opt-in bridge for one offline, public-process-only R5P2 A2 replay. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_R5P2_INDEPENDENT_A2", matches = "true")
class R5P2IndependentActualReplayGateTest {
    private static final String COMPLETE = "R5P2_INDEPENDENT_REPLAY_COMPLETE";
    private static final String INVALID = "R5P2_MEASUREMENT_INVALID";

    @Test
    void replaysFortyEightFreshBranchProcessesBeforeWritingPayloadSafeEvidence()
            throws Exception {
        var output = requiredOutput();
        var repository = repositoryRoot();
        var builder = new ProcessBuilder(List.of(
                required("RENDERWEAVE_DOCUMENT_VISION_EXECUTABLE"),
                repository.resolve("tools/replay_r5p2_paired_a2.py").toString(),
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
            input.write(new R5P2IndependentReplayProtocol().build());
        }
        assertTrue(process.waitFor(12, TimeUnit.MINUTES), "R5P2_A2_PROCESS_TIMEOUT");
        var stdout = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        var stderr = new String(
                process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.exitValue(), "R5P2_A2_PROCESS_FAILED:" + stderr);
        assertTrue(stdout.equals(COMPLETE) || stdout.equals(INVALID), stdout);
        assertTrue(Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS));

        var raw = Files.readAllBytes(output);
        var text = new String(raw, StandardCharsets.UTF_8);
        var lowered = text.toLowerCase();
        for (var forbidden : List.of(
                "\"rawbytes\"", "\"normalizedbytes\"", "\"encodedimage\"",
                "\"boundingbox\"", "\"sourceboundingbox\"", "\"sourcepixelbox\"",
                "\"ocrtext\"", "\"goldtext\"", "\"prompttext\"",
                "\"providerrequest\"", "\"providerresponse\"", "\"modeloutput\"",
                "\"candidatejson\"", "\"rootdocument\"", "\"base64\"", "data:image")) {
            assertFalse(lowered.contains(forbidden), forbidden);
        }
        JsonNode envelope = new JsonMapper().readTree(raw);
        assertEquals("renderweave-r5p2-independent-replay-envelope/1.0",
                envelope.get("envelopeVersion").textValue());
        assertTrue(envelope.get("evidenceIdentity").textValue().matches(
                "renderweave-r5p2-independent-replay-evidence/1\\.0:[0-9a-f]{64}"));
        var evidence = envelope.get("evidence");
        assertEquals(stdout, evidence.get("terminalCode").textValue());
        assertEquals(0, evidence.get("externalProviderUsage").get("attempts").intValue());
        assertEquals(0, evidence.get("externalProviderUsage").get("reservations").intValue());
        assertEquals(0L,
                evidence.get("externalProviderUsage").get("costMicrosCny").longValue());
        assertEquals(0, evidence.get("apiKeyReads").intValue());
        assertEquals(0, evidence.get("accessAudit")
                .get("producerReportReadsDuringReplay").intValue());
        assertEquals(0, evidence.get("accessAudit")
                .get("producerMetricReadsDuringReplay").intValue());
        assertEquals(0, evidence.get("accessAudit")
                .get("producerDecisionReadsDuringReplay").intValue());
        if (COMPLETE.equals(stdout)) {
            assertTrue(evidence.get("measurementValid").booleanValue());
            assertEquals(48, evidence.get("accounting")
                    .get("branchAcquisitionProcesses").intValue());
            assertEquals(2, evidence.get("accounting")
                    .get("capabilityProbeProcesses").intValue());
            assertEquals(136, evidence.get("accounting").get("artifactViews").intValue());
            assertEquals(12, evidence.get("cases").size());
        } else {
            assertFalse(evidence.get("measurementValid").booleanValue());
        }
    }

    private static Path requiredOutput() throws Exception {
        var repository = repositoryRoot();
        var evidenceRootPath = repository.resolve(".sdlc/evidence")
                .toAbsolutePath().normalize();
        if (Files.isSymbolicLink(evidenceRootPath)
                || !Files.isDirectory(evidenceRootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5P2_A2_EVIDENCE_ROOT_INVALID");
        }
        var evidenceRoot = evidenceRootPath.toRealPath();
        var output = Path.of(required("RENDERWEAVE_R5P2_A2_EVIDENCE"))
                .toAbsolutePath().normalize();
        if (!output.startsWith(evidenceRootPath) || output.equals(evidenceRootPath)
                || !"r5p2-independent-actual-replay.json".equals(
                        output.getFileName().toString())
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5P2_A2_EVIDENCE_PATH_INVALID");
        }
        var parent = output.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.toRealPath().startsWith(evidenceRoot)) {
            throw new IllegalArgumentException("R5P2_A2_EVIDENCE_PATH_INVALID");
        }
        return output;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for R5P2 independent A2");
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
