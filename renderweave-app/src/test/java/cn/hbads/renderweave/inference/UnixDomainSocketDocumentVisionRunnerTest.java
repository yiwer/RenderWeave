package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.vision.DocumentVisionArtifact;
import cn.hbads.renderweave.inference.vision.DocumentVisionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract test over a real AF_UNIX socket: HTTP/1.1 envelope, capability probe and fail-closed mapping. */
class UnixDomainSocketDocumentVisionRunnerTest {
    private static final String CAPABILITY_JSON = "{"
            + "\"protocolVersion\":\"renderweave-document-vision-process-capability/1.0\","
            + "\"capabilityId\":\"" + LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID + "\","
            + "\"engine\":\"rapidocr-openvino-ppocrv6-small\","
            + "\"engineVersion\":\"rapidocr-3.9.2+openvino-2026.0.0\","
            + "\"modelManifestSha256\":\"c05805399d7d10b1d1e32f2f52faf2a9fe6617db50f6b96221cb3b7be47e58a5\""
            + "}";

    @TempDir
    Path socketDirectory;

    private ServerSocketChannel server;
    private ExecutorService pool;
    private final AtomicReference<String> ocrBody = new AtomicReference<>(null);
    private final AtomicReference<Long> responseDelayMillis = new AtomicReference<>(0L);

    @AfterEach
    void stopServer() throws IOException {
        if (pool != null) pool.shutdownNow();
        if (server != null) server.close();
    }

    @Test
    void capabilityProbeAndPreprocessRoundTripSucceedOverUnixSocket() {
        var socket = startServer();
        ocrBody.set("""
                {"protocolVersion":"renderweave-document-vision-response/1.0",
                 "capabilityId":"%s",
                 "artifacts":[{"artifactId":"%s","sourceOrdinal":0,
                   "lines":[{"left":1,"top":2,"right":30,"bottom":12,"confidenceBps":9500,"text":"OK"}]}]}
                """.formatted(LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID,
                        "a".repeat(64)).replace("\n", "").replace(" ", ""));

        var preprocessor = LocalProcessDocumentVisionPreprocessor.forUnixSocket(
                socket, Duration.ofSeconds(10),
                LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID);

        assertThat(preprocessor.capability().available())
                .as("diagnosticCode=%s", preprocessor.capability().diagnosticCode())
                .isTrue();
        assertThat(preprocessor.capability().capabilityId())
                .isEqualTo(LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID);

        var observation = preprocessor.preprocess(List.of(new DocumentVisionArtifact(
                "a".repeat(64), 0, "image/png", new byte[] {1, 2, 3}, 32, 16
        )));
        assertThat(observation.artifacts()).hasSize(1);
        var lines = observation.artifacts().getFirst().lines();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().text()).isEqualTo("OK");
        assertThat(lines.getFirst().confidence())
                .isEqualTo(cn.hbads.renderweave.inference.vision.DocumentVisionObservation
                        .ConfidenceBucket.HIGH);
    }

    @Test
    void typedSidecarErrorsPropagateAsDocumentVisionCodes() {
        var socket = startServer();
        ocrBody.set("422:{\"errorCode\":\"DOCUMENT_VISION_ARTIFACT_INVALID\"}");
        var preprocessor = LocalProcessDocumentVisionPreprocessor.forUnixSocket(
                socket, Duration.ofSeconds(10),
                LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID);
        assertThat(preprocessor.capability().available()).isTrue();

        assertThatThrownBy(() -> preprocessor.preprocess(List.of(new DocumentVisionArtifact(
                "b".repeat(64), 0, "image/png", new byte[] {1}, 32, 16
        ))))
                .isInstanceOf(DocumentVisionException.class)
                .hasMessage("DOCUMENT_VISION_ARTIFACT_INVALID");
    }

    @Test
    void unresponsiveSidecarFailsClosedWithTimeout() {
        var socket = startServer();
        responseDelayMillis.set(10_000L);

        var preprocessor = LocalProcessDocumentVisionPreprocessor.forUnixSocket(
                socket, Duration.ofSeconds(1),
                LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID);

        assertThat(preprocessor.capability().available()).isFalse();
        assertThat(preprocessor.capability().diagnosticCode()).isEqualTo("DOCUMENT_VISION_TIMEOUT");
    }

    @Test
    void missingSocketFailsClosedBeforeAnyDispatch() {
        var preprocessor = LocalProcessDocumentVisionPreprocessor.forUnixSocket(
                socketDirectory.resolve("absent.sock"), Duration.ofSeconds(2),
                LocalProcessDocumentVisionPreprocessor.EXPECTED_CAPABILITY_ID);

        assertThat(preprocessor.capability().available()).isFalse();
        assertThat(preprocessor.capability().diagnosticCode())
                .isIn("DOCUMENT_VISION_PROCESS_FAILED", "DOCUMENT_VISION_STARTUP_PROBE_FAILED");
    }

    private Path startServer() {
        try {
            var socket = socketDirectory.resolve("document-vision.sock");
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            pool = Executors.newCachedThreadPool();
            pool.submit(() -> {
                while (server.isOpen()) {
                    try {
                        var connection = server.accept();
                        pool.submit(() -> handle(connection));
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            return socket;
        } catch (IOException failure) {
            throw new IllegalStateException("AF_UNIX test server failed", failure);
        }
    }

    private void handle(SocketChannel connection) {
        try (connection) {
            var delay = responseDelayMillis.get();
            if (delay > 0) Thread.sleep(delay);
            var head = new ByteArrayOutputStream();
            var buffer = ByteBuffer.allocate(4 * 1024);
            var request = new ByteArrayOutputStream();
            while (true) {
                buffer.clear();
                var read = connection.read(buffer);
                if (read <= 0) return;
                buffer.flip();
                var chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                request.write(chunk);
                if (request.toString(StandardCharsets.US_ASCII).contains("\r\n\r\n")) break;
            }
            var text = request.toString(StandardCharsets.US_ASCII);
            var path = text.split(" ", 3)[1];
            final int status;
            final String body;
            if ("/capability".equals(path)) {
                status = 200;
                body = CAPABILITY_JSON;
            } else if ("/ocr".equals(path)) {
                var configured = ocrBody.get();
                if (configured != null && configured.startsWith("422:")) {
                    status = 422;
                    body = configured.substring(4);
                } else {
                    status = 200;
                    body = configured;
                }
            } else {
                status = 404;
                body = "{\"errorCode\":\"DOCUMENT_VISION_ROUTE_UNKNOWN\"}";
            }
            var payload = body.getBytes(StandardCharsets.UTF_8);
            var response = ("HTTP/1.1 " + status + " X\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            var output = ByteBuffer.allocate(response.length + payload.length);
            output.put(response).put(payload).flip();
            while (output.hasRemaining()) {
                if (connection.write(output) == 0) Thread.onSpinWait();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Test connections may close abruptly; the assertions carry the verdict.
        }
    }
}
