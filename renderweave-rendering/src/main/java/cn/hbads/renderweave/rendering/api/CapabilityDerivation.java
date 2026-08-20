package cn.hbads.renderweave.rendering.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * Evaluation Capability 的 exact 派生合同（冻结票据 14 §59）：
 * {@code HMAC-SHA-256(key=nonce, data=UTF8(domain) || uint64be(|pos|) || pos ||
 * uint32be(counter))}，digest 解释为 unsigned big-endian 256-bit {@code x}；
 * {@code M=10^18}、{@code limit=floor(2^256/M)×M}；{@code x < limit} 取
 * {@code k = x mod M}，否则 counter+1；128 次全部拒绝返回 {@code null}
 * （对应 CAPABILITY_RESULT_INVALID）。纯函数，无状态；app capability runtime 与
 * Rendering 内部求值共用同一实现，保证派生单点。
 */
public final class CapabilityDerivation {

    public static final String RANDOM_UNIFORM_DOMAIN =
            "renderweave-capability-random-uniform-decimal/1\0";
    public static final int MAX_REJECTION_ATTEMPTS = 128;

    private static final BigInteger M = BigInteger.TEN.pow(18);
    private static final BigInteger LIMIT = BigInteger.TWO.pow(256).divide(M).multiply(M);

    private CapabilityDerivation() {
    }

    /**
     * 从 server-only 256-bit nonce 与 demand 位置派生 {@code [0,1)} 均匀 decimal
     * （k/10^18）；128 次 rejection 后返回 {@code null}。
     */
    public static BigDecimal uniformDecimal(byte[] nonce256, byte[] positionBytes) {
        Objects.requireNonNull(nonce256, "nonce256");
        Objects.requireNonNull(positionBytes, "positionBytes");
        if (nonce256.length != 32) {
            throw new IllegalArgumentException("nonce must be 256-bit");
        }
        var domain = RANDOM_UNIFORM_DOMAIN.getBytes(StandardCharsets.UTF_8);
        var lengthPrefix = BigInteger.valueOf(positionBytes.length).toByteArray();
        var lengthBytes = new byte[8];
        System.arraycopy(lengthPrefix, Math.max(0, lengthPrefix.length - 8), lengthBytes,
                8 - Math.min(8, lengthPrefix.length), Math.min(8, lengthPrefix.length));
        for (int counter = 0; counter < MAX_REJECTION_ATTEMPTS; counter++) {
            var counterBytes = new byte[] {
                    0, 0, (byte) (counter >>> 8), (byte) counter
            };
            var mac = hmacSha256(nonce256, domain, lengthBytes, positionBytes, counterBytes);
            var x = new BigInteger(1, mac);
            if (x.compareTo(LIMIT) < 0) {
                return new BigDecimal(x.mod(M), 18);
            }
        }
        return null;
    }

    private static byte[] hmacSha256(byte[] key, byte[]... parts) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            for (var part : parts) {
                mac.update(part);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 must be available", e);
        }
    }
}
