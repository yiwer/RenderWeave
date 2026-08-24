package cn.hbads.renderweave.app.rendering;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RendererProcessSupervisorTest {

    private static final String ASSET_FETCH_ORIGIN = "https://render.internal.example";
    private static final List<String> ASSET_FETCH_ALLOWED_IPS =
            List.of("10.20.30.40", "2001:db8::1");

    @TempDir
    Path temporaryDirectory;

    @Test
    void commandLineIsClosedAndAConfiguredPreexistingPathIsNeverRemoved() throws Exception {
        var executable = Files.writeString(
                temporaryDirectory.resolve("renderer-daemon"), "not launched");
        var manifest = temporaryDirectory.resolve("process-manifest.json");
        var manifestBytes = Files.readAllBytes(repositoryFile("renderer/process-manifest.json"));
        Files.write(manifest, manifestBytes);
        var manifestDigest = "sha256:" + RendererProcessProtocol.rawSha256(manifestBytes);
        var socket = temporaryDirectory.resolve("runtime/renderer.sock");
        Files.createDirectories(socket.getParent());
        var sentinel = "must-survive".getBytes(StandardCharsets.UTF_8);
        Files.write(socket, sentinel);

        try (var supervisor = new RendererProcessSupervisor(
                executable.toAbsolutePath(),
                socket.toAbsolutePath(),
                manifest.toAbsolutePath(),
                manifestDigest,
                ASSET_FETCH_ORIGIN,
                ASSET_FETCH_ALLOWED_IPS,
                4096,
                Duration.ofMillis(50),
                Duration.ZERO,
                Clock.systemUTC())) {
            assertEquals(
                    java.util.List.of(
                            executable.toAbsolutePath().toString(),
                            "--socket", socket.toAbsolutePath().toString(),
                            "--manifest", manifest.toAbsolutePath().toString(),
                            "--asset-fetch-origin", ASSET_FETCH_ORIGIN,
                            "--asset-fetch-allowed-ip", "10.20.30.40",
                            "--asset-fetch-allowed-ip", "2001:db8::1",
                            "--max-frame-bytes", "4096"),
                    supervisor.commandLine());
            assertThrows(IOException.class, supervisor::open);
            assertArrayEquals(sentinel, Files.readAllBytes(socket));
        }
    }

    @Test
    void manifestByteDriftFailsBeforeAnyProcessOrSocketMutation() throws Exception {
        var executable = Files.writeString(
                temporaryDirectory.resolve("renderer-daemon"), "not launched");
        var manifest = Files.writeString(
                temporaryDirectory.resolve("process-manifest.json"), "{}");
        var socket = temporaryDirectory.resolve("runtime/renderer.sock");
        try (var supervisor = new RendererProcessSupervisor(
                executable.toAbsolutePath(),
                socket.toAbsolutePath(),
                manifest.toAbsolutePath(),
                "sha256:" + "0".repeat(64),
                ASSET_FETCH_ORIGIN,
                ASSET_FETCH_ALLOWED_IPS,
                4096,
                Duration.ofMillis(50),
                Duration.ZERO,
                Clock.systemUTC())) {
            assertThrows(IOException.class, supervisor::open);
            assertFalse(Files.exists(socket));
        }
    }

    @Test
    void missingSocketParentFailsWithoutCreatingDeploymentDirectories() throws Exception {
        var executable = Files.writeString(
                temporaryDirectory.resolve("renderer-daemon"), "not launched");
        var manifestBytes = Files.readAllBytes(repositoryFile("renderer/process-manifest.json"));
        var manifest = Files.write(
                temporaryDirectory.resolve("process-manifest.json"), manifestBytes);
        var socket = temporaryDirectory.resolve("not-provisioned/renderer.sock");
        try (var supervisor = new RendererProcessSupervisor(
                executable.toAbsolutePath(),
                socket.toAbsolutePath(),
                manifest.toAbsolutePath(),
                "sha256:" + RendererProcessProtocol.rawSha256(manifestBytes),
                ASSET_FETCH_ORIGIN,
                ASSET_FETCH_ALLOWED_IPS,
                4096,
                Duration.ofMillis(50),
                Duration.ZERO,
                Clock.systemUTC())) {
            assertThrows(IOException.class, supervisor::open);
            assertFalse(Files.exists(socket.getParent()));
        }
    }

    @Test
    void blankAssetFetchOriginIsRejectedBeforeLaunch() throws Exception {
        var executable = Files.writeString(
                temporaryDirectory.resolve("renderer-daemon"), "not launched");
        var manifest = Files.writeString(
                temporaryDirectory.resolve("process-manifest.json"), "{}");
        var socket = temporaryDirectory.resolve("runtime/renderer.sock");

        assertThrows(IllegalArgumentException.class, () -> new RendererProcessSupervisor(
                executable.toAbsolutePath(),
                socket.toAbsolutePath(),
                manifest.toAbsolutePath(),
                "sha256:" + "0".repeat(64),
                " ",
                ASSET_FETCH_ALLOWED_IPS,
                4096,
                Duration.ofMillis(50),
                Duration.ZERO,
                Clock.systemUTC()));
    }

    @Test
    void emptyOrDuplicateAssetFetchAllowedIpsAreRejectedBeforeLaunch() throws Exception {
        var executable = Files.writeString(
                temporaryDirectory.resolve("renderer-daemon"), "not launched");
        var manifest = Files.writeString(
                temporaryDirectory.resolve("process-manifest.json"), "{}");
        var socket = temporaryDirectory.resolve("runtime/renderer.sock");

        for (var values : List.of(List.<String>of(), List.of("127.0.0.1", "127.0.0.1"))) {
            assertThrows(IllegalArgumentException.class, () -> new RendererProcessSupervisor(
                    executable.toAbsolutePath(),
                    socket.toAbsolutePath(),
                    manifest.toAbsolutePath(),
                    "sha256:" + "0".repeat(64),
                    ASSET_FETCH_ORIGIN,
                    values,
                    4096,
                    Duration.ofMillis(50),
                    Duration.ZERO,
                    Clock.systemUTC()));
        }
    }

    private static Path repositoryFile(String relative) {
        var cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (var i = 0; i < 5 && cursor != null; i++, cursor = cursor.getParent()) {
            var candidate = cursor.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("repository file not found: " + relative);
    }
}
