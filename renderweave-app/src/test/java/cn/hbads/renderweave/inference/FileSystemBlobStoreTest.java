package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.InferenceStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemBlobStoreTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void writesAtomicallyReadsWithIntegrityAndIsIdempotent() {
        var store = new FileSystemBlobStore(temporaryDirectory);
        var bytes = "normalized-only".getBytes(StandardCharsets.UTF_8);
        var artifactId = sha256(bytes);

        var first = store.write(artifactId, bytes);
        var replay = store.write(artifactId, bytes);

        assertThat(first.locator()).isEqualTo(artifactId);
        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(store.read(artifactId)).isEqualTo(bytes);
        assertThat(Files.exists(store.pathForTesting(artifactId))).isTrue();

        store.delete(artifactId);
        store.delete(artifactId);
        assertThat(Files.exists(store.pathForTesting(artifactId))).isFalse();
    }

    @Test
    void refusesDigestMismatchTraversalAndCorruptedExistingContent() throws Exception {
        var store = new FileSystemBlobStore(temporaryDirectory);
        var bytes = "expected".getBytes(StandardCharsets.UTF_8);
        var artifactId = sha256(bytes);

        assertThatThrownBy(() -> store.write(artifactId, "different".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InferenceStorageException.class)
                .satisfies(error -> assertThat(((InferenceStorageException) error).code())
                        .isEqualTo("STORAGE_ARTIFACT_DIGEST_MISMATCH"));
        assertThatThrownBy(() -> store.read("../outside"))
                .isInstanceOf(IllegalArgumentException.class);

        store.write(artifactId, bytes);
        Files.writeString(store.pathForTesting(artifactId), "corrupted", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store.read(artifactId))
                .isInstanceOf(InferenceStorageException.class)
                .satisfies(error -> assertThat(((InferenceStorageException) error).code())
                        .isEqualTo("STORAGE_ARTIFACT_CORRUPTED"));
        assertThatThrownBy(() -> store.write(artifactId, bytes))
                .isInstanceOf(InferenceStorageException.class)
                .satisfies(error -> assertThat(((InferenceStorageException) error).code())
                        .isEqualTo("STORAGE_ARTIFACT_CORRUPTED"));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
