package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderOutput;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import cn.hbads.renderweave.rendering.spi.RenderEngine;
import cn.hbads.renderweave.rendering.spi.RenderEngine.EngineOutcome;
import cn.hbads.renderweave.rendering.spi.RenderEngine.RendererCommand;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistent, multiplexed process Adapter for {@link RenderEngine}.
 *
 * <p>The transport and process lifecycle are one outer seam so tests can supply a scripted peer;
 * production supplies {@link RendererProcessSupervisor}. No test bypass reaches protocol parsing,
 * request correlation, result sealing, or failure mapping.
 */
final class RendererProcessAdapter implements RenderEngine, AutoCloseable {

    interface Connection extends AutoCloseable {
        InputStream input();

        OutputStream output();

        @Override
        void close() throws IOException;
    }

    interface ConnectionFactory extends AutoCloseable {
        Connection open() throws IOException;

        void invalidate();

        @Override
        void close() throws IOException;
    }

    private enum CallerDisposition {
        SEALED,
        JOINED,
        REPLAYED
    }

    private sealed interface WireOutcome
            permits WireOutcome.Success, WireOutcome.Problem, WireOutcome.Unknown {
        record Success(RenderOutput output) implements WireOutcome {
        }

        record Problem(RenderingProblem problem) implements WireOutcome {
        }

        record Unknown() implements WireOutcome {
        }
    }

    private static final class PendingRequest {
        final RendererCommand command;
        final RendererProcessProtocol.EncodedCommand encoded;
        final CompletableFuture<WireOutcome> outcome = new CompletableFuture<>();
        volatile long generation;
        volatile RendererProcessProtocol.ParsedResult metadata;

        PendingRequest(
                RendererCommand command,
                RendererProcessProtocol.EncodedCommand encoded
        ) {
            this.command = command;
            this.encoded = encoded;
        }
    }

    private record UnknownAttempt(String commandDigest, long deadlineEpochMilli) {
    }

    private static final class Session {
        final long generation;
        final Connection connection;
        final AtomicBoolean active = new AtomicBoolean(true);
        final CompletableFuture<Void> handshake = new CompletableFuture<>();
        volatile Thread reader;

        Session(long generation, Connection connection) {
            this.generation = generation;
            this.connection = connection;
        }
    }

    private final ConnectionFactory connections;
    private final String expectedManifestSha256;
    private final int maximumFramedBytes;
    private final Duration handshakeTimeout;
    private final Clock clock;
    private final Object sessionMonitor = new Object();
    private final Object writeMonitor = new Object();
    private final ConcurrentHashMap<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UnknownAttempt> unknownAttempts =
            new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Session session;

