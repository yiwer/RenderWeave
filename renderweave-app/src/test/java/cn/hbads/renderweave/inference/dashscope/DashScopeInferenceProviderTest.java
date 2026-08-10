package cn.hbads.renderweave.inference.dashscope;

import com.sun.net.httpserver.HttpServer;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.profile.InferencePromptRegistry;
import cn.hbads.renderweave.inference.provider.ProviderCallException;
import cn.hbads.renderweave.inference.provider.ProviderImage;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderNotConfiguredException;
import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashScopeInferenceProviderTest {
    private static final String TEST_KEY = "sk-test-renderweave-placeholder";
    private final InferenceProfileRegistry profiles = new InferenceProfileRegistry();
    private final InferencePromptRegistry prompts = new InferencePromptRegistry();

    @Test
    void emitsTheNarrowChatCompletionsContractAndParsesSafeUsage() throws Exception {
        var transport = new CapturingTransport(new DashScopeHttpResponse(
                200,
                Map.of("x-request-id", List.of("header-request-id")),
                """
                {"id":"body-request-id","model":"qwen3.7-flash","choices":[{"message":{"content":"{\\"contractVersion\\":\\"renderweave-candidate/1.0\\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1000,"completion_tokens":500}}
                """.getBytes(StandardCharsets.UTF_8)
        ));
        var provider = new DashScopeInferenceProvider(
                Optional.of(DashScopeApiKey.fromValue(TEST_KEY)), transport, JsonMapper.builder().build()
        );
        var profile = profiles.require("dashscope-qwen37-flash-v1").profile();
        var response = provider.complete(new ProviderInferenceRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                0,
                InferenceStage.STRUCTURE,
                profile,
                prompts.require(profile.promptVersion()).text(),
                "{\"mode\":\"IMAGE_ONLY\",\"artifacts\":[{\"artifactId\":\"" + "a".repeat(64) + "\"}]}",
                List.of(new ProviderImage("a".repeat(64), "image/png", new byte[] {1, 2, 3}))
        ));

        assertEquals(profile.providerEndpoint(), transport.uri().toString());
        assertEquals(Duration.ofSeconds(90), transport.timeout());
        assertEquals(TEST_KEY.length() + "Bearer ".length(),
                transport.headers().get("Authorization").length());
        var body = JsonMapper.builder().build().readTree(transport.body());
        assertEquals("qwen3.7-flash", body.get("model").asText());
        assertEquals("json_object", body.get("response_format").get("type").asText());
        assertFalse(body.get("enable_thinking").asBoolean());
        assertFalse(body.has("tools"));
        assertEquals(4096, body.get("max_tokens").asInt());
        var mediaUrl = body.get("messages").get(1).get("content").get(0)
                .get("image_url").get("url").asText();
        assertTrue(mediaUrl.startsWith("data:image/png;base64,"));
        assertFalse(mediaUrl.startsWith("http"));
        assertEquals("body-request-id", response.providerRequestId());
        assertEquals(1_000, response.usage().inputTokens());
        assertEquals(500, response.usage().outputTokens());
    }

    @Test
    void missingKeyFailsBeforeTransportAndApplicationCanStillConstruct() {
        var transport = new CapturingTransport(null);
        var provider = new DashScopeInferenceProvider(
                Optional.empty(), transport, JsonMapper.builder().build()
        );
        var profile = profiles.require("dashscope-qwen37-flash-v1").profile();

        var failure = assertThrows(ProviderNotConfiguredException.class, () -> provider.complete(
                new ProviderInferenceRequest(
                        UUID.randomUUID(), 0, InferenceStage.STRUCTURE, profile,
                        prompts.require(profile.promptVersion()).text(), "{}", List.of()
                )
        ));
        assertEquals("DASHSCOPE_NOT_CONFIGURED", failure.code());
        assertEquals(0, transport.calls());
    }

    @Test
    void providerErrorsExposeOnlyStableMetadataAndNeverTheBodyOrKey() {
        var transport = new CapturingTransport(new DashScopeHttpResponse(
                429, Map.of("retry-after", List.of("2")),
                ("upstream body containing " + TEST_KEY).getBytes(StandardCharsets.UTF_8)
        ));
        var provider = new DashScopeInferenceProvider(
                Optional.of(DashScopeApiKey.fromValue(TEST_KEY)), transport, JsonMapper.builder().build()
        );
        var profile = profiles.require("dashscope-qwen38-max-v1").profile();

        var failure = assertThrows(ProviderCallException.class, () -> provider.complete(
                new ProviderInferenceRequest(
                        UUID.randomUUID(), 0, InferenceStage.STRUCTURE, profile,
                        prompts.require(profile.promptVersion()).text(), "{}", List.of()
                )
        ));
        assertEquals("DASHSCOPE_HTTP_429", failure.code());
        assertTrue(failure.retryable());
        assertEquals(Duration.ofSeconds(2), failure.retryAfter().orElseThrow());
        assertFalse(failure.getMessage().contains(TEST_KEY));
        assertFalse(failure.getMessage().contains("upstream body"));
    }

    @Test
    void httpDateRetryAfterStillProducesAFailClosedPresenceSignal() {
        var transport = new CapturingTransport(new DashScopeHttpResponse(
                429, Map.of("Retry-After", List.of("Sat, 08 Aug 2026 12:00:00 GMT")),
                new byte[0]
        ));
        var provider = new DashScopeInferenceProvider(
                Optional.of(DashScopeApiKey.fromValue(TEST_KEY)), transport, JsonMapper.builder().build()
        );
        var profile = profiles.require("dashscope-qwen37-flash-v1").profile();

        var failure = assertThrows(ProviderCallException.class, () -> provider.complete(
                new ProviderInferenceRequest(
                        UUID.randomUUID(), 0, InferenceStage.STRUCTURE, profile,
                        prompts.require(profile.promptVersion()).text(), "{}", List.of()
                )
        ));

        assertTrue(failure.retryAfter().isPresent());
        assertEquals(Duration.ofMinutes(5), failure.retryAfter().orElseThrow());
    }

    @Test
    void jdkTransportBoundsTheResponseWhileItIsBeingRead() throws Exception {
        var responseBytes = 3 * 1024 * 1024 + 2;
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, responseBytes);
            try (var output = exchange.getResponseBody()) {
                var chunk = new byte[16 * 1024];
                var remaining = responseBytes;
                while (remaining > 0) {
                    var length = Math.min(remaining, chunk.length);
                    output.write(chunk, 0, length);
                    remaining -= length;
                }
            } catch (IOException ignored) {
                // Expected when the bounded client closes before the oversized body is exhausted.
            }
        });
        server.start();
        try {
            var failure = assertThrows(ProviderCallException.class, () ->
                    new JdkDashScopeHttpTransport().exchange(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                            Map.of(), new byte[0], Duration.ofSeconds(5)
                    ));
            assertEquals("DASHSCOPE_RESPONSE_TOO_LARGE", failure.code());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void jdkTransportClassifiesTheRequestDeadlineAsATimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(250);
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var failure = assertThrows(ProviderCallException.class, () ->
                    new JdkDashScopeHttpTransport().exchange(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                            Map.of(), new byte[0], Duration.ofMillis(50)
                    ));
            assertEquals("DASHSCOPE_TIMEOUT", failure.code());
            assertTrue(failure.retryable());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void secretCanComeFromEnvironmentValueOrMountedFileWithoutAppearingInToString(@TempDir Path temp) throws Exception {
        var file = temp.resolve("dashscope_api_key");
        Files.writeString(file, TEST_KEY + "\n", StandardCharsets.UTF_8);

        var direct = DashScopeApiKey.resolve(TEST_KEY, "").orElseThrow();
        var mounted = DashScopeApiKey.resolve("", file.toString()).orElseThrow();
        assertEquals("<redacted:DASHSCOPE_API_KEY>", direct.toString());
        assertEquals("<redacted:DASHSCOPE_API_KEY>", mounted.toString());
        assertThrows(IllegalStateException.class,
                () -> DashScopeApiKey.resolve(TEST_KEY, file.toString()));
    }

    private static final class CapturingTransport implements DashScopeHttpTransport {
        private final DashScopeHttpResponse response;
        private java.net.URI uri;
        private Map<String, String> headers;
        private byte[] body;
        private Duration timeout;
        private int calls;

        private CapturingTransport(DashScopeHttpResponse response) {
            this.response = response;
        }

        @Override
        public DashScopeHttpResponse exchange(
                java.net.URI uri,
                Map<String, String> headers,
                byte[] body,
                Duration timeout
        ) {
            calls++;
            this.uri = uri;
            this.headers = Map.copyOf(headers);
            this.body = body.clone();
            this.timeout = timeout;
            return response;
        }

        java.net.URI uri() { return uri; }
        Map<String, String> headers() { return headers; }
        byte[] body() { return body.clone(); }
        Duration timeout() { return timeout; }
        int calls() { return calls; }
    }
}
