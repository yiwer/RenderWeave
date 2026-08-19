package cn.hbads.renderweave.asset.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Fixed canonical sRGB ICC equality policy for the acceptance kernel: only an embedded
 * ICC profile whose bytes are byte-identical to the frozen sRGB IEC 61966-2.1 profile is
 * admitted; absent profile and explicit sRGB declaration remain the other legal cases.
 */
final class IccPolicy {

    static final String CANONICAL_SRGB_SHA256 =
            "2b3aa1645779a9e634744faf9b01e9102b0c9b88fd6deced7934df86b949af7e";

    private static final String RESOURCE =
            "/cn/hbads/renderweave/asset/acceptance/sRGB-IEC61966-2.1.icc";

    private static final byte[] CANONICAL = loadCanonical();

    private IccPolicy() {
    }

    static boolean isCanonicalSrgb(byte[] profileBytes) {
        return profileBytes != null && Arrays.equals(CANONICAL, profileBytes);
    }

    private static byte[] loadCanonical() {
        try (InputStream stream = IccPolicy.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing canonical sRGB ICC resource " + RESOURCE);
            }
            var out = new ByteArrayOutputStream(4096);
            stream.transferTo(out);
            byte[] bytes = out.toByteArray();
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
            if (!CANONICAL_SRGB_SHA256.equals(actual)) {
                throw new IllegalStateException("canonical sRGB ICC resource sha256 drift: " + actual);
            }
            return bytes;
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
