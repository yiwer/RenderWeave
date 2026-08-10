package cn.hbads.renderweave.inference;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Clean repository identity excluding only the fixed, tracked authorization ledgers whose
 * PROPOSED/OPEN/CLOSED transitions must not change an evaluation snapshot.
 */
final class VisualEvaluationIdentity {
    static final String VERSION = "renderweave-visual-evaluation-tree-sha256/1";
    private static final List<String> SENSITIVE_ENVIRONMENT_KEYS = List.of(
            "DASHSCOPE_API_KEY",
            "DASHSCOPE_API_KEY_FILE",
            "RENDERWEAVE_RUN_LIVE_CANARY",
            "RENDERWEAVE_RUN_LIVE_CERTIFICATION",
            "RENDERWEAVE_LIVE_CERTIFICATION_AUTHORIZATION",
            "RENDERWEAVE_RUN_VISUAL_EVALUATION",
            "RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION",
            "RENDERWEAVE_LIVE_AI_ENABLED",
            "RENDERWEAVE_LIVE_UPLOAD_ENABLED"
    );

    private final Path repositoryRoot;
    private final Set<String> excludedAuthorizationPaths;

    VisualEvaluationIdentity(Path repositoryRoot, List<Path> authorizationFiles) {
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot")
                .toAbsolutePath().normalize();
        authorizationFiles = List.copyOf(Objects.requireNonNull(authorizationFiles,
                "authorizationFiles"));
        if (authorizationFiles.isEmpty()) {
            throw new IllegalArgumentException("At least one visual authorization ledger is required");
        }
        var excluded = new HashSet<String>();
        for (var file : authorizationFiles) {
            var normalized = Objects.requireNonNull(file, "authorizationFile")
                    .toAbsolutePath().normalize();
            if (!normalized.startsWith(this.repositoryRoot)) {
                throw new IllegalArgumentException("Visual authorization must be inside the repository");
            }
            if (!excluded.add(normalize(this.repositoryRoot.relativize(normalized)))) {
                throw new IllegalArgumentException("Visual authorization ledger paths must be unique");
            }
        }
        excludedAuthorizationPaths = Set.copyOf(excluded);
    }

    String current() {
        var first = captureOnce();
        var second = captureOnce();
        if (!first.equals(second)) {
            throw new IllegalStateException("VISUAL_EVALUATION_IDENTITY_UNSTABLE");
        }
        return first;
    }

    void requireCurrent(String expected) {
        if (!Objects.equals(expected, current())) {
            throw new IllegalStateException("VISUAL_EVALUATION_IDENTITY_MISMATCH");
        }
    }

    private String captureOnce() {
        var tracked = gitPaths("ls-files", "-z", "--cached").stream()
                .sorted(Comparator.naturalOrder()).toList();
        for (var excluded : excludedAuthorizationPaths) {
            var ledger = repositoryRoot.resolve(excluded).normalize();
            if (!tracked.contains(excluded)
                    || !Files.isRegularFile(ledger, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("VISUAL_EVALUATION_AUTHORIZATION_NOT_TRACKED");
            }
        }
        if (!gitPaths("status", "--porcelain=v1", "-z", "--untracked-files=no").isEmpty()) {
            throw new IllegalStateException("VISUAL_EVALUATION_REPOSITORY_HAS_TRACKED_CHANGES");
        }
        if (!gitPaths("ls-files", "-z", "--others", "--exclude-standard").isEmpty()) {
            throw new IllegalStateException("VISUAL_EVALUATION_REPOSITORY_HAS_UNTRACKED_FILES");
        }
        var inputs = tracked.stream().filter(path -> !excludedAuthorizationPaths.contains(path)).toList();
        if (inputs.isEmpty()) throw new IllegalStateException("VISUAL_EVALUATION_IDENTITY_EMPTY");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((VERSION + "\n").getBytes(StandardCharsets.UTF_8));
            for (var relative : inputs) {
                var file = repositoryRoot.resolve(relative).normalize();
                if (!file.startsWith(repositoryRoot)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("VISUAL_EVALUATION_TRACKED_FILE_UNAVAILABLE");
                }
                var path = relative.getBytes(StandardCharsets.UTF_8);
                var content = Files.readAllBytes(file);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(path.length).array());
                digest.update(path);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
                digest.update(content);
            }
            return VERSION + ":" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } catch (IOException failure) {
            throw new IllegalStateException("VISUAL_EVALUATION_IDENTITY_IO_FAILED", failure);
        }
    }

    private List<String> gitPaths(String... arguments) {
        var command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("renderweave-visual-eval-git-", ".out");
            var builder = new ProcessBuilder(command).directory(repositoryRoot.toFile())
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            SENSITIVE_ENVIRONMENT_KEYS.forEach(key -> builder.environment().remove(key));
            builder.environment().put("RENDERWEAVE_LIVE_AI_ENABLED", "false");
            builder.environment().put("RENDERWEAVE_LIVE_UPLOAD_ENABLED", "false");
            process = builder.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("VISUAL_EVALUATION_GIT_IDENTITY_TIMEOUT");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("VISUAL_EVALUATION_GIT_IDENTITY_FAILED");
            }
            return Arrays.stream(Files.readString(output, StandardCharsets.UTF_8).split("\\x00", -1))
                    .filter(value -> !value.isEmpty()).map(VisualEvaluationIdentity::normalize).toList();
        } catch (IOException failure) {
            throw new IllegalStateException("VISUAL_EVALUATION_GIT_IDENTITY_FAILED", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("VISUAL_EVALUATION_GIT_IDENTITY_INTERRUPTED", interrupted);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException cleanupFailure) {
                    output.toFile().deleteOnExit();
                }
            }
        }
    }

    private static String normalize(Path value) { return normalize(value.toString()); }

    private static String normalize(String value) { return value.replace('\\', '/'); }
}
