package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.vision.DocumentVisionArtifact;
import cn.hbads.renderweave.inference.vision.DocumentVisionException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProcessDocumentVisionPreprocessorTest {
    private static final String CAPABILITY = LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID;
    private static final String ARTIFACT_ID = "a".repeat(64);
    private static final String CAPABILITY_JSON = """
            {"protocolVersion":"renderweave-document-vision-process-capability/1.0",
             "capabilityId":"%s","engine":"rapidocr-openvino-ppocrv6-small",
             "engineVersion":"rapidocr-3.9.2+openvino-2026.0.0",
             "modelManifestSha256":"%s"}
            """.formatted(CAPABILITY, LocalProcessDocumentVisionPreprocessor.EXPECTED_MODEL_MANIFEST_SHA256);

    @Test
    void probesExactCapabilityAndMapsBoundedOutputWithoutLeakingText() {
        var runner = new StubRunner("""
                {"protocolVersion":"renderweave-document-vision-response/1.0",
                 "capabilityId":"%s","artifacts":[{"artifactId":"%s","sourceOrdinal":0,
                 "lines":[
                   {"left":50,"top":20,"right":90,"bottom":40,"confidenceBps":5900,"text":" secret stop "},
                   {"left":10,"top":5,"right":40,"bottom":15,"confidenceBps":9200,"text":"站点 名称"}
                 ]}]}
                """.formatted(CAPABILITY, ARTIFACT_ID));
        var adapter = adapter(runner);

        var observation = adapter.preprocess(List.of(artifact()));

        assertTrue(adapter.capability().available());
        assertEquals(CAPABILITY, observation.capabilityId());
        assertEquals(2, observation.lineCount());
        var lines = observation.artifacts().getFirst().lines();
        assertEquals("站点 名称", lines.getFirst().text());
        assertEquals("secret stop", lines.getLast().text());
        assertEquals(1_000, lines.getFirst().boundingBox().left());
        assertEquals(500, lines.getFirst().boundingBox().top());
        assertEquals("HIGH", lines.getFirst().confidence().name());
        assertEquals("LOW", lines.getLast().confidence().name());
        assertFalse(observation.toString().contains("站点"));
        assertFalse(lines.getFirst().toString().contains("站点"));
        assertEquals(2, runner.calls.size());
        assertTrue(runner.calls.getFirst().command().contains("--capability"));
        assertFalse(runner.calls.getLast().command().contains("--capability"));
        assertEquals("*", runner.calls.getLast().environment().get("NO_PROXY"));
        assertFalse(runner.calls.getLast().environment().containsKey("DASHSCOPE_API_KEY"));
        assertFalse(runner.calls.getLast().environment().containsKey("DASHSCOPE_TOKEN_API_KEY"));
        assertFalse(runner.calls.getLast().environment().containsKey("HTTP_PROXY"));
        assertFalse(runner.calls.getLast().environment().containsKey("HTTPS_PROXY"));
        var request = new String(runner.calls.getLast().input(), StandardCharsets.UTF_8);
        assertTrue(request.contains("\"protocolVersion\":\"renderweave-document-vision-request/1.0\""));
        assertTrue(request.contains(ARTIFACT_ID));
    }

    @Test
    void rejectsAmbiguousOrCoercedProcessResponsesFailClosed() {
        for (var invalid : List.of(
                "{\"protocolVersion\":\"renderweave-document-vision-response/1.0\",\"capabilityId\":\""
                        + CAPABILITY + "\",\"artifacts\":[],\"unknown\":1}",
                "{\"protocolVersion\":\"renderweave-document-vision-response/1.0\",\"protocolVersion\":\"x\","
                        + "\"capabilityId\":\"" + CAPABILITY + "\",\"artifacts\":[]}",
                "{\"protocolVersion\":\"renderweave-document-vision-response/1.0\",\"capabilityId\":\""
                        + CAPABILITY + "\",\"artifacts\":[]}{}",
                "{\"protocolVersion\":\"renderweave-document-vision-response/1.0\",\"capabilityId\":\""
                        + CAPABILITY + "\",\"artifacts\":[{\"artifactId\":\"" + ARTIFACT_ID
                        + "\",\"sourceOrdinal\":\"0\",\"lines\":[]}]}"
        )) {
            var failure = assertThrows(
                    DocumentVisionException.class,
                    () -> adapter(new StubRunner(invalid)).preprocess(List.of(artifact()))
            );
            assertEquals("DOCUMENT_VISION_OUTPUT_INVALID", failure.code());
        }
    }

    @Test
    void exactCapabilityMismatchAndProcessFailuresUseStablePayloadFreeCodes() {
        for (var mismatch : List.of(
                CAPABILITY_JSON.replace(CAPABILITY, "other-capability"),
                CAPABILITY_JSON.replace(
                        LocalProcessDocumentVisionPreprocessor.EXPECTED_MODEL_MANIFEST_SHA256,
                        "d".repeat(64)
                ),
                CAPABILITY_JSON.replace("rapidocr-3.9.2+openvino-2026.0.0", "rapidocr-3.9.3")
        )) {
            var failure = assertThrows(
                    DocumentVisionException.class,
                    () -> LocalProcessDocumentVisionPreprocessor.forTest(
                            List.of("python", "adapter.py"), Path.of("models"), Duration.ofSeconds(10),
                            CAPABILITY, new StubRunner("{}", mismatch)
                    )
            );
            assertEquals("DOCUMENT_VISION_CAPABILITY_MISMATCH", failure.code());
        }

        var failed = new StubRunner("{}");
        failed.failureCode = "DOCUMENT_VISION_TIMEOUT";
        var timeout = assertThrows(
                DocumentVisionException.class,
                () -> adapter(failed).preprocess(List.of(artifact()))
        );
        assertEquals("DOCUMENT_VISION_TIMEOUT", timeout.code());
        assertFalse(timeout.getMessage().contains(ARTIFACT_ID));
    }

    @Test
    void disabledConfigurationNeverProbesOrReadsFiles() {
        var adapter = LocalProcessDocumentVisionPreprocessor.fromConfiguration(
                false, "missing-python", "missing-script", "missing-models", 30, CAPABILITY
        );

        assertFalse(adapter.capability().available());
        assertEquals("DOCUMENT_VISION_DISABLED", adapter.capability().diagnosticCode());
        assertEquals("DOCUMENT_VISION_DISABLED", assertThrows(
                DocumentVisionException.class,
                () -> adapter.preprocess(List.of(artifact()))
        ).code());
    }

    private static LocalProcessDocumentVisionPreprocessor adapter(StubRunner runner) {
        return LocalProcessDocumentVisionPreprocessor.forTest(
                List.of("python", "adapter.py"), Path.of("models"), Duration.ofSeconds(10),
                CAPABILITY, runner
        );
    }

    private static DocumentVisionArtifact artifact() {
        return new DocumentVisionArtifact(
                ARTIFACT_ID, 0, "image/png", new byte[]{1, 2, 3}, 100, 100
        );
    }

    private static final class StubRunner implements LocalProcessDocumentVisionPreprocessor.ProcessRunner {
        private final byte[] response;
        private final byte[] capabilityResponse;
        private final List<Call> calls = new ArrayList<>();
        private String failureCode;

        private StubRunner(String response) {
            this(response, CAPABILITY_JSON);
        }

        private StubRunner(String response, String capabilityResponse) {
            this.response = response.getBytes(StandardCharsets.UTF_8);
            this.capabilityResponse = capabilityResponse.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] execute(
                List<String> command,
                byte[] input,
                Duration timeout,
                Map<String, String> environment
        ) {
            calls.add(new Call(List.copyOf(command), input.clone(), Map.copyOf(environment)));
            if (command.contains("--capability")) return capabilityResponse;
            if (failureCode != null) throw new DocumentVisionException(failureCode);
            return response;
        }
    }

    private record Call(List<String> command, byte[] input, Map<String, String> environment) {
        private Call {
            input = input.clone();
        }

        @Override
        public byte[] input() {
            return input.clone();
        }
    }
}
