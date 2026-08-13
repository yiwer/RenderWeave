package cn.hbads.renderweave.inference;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
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
    static final String LEGACY_VERSION = "renderweave-visual-evaluation-tree-sha256/1";
    static final String VERSION = "renderweave-visual-evaluation-tree-sha256/2";
    private static final Set<String> SUPPORTED_VERSIONS = Set.of(LEGACY_VERSION, VERSION);
    private static final Set<String> REGULAR_GIT_MODES = Set.of("100644", "100755");
    private static final List<String> SENSITIVE_ENVIRONMENT_KEYS = List.of(
            "DASHSCOPE_TOKEN_API_KEY",
            "DASHSCOPE_TOKEN_API_KEY_FILE",
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
        return current(VERSION);
    }

    String current(String version) {
        if (!SUPPORTED_VERSIONS.contains(version)) {
            throw new IllegalArgumentException("Visual evaluation identity version is unsupported");
        }
        var first = captureOnce(version);
        var second = captureOnce(version);
        if (!first.equals(second)) {
            throw new IllegalStateException("VISUAL_EVALUATION_IDENTITY_UNSTABLE");
        }
        return first;
    }

    void requireCurrent(String expected) {
        var version = SUPPORTED_VERSIONS.stream()
                .filter(item -> expected != null && expected.startsWith(item + ":"))
                .findFirst().orElse(null);
        if (version == null || !Objects.equals(expected, current(version))) {
            throw new IllegalStateException("VISUAL_EVALUATION_IDENTITY_MISMATCH");
        }
    }

    private String captureOnce(String version) {
        return LEGACY_VERSION.equals(version) ? captureLegacyOnce() : captureCanonicalOnce();
    }

    /** Historical /1 algorithm. Never use it to mint a new ledger. */
    private String captureLegacyOnce() {
        var tracked = gitPaths("ls-files", "-z", "--cached").stream()
                .sorted(Comparator.naturalOrder()).toList();
        requireTrackedLedgers(tracked);
        requireCleanRepository();
        var inputs = tracked.stream().filter(path -> !excludedAuthorizationPaths.contains(path)).toList();
        if (inputs.isEmpty()) throw new IllegalStateException("VISUAL_EVALUATION_IDENTITY_EMPTY");
        try {
            var digest = sha256();
            digest.update((LEGACY_VERSION + "\n").getBytes(StandardCharsets.UTF_8));
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
            return LEGACY_VERSION + ":" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException failure) {
            throw new IllegalStateException("VISUAL_EVALUATION_IDENTITY_IO_FAILED", failure);
        }
    }

    /** /2 hashes Git's canonical blob bytes and regular-file mode, never checkout-transformed bytes. */
    private String captureCanonicalOnce() {
        var entries = gitIndexEntries();
        requireVisibleIndex(entries);
        var byPath = new HashMap<String, GitIndexEntry>();
        for (var entry : entries) byPath.put(entry.path(), entry);
        for (var excluded : excludedAuthorizationPaths) {
            var entry = byPath.get(excluded);
            var ledger = repositoryRoot.resolve(excluded).normalize();
            if (entry == null || !REGULAR_GIT_MODES.contains(entry.mode())
                    || !Files.isRegularFile(ledger, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("VISUAL_EVALUATION_AUTHORIZATION_NOT_TRACKED");
            }
        }
        requireCleanRepository();
        var inputs = entries.stream()
                .filter(entry -> !excludedAuthorizationPaths.contains(entry.path())).toList();
        if (inputs.isEmpty()) throw new IllegalStateException("VISUAL_EVALUATION_IDENTITY_EMPTY");
        if (inputs.stream().anyMatch(entry -> !REGULAR_GIT_MODES.contains(entry.mode()))) {
            throw new IllegalStateException("VISUAL_EVALUATION_TRACKED_FILE_NOT_REGULAR");
        }
        if (inputs.stream().map(entry -> repositoryRoot.resolve(entry.path()).normalize())
                .anyMatch(file -> !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))) {
            throw new IllegalStateException("VISUAL_EVALUATION_TRACKED_FILE_UNAVAILABLE");
        }
        try {
            var digest = sha256();
            digest.update((VERSION + "\n").getBytes(StandardCharsets.UTF_8));
            digestCanonicalBlobs(digest, inputs);
            return VERSION + ":" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("VISUAL_EVALUATION_IDENTITY_IO_FAILED", failure);
        }
    }

    private void digestCanonicalBlobs(MessageDigest digest, List<GitIndexEntry> entries)
            throws IOException {
        var query = new StringBuilder();
        entries.forEach(entry -> query.append(entry.objectId()).append('\n'));
        var output = gitOutput(query.toString().getBytes(StandardCharsets.US_ASCII),
                "cat-file", "--batch");
        var stream = new ByteArrayInputStream(output);
        for (var entry : entries) {
            var header = readAsciiLine(stream).split(" ", -1);
            if (header.length != 3 || !entry.objectId().equals(header[0]) || !"blob".equals(header[1])) {
                throw new IllegalStateException("VISUAL_EVALUATION_GIT_BLOB_INVALID");
            }
            final int size;
            try {
                var parsed = Long.parseLong(header[2]);
                if (parsed < 0 || parsed > Integer.MAX_VALUE) throw new NumberFormatException();
                size = (int) parsed;
            } catch (NumberFormatException invalid) {
                throw new IllegalStateException("VISUAL_EVALUATION_GIT_BLOB_INVALID", invalid);
            }
            var content = stream.readNBytes(size);
            if (content.length != size || stream.read() != '\n') {
                throw new IllegalStateException("VISUAL_EVALUATION_GIT_BLOB_INVALID");
            }
            var path = entry.pathBytes();
            var mode = entry.mode().getBytes(StandardCharsets.US_ASCII);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(path.length).array());
            digest.update(path);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(mode.length).array());
            digest.update(mode);
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
            digest.update(content);
        }
        if (stream.read() != -1) {
            throw new IllegalStateException("VISUAL_EVALUATION_GIT_BLOB_INVALID");
        }
    }

    private List<GitIndexEntry> gitIndexEntries() {
        var output = gitOutput(null, "ls-files", "--stage", "-z", "--cached");
        var entries = new ArrayList<GitIndexEntry>();
        var paths = new HashSet<String>();
        for (var record : splitNull(output)) {
            var tab = indexOf(record, (byte) '\t');
            if (tab < 0) throw new IllegalStateException("VISUAL_EVALUATION_GIT_INDEX_INVALID");
            var metadata = decodeUtf8(Arrays.copyOfRange(record, 0, tab)).split(" ", -1);
            var pathBytes = Arrays.copyOfRange(record, tab + 1, record.length);
            var path = decodeUtf8(pathBytes);
            if (metadata.length != 3 || !"0".equals(metadata[2])
                    || (!REGULAR_GIT_MODES.contains(metadata[0])
                    && !Set.of("120000", "160000").contains(metadata[0]))
                    || !metadata[1].matches("[0-9a-f]{40}|[0-9a-f]{64}")
                    || path.isEmpty() || path.indexOf('\\') >= 0
                    || !repositoryRoot.resolve(path).normalize().startsWith(repositoryRoot)
                    || !paths.add(path)) {
                throw new IllegalStateException("VISUAL_EVALUATION_GIT_INDEX_INVALID");
            }
            entries.add(new GitIndexEntry(metadata[0], metadata[1], path, pathBytes));
        }
        entries.sort((left, right) -> compareUnsigned(left.pathBytes(), right.pathBytes()));
        return List.copyOf(entries);
    }

    private void requireVisibleIndex(List<GitIndexEntry> entries) {
        var expected = new HashSet<>(entries.stream().map(GitIndexEntry::path).toList());
        for (var tagged : gitPaths("ls-files", "-v", "-z", "--cached")) {
            if (!tagged.startsWith("H ") || !expected.remove(tagged.substring(2))) {
                throw new IllegalStateException("VISUAL_EVALUATION_GIT_INDEX_HIDDEN");
            }
        }
        if (!expected.isEmpty()) {
            throw new IllegalStateException("VISUAL_EVALUATION_GIT_INDEX_HIDDEN");
        }
    }

    private void requireTrackedLedgers(List<String> tracked) {
        for (var excluded : excludedAuthorizationPaths) {
            var ledger = repositoryRoot.resolve(excluded).normalize();
            if (!tracked.contains(excluded)
                    || !Files.isRegularFile(ledger, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("VISUAL_EVALUATION_AUTHORIZATION_NOT_TRACKED");
            }
        }
    }

    private void requireCleanRepository() {
        if (!gitPaths("status", "--porcelain=v1", "-z", "--untracked-files=no").isEmpty()) {
            throw new IllegalStateException("VISUAL_EVALUATION_REPOSITORY_HAS_TRACKED_CHANGES");
        }
        if (!gitPaths("ls-files", "-z", "--others", "--exclude-standard").isEmpty()) {
            throw new IllegalStateException("VISUAL_EVALUATION_REPOSITORY_HAS_UNTRACKED_FILES");
        }
    }

    private List<String> gitPaths(String... arguments) {
        return splitNull(gitOutput(null, arguments)).stream()
                .map(VisualEvaluationIdentity::decodeUtf8)
                .map(VisualEvaluationIdentity::normalize).toList();
    }

    private byte[] gitOutput(byte[] input, String... arguments) {
        var command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Path inputFile = null;
        Path outputFile = null;
        Path errorFile = null;
        Process process = null;
        try {
            outputFile = Files.createTempFile("renderweave-visual-eval-git-", ".out");
            errorFile = Files.createTempFile("renderweave-visual-eval-git-", ".err");
            var builder = new ProcessBuilder(command).directory(repositoryRoot.toFile())
                    .redirectOutput(outputFile.toFile()).redirectError(errorFile.toFile());
            if (input != null) {
                inputFile = Files.createTempFile("renderweave-visual-eval-git-", ".in");
                Files.write(inputFile, input);
                builder.redirectInput(inputFile.toFile());
            }
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
            return Files.readAllBytes(outputFile);
        } catch (IOException failure) {
            throw new IllegalStateException("VISUAL_EVALUATION_GIT_IDENTITY_FAILED", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("VISUAL_EVALUATION_GIT_IDENTITY_INTERRUPTED", interrupted);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            deleteTemporary(inputFile);
            deleteTemporary(outputFile);
            deleteTemporary(errorFile);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String readAsciiLine(ByteArrayInputStream stream) {
        var result = new StringBuilder();
        int value;
        while ((value = stream.read()) >= 0 && value != '\n') {
            if (value > 0x7f) throw new IllegalStateException("VISUAL_EVALUATION_GIT_BLOB_INVALID");
            result.append((char) value);
        }
        if (value != '\n') throw new IllegalStateException("VISUAL_EVALUATION_GIT_BLOB_INVALID");
        return result.toString();
    }

    private static List<byte[]> splitNull(byte[] value) {
        var result = new ArrayList<byte[]>();
        var start = 0;
        for (var index = 0; index < value.length; index++) {
            if (value[index] == 0) {
                if (index > start) result.add(Arrays.copyOfRange(value, start, index));
                start = index + 1;
            }
        }
        if (start < value.length) result.add(Arrays.copyOfRange(value, start, value.length));
        return List.copyOf(result);
    }

    private static int indexOf(byte[] value, byte needle) {
        for (var index = 0; index < value.length; index++) if (value[index] == needle) return index;
        return -1;
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        for (var index = 0; index < Math.min(left.length, right.length); index++) {
            var compared = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (compared != 0) return compared;
        }
        return Integer.compare(left.length, right.length);
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalStateException("VISUAL_EVALUATION_GIT_PATH_INVALID", invalid);
        }
    }

    private static void deleteTemporary(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            path.toFile().deleteOnExit();
        }
    }

    private static String normalize(Path value) { return normalize(value.toString()); }

    private static String normalize(String value) { return value.replace('\\', '/'); }

    private record GitIndexEntry(String mode, String objectId, String path, byte[] pathBytes) { }
}
