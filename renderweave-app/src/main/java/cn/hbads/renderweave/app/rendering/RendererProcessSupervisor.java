package cn.hbads.renderweave.app.rendering;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Owns one renderer daemon process and its production Unix-domain connection.
 *
 * <p>The supervisor never invokes a shell, clears inherited environment variables, passes only
 * closed arguments, requires a pre-provisioned owner-only socket directory, refuses a pre-existing
 * socket path, and removes only a socket node created after its own child launch. Restart backoff
 * and all timeouts are explicit deployment inputs.
 */
final class RendererProcessSupervisor implements RendererProcessAdapter.ConnectionFactory {

    private final Path executable;
    private final Path socketPath;
    private final Path manifestPath;
    private final String expectedManifestSha256;
    private final int maximumFramedBytes;
    private final Duration startupTimeout;
    private final Duration restartBackoff;
    private final Clock clock;
    private Process process;
    private boolean socketOwned;
    private boolean closed;
    private long nextStartEpochMilli;

    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    RendererProcessSupervisor(
            Path executable,
            Path socketPath,
            Path manifestPath,
            String expectedManifestSha256,
            int maximumFramedBytes,
            Duration startupTimeout,
            Duration restartBackoff,
            Clock clock
    ) {
        this.executable = requireAbsolute(executable, "renderer executable");
        this.socketPath = requireSafeSocketPath(socketPath);
        this.manifestPath = requireAbsolute(manifestPath, "renderer manifest");
        RendererProcessProtocol.requireSha256(
                expectedManifestSha256, "expectedManifestSha256");
        if (maximumFramedBytes < 1) {
            throw new IllegalArgumentException("maximumFramedBytes must be positive");
        }
        this.startupTimeout = requirePositive(startupTimeout, "startupTimeout");
        this.restartBackoff = requireNonNegative(restartBackoff, "restartBackoff");
        this.expectedManifestSha256 = expectedManifestSha256;
        this.maximumFramedBytes = maximumFramedBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized RendererProcessAdapter.Connection open() throws IOException {
        if (closed) {
            throw new IOException("renderer supervisor is closed");
        }
        if (process != null && !process.isAlive()) {
            stopOwnedProcess();
        }
        if (process != null) {
            return connect();
        }
        if (clock.millis() < nextStartEpochMilli) {
            throw new IOException("renderer restart backoff is active");
        }
        try {
            verifyInputs();
            verifySocketParent();
            if (Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("renderer socket path already exists");
            }
            var builder = new ProcessBuilder(commandLine());
            builder.environment().clear();
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            socketOwned = true;
            var deadline = Math.addExact(clock.millis(), startupTimeout.toMillis());
            IOException lastConnect = null;
            while (clock.millis() < deadline) {
                if (!process.isAlive()) {
                    throw new IOException("renderer daemon exited during startup");
                }
                try {
                    return connect();
                } catch (IOException e) {
                    lastConnect = e;
                    try {
                        Thread.sleep(Math.min(10, Math.max(1, deadline - clock.millis())));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("renderer startup interrupted", interrupted);
                    }
                }
            }
            throw new IOException("renderer UDS did not become ready before startup timeout",
                    lastConnect);
        } catch (ArithmeticException e) {
            invalidate();
            throw new IOException("renderer startup deadline overflow", e);
        } catch (IOException e) {
            invalidate();
            throw e;
        }
    }

    List<String> commandLine() {
        return List.of(
                executable.toString(),
                "--socket", socketPath.toString(),
                "--manifest", manifestPath.toString(),
                "--max-frame-bytes", Integer.toString(maximumFramedBytes));
    }

    private void verifyInputs() throws IOException {
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("renderer executable must be an exact regular file");
        }
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("renderer manifest must be an exact regular file");
        }
        var actualManifest = "sha256:"
                + RendererProcessProtocol.rawSha256(Files.readAllBytes(manifestPath));
        if (!actualManifest.equals(expectedManifestSha256)) {
            throw new IOException("renderer manifest bytes do not match configured identity");
        }
    }

    private void verifySocketParent() throws IOException {
        var parent = socketPath.getParent();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("renderer socket parent must be a pre-provisioned directory");
        }
        if (!parent.toRealPath().equals(parent)) {
            throw new IOException("renderer socket parent must not traverse symbolic links");
        }
        var fileStore = Files.getFileStore(parent);
        if (!fileStore.supportsFileAttributeView("posix")) {
            throw new IOException("renderer socket parent requires Linux POSIX permissions");
        }
        var permissions = Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS);
        if (!permissions.equals(PRIVATE_DIRECTORY_PERMISSIONS)) {
            throw new IOException("renderer socket parent permissions must be exactly 0700");
        }
    }

    private RendererProcessAdapter.Connection connect() throws IOException {
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            return new ChannelConnection(channel);
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // The original connect failure remains authoritative.
            }
            if (e instanceof IOException io) {
                throw io;
            }
            throw new IOException("renderer Unix-domain transport is unavailable", e);
        }
    }

    @Override
    public synchronized void invalidate() {
        stopOwnedProcess();
        nextStartEpochMilli = saturatingAdd(clock.millis(), restartBackoff.toMillis());
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        stopOwnedProcess();
    }

    private void stopOwnedProcess() {
        var observed = process;
        process = null;
        if (observed != null && observed.isAlive()) {
            observed.destroy();
            try {
                if (!observed.waitFor(startupTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    observed.destroyForcibly();
                    observed.waitFor(startupTimeout.toMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                observed.destroyForcibly();
            }
        }
        removeOwnedSocketNode();
    }

    private void removeOwnedSocketNode() {
        if (!socketOwned) {
            return;
        }
        socketOwned = false;
        try {
            if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    socketPath,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isOther()
                    && !attributes.isDirectory()
                    && !attributes.isRegularFile()
                    && !attributes.isSymbolicLink()) {
                Files.deleteIfExists(socketPath);
            }
        } catch (IOException ignored) {
            // Never widen cleanup to a parent path or a different filesystem object.
        }
    }

    private static Path requireAbsolute(Path value, String name) {
        Objects.requireNonNull(value, name);
        var normalized = value.normalize();
        if (!normalized.isAbsolute()) {
            throw new IllegalArgumentException(name + " must be an absolute path");
        }
        return normalized;
    }

    private static Path requireSafeSocketPath(Path value) {
        var normalized = requireAbsolute(value, "renderer socket");
        if (normalized.getParent() == null || normalized.equals(normalized.getRoot())) {
            throw new IllegalArgumentException("renderer socket must have a narrow parent directory");
        }
        return normalized;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static final class ChannelConnection implements RendererProcessAdapter.Connection {
        private final SocketChannel channel;
        private final InputStream input;
        private final OutputStream output;

        ChannelConnection(SocketChannel channel) {
            this.channel = channel;
            this.input = Channels.newInputStream(channel);
            this.output = Channels.newOutputStream(channel);
        }

        @Override
        public InputStream input() {
            return input;
        }

        @Override
        public OutputStream output() {
            return output;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
