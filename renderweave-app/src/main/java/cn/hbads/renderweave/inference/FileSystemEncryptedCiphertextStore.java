package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.InferenceStorageException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Root-confined opaque ciphertext storage; content identity remains authoritative in PostgreSQL. */
final class FileSystemEncryptedCiphertextStore implements EncryptedCiphertextStore {
    private final Path root;

    FileSystemEncryptedCiphertextStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public synchronized boolean write(String locator, byte[] ciphertext, String expectedSha256) {
        validate(locator);
        Objects.requireNonNull(ciphertext, "ciphertext");
        validate(expectedSha256);
        if (!expectedSha256.equals(sha256(ciphertext))) {
            throw problem("STORAGE_CIPHERTEXT_DIGEST_MISMATCH", "Ciphertext digest does not match metadata", null);
        }
        var target = resolve(locator);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                verify(target, expectedSha256);
                return false;
            }
            var temporary = Files.createTempFile(target.getParent(), locator + ".", ".staging");
            try {
                Files.write(temporary, ciphertext);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target);
                } catch (FileAlreadyExistsException raced) {
                    verify(target, expectedSha256);
                    return false;
                }
                return true;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (InferenceStorageException failure) {
            throw failure;
        } catch (IOException failure) {
            throw problem("STORAGE_CIPHERTEXT_WRITE_FAILED", "Ciphertext could not be written", failure);
        }
    }

    @Override
    public synchronized byte[] read(String locator) {
        var target = resolve(locator);
        try {
            return Files.readAllBytes(target);
        } catch (IOException failure) {
            throw problem("STORAGE_CIPHERTEXT_READ_FAILED", "Ciphertext could not be read", failure);
        }
    }

    @Override
    public synchronized void delete(String locator) {
        var target = resolve(locator);
        try {
            Files.deleteIfExists(target);
        } catch (IOException failure) {
            throw problem("STORAGE_CIPHERTEXT_DELETE_FAILED", "Ciphertext could not be deleted", failure);
        }
    }

    @Override
    public synchronized boolean exists(String locator) {
        return Files.exists(resolve(locator));
    }

    Path pathForTesting(String locator) {
        return resolve(locator);
    }

    private Path resolve(String locator) {
        validate(locator);
        var target = root.resolve(locator.substring(0, 2)).resolve(locator + ".ciphertext").normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Ciphertext locator escapes storage root");
        return target;
    }

    private static void verify(Path target, String expectedSha256) throws IOException {
        if (!expectedSha256.equals(sha256(Files.readAllBytes(target)))) {
            throw problem("STORAGE_CIPHERTEXT_CORRUPTED", "Existing ciphertext failed its digest check", null);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }

    private static void validate(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Ciphertext identity must be a SHA-256 hex digest");
        }
    }

    private static InferenceStorageException problem(String code, String message, Throwable cause) {
        return new InferenceStorageException(code, message, cause);
    }
}
