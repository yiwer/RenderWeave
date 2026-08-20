package cn.hbads.renderweave.app.asset;

import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetFetchEndpoint;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetResolutionSecretsTest {

    private static final String BASE64_URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    @Test
    void rejectsNonCanonicalBase64UrlSignatureAlias() throws Exception {
        var secrets = new AssetResolutionSecrets(new byte[32]);
        var request = new AssetFetchEndpoint.IssueRequest(
                "1".repeat(64),
                "2".repeat(64),
                "00000000-0000-4000-8000-000000000001",
                "rwres_" + "3".repeat(64),
                AssetApplication.AssetId.of("00000000-0000-4000-8000-000000000002"),
                0,
                "4".repeat(64),
                1,
                "renderer:v1",
                1_893_456_000L
        );
        String canonical = secrets.sign(request);
        String alias = nonCanonicalAlias(canonical);

        assertArrayEquals(
                Base64.getUrlDecoder().decode(canonical),
                Base64.getUrlDecoder().decode(alias)
        );
        assertTrue(secrets.verifies(request, canonical));
        assertFalse(secrets.verifies(request, alias));
    }

    private static String nonCanonicalAlias(String canonical) {
        int lastIndex = canonical.length() - 1;
        int canonicalIndex = BASE64_URL_ALPHABET.indexOf(canonical.charAt(lastIndex));
        if (canonical.length() != 43 || canonicalIndex < 0 || canonicalIndex % 4 != 0) {
            throw new AssertionError("expected a canonical 43-character Base64URL signature");
        }
        return canonical.substring(0, lastIndex)
                + BASE64_URL_ALPHABET.charAt(canonicalIndex + 1);
    }
}
