package cn.hbads.renderweave.inference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayPublicKeySetTest {
    @TempDir
    Path directory;

    @Test
    void loadsAnExactEd25519RotationSet() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(directory.resolve("gateway-2026-08-a.pem"), pem(pair.getPublic().getEncoded()),
                StandardCharsets.US_ASCII);

        var keys = GatewayPublicKeySet.load(directory);

        assertThat(keys.resolve("gateway-2026-08-a")).contains(pair.getPublic());
        assertThat(keys.resolve("gateway-unknown")).isEmpty();
    }

    @Test
    void emptyOrPrivateMaterialFailsClosed() throws Exception {
        assertThatThrownBy(() -> GatewayPublicKeySet.load(directory))
                .isInstanceOf(IllegalArgumentException.class);
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(directory.resolve("gateway-2026-08-a.pem"), pem(pair.getPrivate().getEncoded()),
                StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> GatewayPublicKeySet.load(directory))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String pem(byte[] encoded) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                + "\n-----END PUBLIC KEY-----\n";
    }
}
