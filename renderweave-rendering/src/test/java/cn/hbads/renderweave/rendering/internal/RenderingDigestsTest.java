package cn.hbads.renderweave.rendering.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class RenderingDigestsTest {

    // 外部（Python hashlib）计算的冻结 domain 向量，避免同实现重放的 tautology。

    @Test
    void renderDocumentDigestOfEmptyPayloadMatchesFrozenVector() {
        assertEquals(
                "sha256:bb873d12448952b20dc314ae779001d0e27a9f25ecbc4176db55b1b627a6281d",
                RenderingDigests.renderDocumentDigest(new byte[0])
        );
    }

    @Test
    void renderDocumentDigestMatchesFrozenVector() {
        var payload = "{\"dslVersion\":\"renderweave-render/1.0\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "sha256:03fce5ece32e2a331c5c3f19013cfa5dc733c61a7340758030cb9148d00a08f9",
                RenderingDigests.renderDocumentDigest(payload)
        );
    }

    @Test
    void closureDigestOfEmptyManifestMatchesFrozenVector() {
        assertEquals(
                "sha256:fc08b579273fdc6ba9ce092acf17d2321d79f9b0eb8b902aa81385994b091bed",
                RenderingDigests.closureDigest(new byte[0])
        );
    }

    @Test
    void hmacSha256MatchesRfc4231StyleIndependentVector() {
        // key="renderweave-test-key", data="abc" 的 Python hmac 参考值
        var mac = RenderingDigests.hmacSha256(
                "renderweave-test-key".getBytes(StandardCharsets.UTF_8),
                "abc".getBytes(StandardCharsets.UTF_8)
        );
        assertEquals(32, mac.length);
        var expected = new byte[] {
                (byte) 0xd9, (byte) 0xdf, (byte) 0xa5, (byte) 0x49, (byte) 0x5c, (byte) 0x2e, (byte) 0x63, (byte) 0xbe,
                (byte) 0x88, (byte) 0xb3, (byte) 0x36, (byte) 0xa8, (byte) 0x4c, (byte) 0x38, (byte) 0x7e, (byte) 0x47,
                (byte) 0x22, (byte) 0x02, (byte) 0x6a, (byte) 0x65, (byte) 0x08, (byte) 0xd0, (byte) 0x0a, (byte) 0x27,
                (byte) 0xb4, (byte) 0x54, (byte) 0x60, (byte) 0xc7, (byte) 0x85, (byte) 0x57, (byte) 0x0b, (byte) 0x9b
        };
        assertArrayEquals(expected, mac);
    }
}
