package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.InferenceStorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileSystemArtifactKekRingTest {

    @TempDir
    Path directory;

    @Test
    void loadsBoundedSyntheticKeyMaterialAndSelectsExactCurrentId() throws Exception {
        write("kek-old", (byte) 0x11);
        write("kek-current", (byte) 0x22);

        var ring = FileSystemArtifactKekRing.load(directory, "kek-current");

        assertThat(ring.currentKeyId()).isEqualTo("kek-current");
        assertThat(ring.require("kek-old").getEncoded()).hasSize(32);
        assertThat(ring.require("kek-current").getEncoded()).hasSize(32);
    }

    @Test
    void failsClosedForMalformedMaterialMissingCurrentKeyOrMissingDirectory() throws Exception {
        Files.writeString(directory.resolve("kek-invalid.key"), "not-valid-base64!");
        assertUnavailable(() -> FileSystemArtifactKekRing.load(directory, "kek-invalid"));

        Files.delete(directory.resolve("kek-invalid.key"));
        write("kek-present", (byte) 0x33);
        assertUnavailable(() -> FileSystemArtifactKekRing.load(directory, "kek-absent"));
        assertUnavailable(() -> FileSystemArtifactKekRing.load(
                directory.resolve("does-not-exist"), "kek-present"
        ));
    }

    @Test
    void rejectsMoreThanEightMountedKeys() throws Exception {
        for (var index = 0; index < 9; index++) {
            write("kek-" + index, (byte) index);
        }

        assertUnavailable(() -> FileSystemArtifactKekRing.load(directory, "kek-0"));
    }

    private void write(String keyId, byte value) throws Exception {
        var bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        Files.writeString(directory.resolve(keyId + ".key"), Base64.getEncoder().encodeToString(bytes));
        java.util.Arrays.fill(bytes, (byte) 0);
    }

    private static void assertUnavailable(Runnable operation) {
        var failure = assertThrows(InferenceStorageException.class, operation::run);
        assertThat(failure.code()).isEqualTo("STORAGE_KEK_RING_UNAVAILABLE");
    }
}
