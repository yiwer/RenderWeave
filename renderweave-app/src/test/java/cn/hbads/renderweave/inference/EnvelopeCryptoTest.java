package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.InferenceStorageException;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvelopeCryptoTest {

    @Test
    void matchesNistAes256GcmKnownAnswerAndRoundTrips() {
        var key = new SecretKeySpec(new byte[32], "AES");
        var nonce = new byte[12];

        var sealed = EnvelopeCrypto.seal(key, nonce, new byte[0], new byte[0]);

        assertThat(sealed.ciphertext()).isEmpty();
        assertThat(HexFormat.of().formatHex(sealed.tag()))
                .isEqualTo("530f8afbc74536b9a963b4f1c4cb738b");
        assertThat(EnvelopeCrypto.open(
                key, nonce, new byte[0], sealed.ciphertext(), sealed.tag()
        )).isEmpty();
    }

    @Test
    void rejectsTamperedTagAndDifferentAssociatedData() {
        var key = new SecretKeySpec(new byte[32], "AES");
        var nonce = new byte[12];
        var sealed = EnvelopeCrypto.seal(key, nonce, new byte[]{1}, new byte[]{2, 3, 4});
        var tag = sealed.tag();
        tag[0] ^= 1;

        var tagFailure = assertThrows(InferenceStorageException.class, () -> EnvelopeCrypto.open(
                key, nonce, new byte[]{1}, sealed.ciphertext(), tag
        ));
        var aadFailure = assertThrows(InferenceStorageException.class, () -> EnvelopeCrypto.open(
                key, nonce, new byte[]{9}, sealed.ciphertext(), sealed.tag()
        ));

        assertThat(tagFailure.code()).isEqualTo("STORAGE_ARTIFACT_AUTHENTICATION_FAILED");
        assertThat(aadFailure.code()).isEqualTo("STORAGE_ARTIFACT_AUTHENTICATION_FAILED");
    }
}