    RendererProcessAdapter(
            ConnectionFactory connections,
            String expectedManifestSha256,
            int maximumFramedBytes,
            Duration handshakeTimeout,
            Clock clock
    ) {
        this.connections = Objects.requireNonNull(connections, "connections");
        RendererProcessProtocol.requireSha256(
                expectedManifestSha256, "expectedManifestSha256");
        if (maximumFramedBytes < 1) {
            throw new IllegalArgumentException("maximumFramedBytes must be positive");
        }
        this.handshakeTimeout = Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        if (handshakeTimeout.isZero() || handshakeTimeout.isNegative()) {
            throw new IllegalArgumentException("handshakeTimeout must be positive");
        }
        this.expectedManifestSha256 = expectedManifestSha256;
        this.maximumFramedBytes = maximumFramedBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EngineOutcome execute(RendererCommand command) {
        Objects.requireNonNull(command, "command");
        if (closed.get()) {
            return new EngineOutcome.Unknown();
        }
        RendererProcessProtocol.EncodedCommand encoded;
        try {
            encoded = RendererProcessProtocol.encodeCommand(command);
        } catch (RuntimeException e) {
            return terminal(ProblemCode.RENDER_INTERNAL_ERROR);
        }

        var now = clock.millis();
        unknownAttempts.entrySet().removeIf(entry -> entry.getValue().deadlineEpochMilli() <= now);
        if (command.deadlineAtEpochMilli() <= now) {
            return terminal(ProblemCode.RENDER_DEADLINE_EXCEEDED);
        }
        var requestId = command.renderRequestId().value();
        var priorUnknown = unknownAttempts.get(requestId);
        if (priorUnknown != null
                && !priorUnknown.commandDigest().equals(encoded.commandDigest())) {
            return terminal(ProblemCode.RENDER_REQUEST_CONFLICT);
        }

        var candidate = new PendingRequest(command, encoded);
        var existing = pending.putIfAbsent(requestId, candidate);
        var request = existing == null ? candidate : existing;
        CallerDisposition disposition;
        if (existing != null) {
            if (!existing.encoded.commandDigest().equals(encoded.commandDigest())) {
                return terminal(ProblemCode.RENDER_REQUEST_CONFLICT);
            }
            disposition = CallerDisposition.JOINED;
        } else {
            disposition = priorUnknown == null
                    ? CallerDisposition.SEALED
                    : CallerDisposition.REPLAYED;
            Session current = null;
            try {
                current = ensureSession();
                request.generation = current.generation;
                synchronized (writeMonitor) {
                    current.connection.output().write(RendererProcessProtocol.encodeFrame(
                            RendererProcessProtocol.FrameType.COMMAND,
                            encoded.canonicalJsonUtf8()));
                    current.connection.output().flush();
                }
            } catch (Exception e) {
                if (current == null) {
                    markUnknown(requestId, request);
                } else {
                    failSession(current);
                }
            }
        }

        var remaining = command.deadlineAtEpochMilli() - clock.millis();
        if (remaining <= 0) {
            markUnknown(requestId, request);
            return new EngineOutcome.Unknown();
        }
        WireOutcome wire;
        try {
            wire = request.outcome.get(remaining, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markUnknown(requestId, request);
            return new EngineOutcome.Unknown();
        } catch (ExecutionException | TimeoutException e) {
            markUnknown(requestId, request);
            return new EngineOutcome.Unknown();
        }
        if (wire instanceof WireOutcome.Problem problem) {
            unknownAttempts.remove(requestId);
            return new EngineOutcome.TerminalProblem(problem.problem());
        }
        if (wire instanceof WireOutcome.Unknown) {
            unknownAttempts.put(requestId,
                    new UnknownAttempt(encoded.commandDigest(), command.deadlineAtEpochMilli()));
            return new EngineOutcome.Unknown();
        }
        var output = ((WireOutcome.Success) wire).output();
        unknownAttempts.remove(requestId);
        return switch (disposition) {
            case SEALED -> new EngineOutcome.SealedOutput(output);
            case JOINED -> new EngineOutcome.Joined(output);
            case REPLAYED -> new EngineOutcome.Replayed(output);
        };
    }

    private Session ensureSession() throws IOException {
        var observed = session;
        if (observed != null && observed.active.get()) {
            return observed;
        }
        synchronized (sessionMonitor) {
            observed = session;
            if (observed != null && observed.active.get()) {
                return observed;
            }
            if (closed.get()) {
                throw new IOException("renderer Adapter is closed");
            }
            Connection connection = null;
            Session created = null;
            try {
                connection = connections.open();
                created = new Session(generations.incrementAndGet(), connection);
                var readerSession = created;
                created.reader = Thread.ofPlatform()
                        .daemon(true)
                        .name("renderweave-renderer-process-reader-" + created.generation)
                        .start(() -> readLoop(readerSession));
                connection.output().write(RendererProcessProtocol.encodeFrame(
                        RendererProcessProtocol.FrameType.CLIENT_HELLO,
                        RendererProcessProtocol.encodeClientHello(expectedManifestSha256)));
                connection.output().flush();
                created.handshake.get(handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!created.active.get()) {
                    throw new IOException("renderer connection closed during handshake");
                }
                session = created;
                return created;
            } catch (Exception e) {
                if (created != null) {
                    created.active.set(false);
                }
                closeQuietly(connection);
                connections.invalidate();
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (e instanceof IOException io) {
                    throw io;
                }
                throw new IOException("renderer handshake failed", e);
            }
        }
    }

    private void readLoop(Session observed) {
        try {
            var handshake = RendererProcessProtocol.readFrame(
                    observed.connection.input(), maximumFramedBytes);
            if (handshake.type() != RendererProcessProtocol.FrameType.SERVER_HELLO) {
                throw new RendererProcessProtocol.ProtocolException(
                        "renderer handshake did not return SERVER_HELLO");
            }
            RendererProcessProtocol.validateServerHelloForT22(
                    handshake.payload(), expectedManifestSha256);
            observed.handshake.complete(null);
            while (observed.active.get() && !closed.get()) {
                var frame = RendererProcessProtocol.readFrame(
                        observed.connection.input(), maximumFramedBytes);
                dispatch(observed, frame);
            }
        } catch (Exception e) {
            observed.handshake.completeExceptionally(e);
            failSession(observed);
        }
    }

    private void dispatch(Session observed, RendererProcessProtocol.Frame frame)
            throws RendererProcessProtocol.ProtocolException {
        switch (frame.type()) {
            case PROBLEM -> dispatchProblem(observed, frame.payload());
            case RESULT_METADATA -> dispatchMetadata(observed, frame.payload());
            case RESULT_IMAGE -> dispatchImage(observed, frame.payload());
            default -> throw new RendererProcessProtocol.ProtocolException(
                    "renderer sent an illegal post-handshake frame");
        }
    }

    private void dispatchProblem(Session observed, byte[] payload)
            throws RendererProcessProtocol.ProtocolException {
        var parsed = RendererProcessProtocol.parseProblem(payload);
        var request = pending.get(parsed.requestId());
        if (request == null) {
            return;
        }
        if (request.generation != observed.generation) {
            throw new RendererProcessProtocol.ProtocolException(
                    "renderer problem crossed connection generation");
        }
        var problem = new RenderingProblem(
                parsed.code(),
                EvaluationStage.ENGINE,
                parsed.safeLocation(),
                parsed.limitId());
        complete(parsed.requestId(), request, new WireOutcome.Problem(problem));
    }

    private void dispatchMetadata(Session observed, byte[] payload)
            throws RendererProcessProtocol.ProtocolException {
        var parsed = RendererProcessProtocol.parseResult(payload);
        var request = pending.get(parsed.requestId());
        if (request == null) {
            return;
        }
        if (request.generation != observed.generation
                || request.metadata != null
                || !request.command.rendererProfile().equals(parsed.rendererProfile())
                || !request.command.outputSelection().equals(parsed.outputSelection())) {
            throw new RendererProcessProtocol.ProtocolException(
                    "renderer RESULT metadata does not match pending Command");
        }
        request.metadata = parsed;
    }

    private void dispatchImage(Session observed, byte[] payload)
            throws RendererProcessProtocol.ProtocolException {
        var parsed = RendererProcessProtocol.parseResultImage(payload);
        var request = pending.get(parsed.requestId());
        if (request == null) {
            return;
        }
        var metadata = request.metadata;
        var bytes = parsed.imageBytes();
        if (request.generation != observed.generation
                || metadata == null
                || metadata.byteLength() != bytes.length
                || !metadata.contentSha256().equals(RendererProcessProtocol.rawSha256(bytes))) {
            throw new RendererProcessProtocol.ProtocolException(
                    "renderer RESULT image does not match sealed metadata");
        }
        RenderOutput output;
        try {
            output = new RenderOutput(
                    bytes,
                    metadata.contractVersion(),
                    metadata.rendererProfile(),
                    metadata.dslVersion(),
                    metadata.layoutProfile(),
                    metadata.outputProfile(),
                    metadata.format(),
                    metadata.mediaType(),
                    metadata.widthPx(),
                    metadata.heightPx(),
                    metadata.dpi(),
                    metadata.quality(),
                    metadata.byteLength(),
                    metadata.contentSha256());
        } catch (IllegalArgumentException e) {
            throw new RendererProcessProtocol.ProtocolException(
                    "renderer RESULT output is invalid", e);
        }
        complete(parsed.requestId(), request, new WireOutcome.Success(output));
    }

    private void complete(String requestId, PendingRequest request, WireOutcome outcome) {
        pending.remove(requestId, request);
        request.outcome.complete(outcome);
    }

    private void markUnknown(String requestId, PendingRequest request) {
        pending.remove(requestId, request);
        unknownAttempts.put(requestId, new UnknownAttempt(
                request.encoded.commandDigest(), request.command.deadlineAtEpochMilli()));
        request.outcome.complete(new WireOutcome.Unknown());
    }

    private void failSession(Session observed) {
        if (!observed.active.compareAndSet(true, false)) {
            return;
        }
        synchronized (sessionMonitor) {
            if (session == observed) {
                session = null;
            }
        }
        closeQuietly(observed.connection);
        connections.invalidate();
        pending.forEach((requestId, request) -> {
            if (request.generation == observed.generation) {
                markUnknown(requestId, request);
            }
        });
    }

    private static EngineOutcome terminal(ProblemCode code) {
        return new EngineOutcome.TerminalProblem(
                RenderingProblem.of(code, EvaluationStage.ENGINE));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        var observed = session;
        if (observed != null) {
            failSession(observed);
        }
        pending.forEach(this::markUnknown);
        try {
            connections.close();
        } catch (IOException ignored) {
            // Adapter is already fail-closed; close has no product outcome to relax.
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (IOException ignored) {
            // Closing a failed transport cannot create a successful outcome.
        }
    }
}
