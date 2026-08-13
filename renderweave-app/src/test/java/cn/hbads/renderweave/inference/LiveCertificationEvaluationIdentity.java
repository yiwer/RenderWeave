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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Reproducible identity of the complete repository input to a live certification run.
 * The human authorization file is excluded so it can move PROPOSED -> OPEN -> CLOSED;
 * every other tracked file is bound and any untracked file fails closed.
 */
final class LiveCertificationEvaluationIdentity {
    static final String VERSION = "renderweave-repository-tree-sha256/1";
    private static final List<String> PAID_ENVIRONMENT_KEYS = List.of(
            "DASHSCOPE_TOKEN_API_KEY",
            "DASHSCOPE_TOKEN_API_KEY_FILE",
            "DASHSCOPE_API_KEY",
            "DASHSCOPE_API_KEY_FILE",
            "RENDERWEAVE_RUN_LIVE_CANARY",
            "RENDERWEAVE_RUN_LIVE_CERTIFICATION",
            LiveCertificationAuthorizationLocator.SELECTOR_ENVIRONMENT_VARIABLE,
            "RENDERWEAVE_LIVE_AI_ENABLED",
            "RENDERWEAVE_LIVE_UPLOAD_ENABLED"
    );

    private final Path repositoryRoot;
    private final String excludedAuthorizationPath;

    LiveCertificationEvaluationIdentity(Path repositoryRoot, Path authorizationFile) {
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot")
                .toAbsolutePath().normalize();
        var excluded = Objects.requireNonNull(authorizationFile, "authorizationFile")
                .toAbsolutePath().normalize();
        if (!excluded.startsWith(this.repositoryRoot)) {
            throw new IllegalArgumentException("Certification authorization must be inside the repository");
        }
        excludedAuthorizationPath = normalize(this.repositoryRoot.relativize(excluded));
    }

    String current() {
        var first = captureOnce();
        var second = captureOnce();
        if (!first.equals(second)) {
            throw new IllegalStateException("LIVE_CERTIFICATION_EVALUATION_IDENTITY_UNSTABLE");
        }
        return first;
    }

    void requireCurrent(String expectedIdentity) {
        if (!Objects.equals(expectedIdentity, current())) {
            throw new IllegalStateException("LIVE_CERTIFICATION_EVALUATION_IDENTITY_MISMATCH");
        }
    }

    private String captureOnce() {
        var tracked = gitPaths("ls-files", "-z", "--cached").stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        var authorizationFile = repositoryRoot.resolve(excludedAuthorizationPath).normalize();
        if (!tracked.contains(excludedAuthorizationPath)
                || !Files.isRegularFile(authorizationFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("LIVE_CERTIFICATION_AUTHORIZATION_NOT_TRACKED");
        }
        var untracked = gitPaths("ls-files", "-z", "--others", "--exclude-standard").stream()
                .toList();
        if (!untracked.isEmpty()) {
            throw new IllegalStateException("LIVE_CERTIFICATION_REPOSITORY_HAS_UNTRACKED_FILES");
        }
        var evaluationInputs = tracked.stream()
                .filter(path -> !path.equals(excludedAuthorizationPath))
                .toList();
        if (evaluationInputs.isEmpty()) {
            throw new IllegalStateException("LIVE_CERTIFICATION_REPOSITORY_IDENTITY_EMPTY");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((VERSION + "\n").getBytes(StandardCharsets.UTF_8));
            for (var relative : evaluationInputs) {
                var file = repositoryRoot.resolve(relative).normalize();
                if (!file.startsWith(repositoryRoot) || !Files.isRegularFile(file)) {
                    throw new IllegalStateException("LIVE_CERTIFICATION_TRACKED_FILE_UNAVAILABLE");
                }
                var pathBytes = relative.getBytes(StandardCharsets.UTF_8);
                var content = Files.readAllBytes(file);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(pathBytes.length).array());
                digest.update(pathBytes);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
                digest.update(content);
            }
            return VERSION + ":" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } catch (IOException failure) {
            throw new IllegalStateException("LIVE_CERTIFICATION_EVALUATION_IDENTITY_IO_FAILED", failure);
        }
    }

    private List<String> gitPaths(String... arguments) {
        var command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Path outputFile = null;
        Process process = null;
        try {
            outputFile = Files.createTempFile("renderweave-certification-git-", ".out");
            var builder = new ProcessBuilder(command)
                    .directory(repositoryRoot.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile());
            PAID_ENVIRONMENT_KEYS.forEach(key -> builder.environment().remove(key));
            builder.environment().put("RENDERWEAVE_LIVE_AI_ENABLED", "false");
            builder.environment().put("RENDERWEAVE_LIVE_UPLOAD_ENABLED", "false");
            process = builder.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("LIVE_CERTIFICATION_GIT_IDENTITY_TIMEOUT");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("LIVE_CERTIFICATION_GIT_IDENTITY_FAILED");
            }
            var output = Files.readAllBytes(outputFile);
            return Arrays.stream(new String(output, StandardCharsets.UTF_8).split("\\x00", -1))
                    .filter(value -> !value.isEmpty())
                    .map(LiveCertificationEvaluationIdentity::normalize)
                    .toList();
        } catch (IOException failure) {
            throw new IllegalStateException("LIVE_CERTIFICATION_GIT_IDENTITY_FAILED", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LIVE_CERTIFICATION_GIT_IDENTITY_INTERRUPTED", interrupted);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (outputFile != null) {
                try {
                    Files.deleteIfExists(outputFile);
                } catch (IOException cleanupFailure) {
                    outputFile.toFile().deleteOnExit();
                }
            }
        }
    }

    private static String normalize(Path path) {
        return normalize(path.toString());
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }
}
