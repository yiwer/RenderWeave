package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.InferenceStorageException;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Arrays;
import java.util.LinkedHashMap;

/** Loads a bounded read-only KEK ring from orchestrator-mounted secret files without exposing bytes. */
final class FileSystemArtifactKekRing {
    private static final int MAXIMUM_KEYS = 8;
    private static final int MAXIMUM_FILE_BYTES = 256;

    private FileSystemArtifactKekRing() { }

    static ArtifactKekRing load(Path directory, String currentKeyId) {
        var keys = new LinkedHashMap<String, byte[]>();
        try {
            ArtifactEnvelope.requireKeyId(currentKeyId);
            var root = directory.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw unavailable(null);
            }
            try (var paths = Files.list(root)) {
                var files = paths.filter(path -> path.getFileName().toString().endsWith(".key"))
                        .sorted().toList();
                if (files.isEmpty() || files.size() > MAXIMUM_KEYS) throw unavailable(null);
                for (var path : files) {
                    if (Files.isSymbolicLink(path)
                            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.size(path) > MAXIMUM_FILE_BYTES) {
                        throw unavailable(null);
                    }
                    var fileName = path.getFileName().toString();
                    var keyId = ArtifactEnvelope.requireKeyId(
                            fileName.substring(0, fileName.length() - ".key".length())
                    );
                    var raw = Files.readAllBytes(path);
                    final byte[] key;
                    try {
                        var encoded = trimAsciiWhitespace(raw);
                        try {
                            key = Base64.getDecoder().decode(encoded);
                        } finally {
                            Arrays.fill(encoded, (byte) 0);
                        }
                    } catch (IllegalArgumentException malformed) {
                        throw unavailable(malformed);
                    } finally {
                        Arrays.fill(raw, (byte) 0);
                    }
                    if (key.length != EnvelopeCrypto.KEY_BYTES || keys.putIfAbsent(keyId, key) != null) {
                        Arrays.fill(key, (byte) 0);
                        throw unavailable(null);
                    }
                }
            }
            return ArtifactKekRing.of(currentKeyId, keys);
        } catch (InferenceStorageException failure) {
            throw failure;
        } catch (Exception failure) {
            throw unavailable(failure);
        } finally {
            keys.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
        }
    }

    private static byte[] trimAsciiWhitespace(byte[] value) {
        var start = 0;
        var end = value.length;
        while (start < end && isAsciiWhitespace(value[start])) start++;
        while (end > start && isAsciiWhitespace(value[end - 1])) end--;
        return Arrays.copyOfRange(value, start, end);
    }

    private static boolean isAsciiWhitespace(byte value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    private static InferenceStorageException unavailable(Throwable cause) {
        return new InferenceStorageException(
                "STORAGE_KEK_RING_UNAVAILABLE",
                "The orchestrator-mounted artifact key ring is unavailable",
                cause
        );
    }
}
