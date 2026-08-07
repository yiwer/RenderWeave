package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceStorageException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Content-addressed, root-confined storage for normalized inference artifacts. */
public final class FileSystemBlobStore implements BlobStore {
    private final Path root;

    public FileSystemBlobStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public synchronized WriteReceipt write(String artifactId, byte[] bytes) {
        validateArtifactId(artifactId);
        Objects.requireNonNull(bytes, "bytes");
        if (!artifactId.equals(sha256(bytes))) {
            throw new InferenceStorageException(
                    "STORAGE_ARTIFACT_DIGEST_MISMATCH",
                    "Artifact bytes do not match their content address",
                    null
            );
        }
        var target = resolve(artifactId);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                verifyExisting(target, artifactId);
                return new WriteReceipt(artifactId, false);
            }
            var temporary = Files.createTempFile(target.getParent(), artifactId + ".", ".staging");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target);
                } catch (FileAlreadyExistsException raced) {
                    verifyExisting(target, artifactId);
                    return new WriteReceipt(artifactId, false);
                }
                return new WriteReceipt(artifactId, true);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (InferenceStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new InferenceStorageException(
                    "STORAGE_BLOB_WRITE_FAILED", "Normalized artifact could not be written", exception
            );
        }
    }

    @Override
    public synchronized byte[] read(String locator) {
        var target = resolve(locator);
        try {
            var bytes = Files.readAllBytes(target);
            if (!locator.equals(sha256(bytes))) {
                throw new InferenceStorageException(
                        "STORAGE_ARTIFACT_CORRUPTED", "Stored artifact failed its digest check", null
                );
            }
            return bytes;
        } catch (InferenceStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new InferenceStorageException(
                    "STORAGE_BLOB_READ_FAILED", "Normalized artifact could not be read", exception
            );
        }
    }

    @Override
    public synchronized void delete(String locator) {
        var target = resolve(locator);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new InferenceStorageException(
                    "STORAGE_BLOB_DELETE_FAILED", "Normalized artifact could not be deleted", exception
            );
        }
    }

    Path pathForTesting(String artifactId) {
        return resolve(artifactId);
    }

    private Path resolve(String artifactId) {
        validateArtifactId(artifactId);
        var target = root.resolve(artifactId.substring(0, 2)).resolve(artifactId + ".blob").normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Artifact locator escapes storage root");
        return target;
    }

    private static void verifyExisting(Path target, String artifactId) throws IOException {
        if (!artifactId.equals(sha256(Files.readAllBytes(target)))) {
            throw new InferenceStorageException(
                    "STORAGE_ARTIFACT_CORRUPTED", "Existing artifact failed its digest check", null
            );
        }
    }

    private static void validateArtifactId(String artifactId) {
        if (artifactId == null || !artifactId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Artifact locator must be a SHA-256 hex digest");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }
}
