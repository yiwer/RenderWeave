package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import cn.hbads.renderweave.rendering.spi.RenderEngine.EngineOutcome;
import cn.hbads.renderweave.rendering.spi.RenderEngine.RendererCommand;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.FilterOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Production-port tests through the outer process connection seam, never module-internal mocks. */
class RendererProcessAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode VECTORS = loadVectors();
    private static final String MANIFEST = VECTORS.path("authorityContext")
            .path("machineManifestSha256").asText();

    @Test
    void exactProblemBecomesTerminalEngineProblem() throws Exception {
        try (var factory = new ScriptedConnectionFactory(List.of(peer -> {
            handshake(peer);
            var command = RendererProcessProtocol.readFrame(peer.input(), 4096);
            assertEquals(RendererProcessProtocol.FrameType.COMMAND, command.type());
            write(peer.output(), RendererProcessProtocol.FrameType.PROBLEM,
                    jsonCase("problem").getBytes(StandardCharsets.UTF_8));
        }));
             var adapter = adapter(factory)) {
            var actual = adapter.execute(command());
            factory.awaitScripts();
            var outcome = assertInstanceOf(EngineOutcome.TerminalProblem.class, actual);
            assertEquals(ProblemCode.RENDER_INTERNAL_ERROR, outcome.problem().code());
        }
    }

    @Test
    void completeMetadataAndImageBecomeOneSealedOutput() throws Exception {
        try (var factory = new ScriptedConnectionFactory(List.of(peer -> {
            handshake(peer);
            RendererProcessProtocol.readFrame(peer.input(), 4096);
            write(peer.output(), RendererProcessProtocol.FrameType.RESULT_METADATA,
                    jsonCase("png-result-metadata").getBytes(StandardCharsets.UTF_8));
            write(peer.output(), RendererProcessProtocol.FrameType.RESULT_IMAGE,
                    Base64.getDecoder().decode(caseById("png-result-image")
                            .path("payloadBase64").asText()));
        }));
             var adapter = adapter(factory)) {
            var actual = adapter.execute(command());
            factory.awaitScripts();
            var outcome = assertInstanceOf(EngineOutcome.SealedOutput.class, actual);
            assertEquals(1, outcome.output().widthPx());
            assertEquals(1, outcome.output().heightPx());
            assertEquals(68, outcome.output().byteLength());
            assertArrayEquals(
                    Base64.getDecoder().decode(caseById("png-result-metadata")
                            .path("imageBase64").asText()),
                    outcome.output().sealedImageBytes());
        }
    }

    @Test
    void disconnectIsUnknownAndExactRetrySuccessIsReplayed() throws Exception {
        try (var factory = new ScriptedConnectionFactory(List.of(
                peer -> {
                    handshake(peer);
                    RendererProcessProtocol.readFrame(peer.input(), 4096);
                    peer.close();
                },
                peer -> {
                    handshake(peer);
                    RendererProcessProtocol.readFrame(peer.input(), 4096);
                    write(peer.output(), RendererProcessProtocol.FrameType.RESULT_METADATA,
                            jsonCase("png-result-metadata").getBytes(StandardCharsets.UTF_8));
                    write(peer.output(), RendererProcessProtocol.FrameType.RESULT_IMAGE,
                            Base64.getDecoder().decode(caseById("png-result-image")
                                    .path("payloadBase64").asText()));
                }));
             var adapter = adapter(factory)) {
            var first = adapter.execute(command());
            var second = adapter.execute(command());
            factory.awaitScripts();
            assertInstanceOf(EngineOutcome.Unknown.class, first);
            assertInstanceOf(EngineOutcome.Replayed.class, second);
        }
    }

    @Test
    void manifestHandshakeMismatchIsUnknownAndNeverSendsACommand() throws Exception {
        try (var factory = new ScriptedConnectionFactory(List.of(peer -> {
            var hello = RendererProcessProtocol.readFrame(peer.input(), 4096);
            assertEquals(RendererProcessProtocol.FrameType.CLIENT_HELLO, hello.type());
            write(peer.output(), RendererProcessProtocol.FrameType.SERVER_HELLO,
                    RendererProcessProtocol.encodeServerHelloForT22(
                            "sha256:" + "0".repeat(64)));
        }));
             var adapter = adapter(factory)) {
            var actual = adapter.execute(command());
            factory.awaitScripts();
            assertInstanceOf(EngineOutcome.Unknown.class, actual);
        }
    }

    @Test
    void resultIntegrityDriftIsUnknownAndNeverReleasesPartialOutput() throws Exception {
        try (var factory = new ScriptedConnectionFactory(List.of(peer -> {
            handshake(peer);
            RendererProcessProtocol.readFrame(peer.input(), 4096);
            write(peer.output(), RendererProcessProtocol.FrameType.RESULT_METADATA,
                    jsonCase("png-result-metadata").getBytes(StandardCharsets.UTF_8));
            var corrupted = Base64.getDecoder().decode(caseById("png-result-image")
                    .path("payloadBase64").asText());
            corrupted[corrupted.length - 1] ^= 1;
            write(peer.output(), RendererProcessProtocol.FrameType.RESULT_IMAGE, corrupted);
        }));
             var adapter = adapter(factory)) {
            var actual = adapter.execute(command());
            factory.awaitScripts();
            assertInstanceOf(EngineOutcome.Unknown.class, actual);
        }
    }

    @Test
    void commandWriteFailureInvalidatesSessionAndExactRetryUsesFreshConnection()
            throws Exception {
        try (var scripts = new ScriptedConnectionFactory(List.of(
                peer -> {
                    handshake(peer);
                    try {
                        RendererProcessProtocol.readFrame(peer.input(), 4096);
                    } catch (IOException expectedDisconnect) {
                        // The failed client write owns connection invalidation.
                    }
                },
                peer -> {
                    handshake(peer);
                    RendererProcessProtocol.readFrame(peer.input(), 4096);
                    write(peer.output(), RendererProcessProtocol.FrameType.RESULT_METADATA,
                            jsonCase("png-result-metadata").getBytes(StandardCharsets.UTF_8));
                    write(peer.output(), RendererProcessProtocol.FrameType.RESULT_IMAGE,
                            Base64.getDecoder().decode(caseById("png-result-image")
                                    .path("payloadBase64").asText()));
                }));
             var factory = new FailFirstCommandWriteFactory(scripts);
             var adapter = adapter(factory)) {
            assertInstanceOf(EngineOutcome.Unknown.class, adapter.execute(command()));
            assertInstanceOf(EngineOutcome.Replayed.class, adapter.execute(command()));
            scripts.awaitScripts();
        }
    }

    private static RendererProcessAdapter adapter(
            RendererProcessAdapter.ConnectionFactory factory
    ) {
        return new RendererProcessAdapter(
                factory,
                MANIFEST,
                4096,
                Duration.ofSeconds(2),
                Clock.systemUTC());
    }

    private static RendererCommand command() {
        var vector = caseById("png-command");
        return new RendererCommand(
                "renderweave-render-command/1.0",
                new RenderRequestId(vector.path("requestId").asText()),
                "renderweave-renderer/1.0",
                Instant.parse(vector.path("deadlineAt").asText()).toEpochMilli(),
                vector.path("renderDocumentDigest").asText(),
                vector.path("documentCanonicalJson").asText().getBytes(StandardCharsets.UTF_8),
                new OutputSelection.Png(96),
                false);
    }

    private static void handshake(Peer peer) throws Exception {
        var hello = RendererProcessProtocol.readFrame(peer.input(), 4096);
        assertEquals(RendererProcessProtocol.FrameType.CLIENT_HELLO, hello.type());
        assertArrayEquals(RendererProcessProtocol.encodeClientHello(MANIFEST), hello.payload());
        write(peer.output(), RendererProcessProtocol.FrameType.SERVER_HELLO,
                RendererProcessProtocol.encodeServerHelloForT22(MANIFEST));
    }

    private static void write(
            OutputStream output,
            RendererProcessProtocol.FrameType type,
            byte[] payload
    ) throws IOException {
        output.write(RendererProcessProtocol.encodeFrame(type, payload));
        output.flush();
    }

    private static String jsonCase(String id) {
        return caseById(id).path("canonicalJson").asText();
    }

    private static JsonNode caseById(String id) {
        for (var vector : VECTORS.path("cases")) {
            if (id.equals(vector.path("id").asText())) {
                return vector;
            }
        }
        throw new IllegalStateException("missing vector " + id);
    }

    private static JsonNode loadVectors() {
        try {
            var cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            for (var i = 0; i < 5 && cursor != null; i++, cursor = cursor.getParent()) {
                var candidate = cursor.resolve("renderer/protocol-vectors-v1.json");
                if (Files.isRegularFile(candidate)) {
                    return JSON.readTree(Files.readAllBytes(candidate));
                }
            }
            throw new IllegalStateException("renderer protocol vectors not found");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @FunctionalInterface
    private interface PeerScript {
        void run(Peer peer) throws Exception;
    }

    private record Peer(InputStream input, OutputStream output) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                input.close();
            } finally {
                output.close();
            }
        }
    }

    private static final class ScriptedConnectionFactory
            implements RendererProcessAdapter.ConnectionFactory {
        private final ArrayDeque<PeerScript> scripts;
        private final List<CompletableFuture<Void>> runs = new ArrayList<>();

        ScriptedConnectionFactory(List<PeerScript> scripts) {
            this.scripts = new ArrayDeque<>(scripts);
        }

        @Override
        public synchronized RendererProcessAdapter.Connection open() throws IOException {
            var script = scripts.removeFirst();
            var clientInput = new PipedInputStream(64 * 1024);
            var serverOutput = new PipedOutputStream(clientInput);
            var serverInput = new PipedInputStream(64 * 1024);
            var clientOutput = new PipedOutputStream(serverInput);
            var peer = new Peer(serverInput, serverOutput);
            runs.add(CompletableFuture.runAsync(() -> {
                try (peer) {
                    script.run(peer);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
            return new RendererProcessAdapter.Connection() {
                @Override
                public InputStream input() {
                    return clientInput;
                }

                @Override
                public OutputStream output() {
                    return clientOutput;
                }

                @Override
                public void close() throws IOException {
                    try {
                        clientInput.close();
                    } finally {
                        clientOutput.close();
                    }
                }
            };
        }

        @Override
        public void invalidate() {
            // Each scripted connection is already terminal; the next open obtains the next peer.
        }

        void awaitScripts() throws Exception {
            for (var run : runs) {
                run.get(5, TimeUnit.SECONDS);
            }
            assertEquals(0, scripts.size());
        }

        @Override
        public void close() {
            // Adapter owns individual connections; scripts are joined explicitly.
        }
    }

    private static final class FailFirstCommandWriteFactory
            implements RendererProcessAdapter.ConnectionFactory {
        private final ScriptedConnectionFactory delegate;
        private final AtomicBoolean first = new AtomicBoolean(true);

        FailFirstCommandWriteFactory(ScriptedConnectionFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public RendererProcessAdapter.Connection open() throws IOException {
            var connection = delegate.open();
            if (!first.compareAndSet(true, false)) {
                return connection;
            }
            var output = new FilterOutputStream(connection.output()) {
                private int writes;

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (++writes > 1) {
                        throw new IOException("scripted Command write failure");
                    }
                    out.write(bytes, offset, length);
                }
            };
            return new RendererProcessAdapter.Connection() {
                @Override
                public InputStream input() {
                    return connection.input();
                }

                @Override
                public OutputStream output() {
                    return output;
                }

                @Override
                public void close() throws IOException {
                    connection.close();
                }
            };
        }

        @Override
        public void invalidate() {
            delegate.invalidate();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
