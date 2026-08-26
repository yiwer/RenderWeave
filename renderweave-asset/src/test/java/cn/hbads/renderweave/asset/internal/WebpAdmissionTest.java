package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FailureCode;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FailureStage;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Orientation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebpAdmissionTest {

    private static final AssetAcceptanceAuthority AUTHORITY = new CanonicalAssetAcceptanceAuthority();

    private static byte[] fixture(String name) {
        try (var stream = WebpAdmissionTest.class.getResourceAsStream("/asset-fixtures/" + name)) {
            assertTrue(stream != null, "missing " + name + " fixture");
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void admitsLossyWebpWithExactDescriptor() {
        byte[] raw = fixture("lossy.webp");
        var outcome = AUTHORITY.admit(raw, AssetKind.IMAGE);
        var admitted = assertInstanceOf(AssetAcceptanceAuthority.Admitted.class, outcome);
        assertEquals(AssetKind.IMAGE, admitted.kind());
        assertEquals(raw.length, admitted.byteLength());
        assertEquals(sha256Hex(raw), admitted.sha256());
        var descriptor = assertInstanceOf(
                AssetAcceptanceAuthority.ImageDescriptor.class, admitted.descriptor()
        );
        assertEquals(2, descriptor.encodedWidthPx());
        assertEquals(3, descriptor.encodedHeightPx());
        assertEquals(Orientation.IDENTITY, descriptor.orientation());
        assertEquals(2, descriptor.logicalWidthPx());
        assertEquals(3, descriptor.logicalHeightPx());
        assertEquals(1, descriptor.frameCount());
    }

    @Test
    void admitsLosslessWebp() {
        var outcome = AUTHORITY.admit(fixture("lossless.webp"), AssetKind.IMAGE);
        var admitted = assertInstanceOf(AssetAcceptanceAuthority.Admitted.class, outcome);
        var descriptor = assertInstanceOf(
                AssetAcceptanceAuthority.ImageDescriptor.class, admitted.descriptor()
        );
        assertEquals(2, descriptor.encodedWidthPx());
        assertEquals(3, descriptor.encodedHeightPx());
    }

    @Test
    void rejectsAnimatedWebpAsUnsupported() {
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(fixture("animated.webp"), AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_UNSUPPORTED, rejected.code());
        assertEquals(FailureStage.ASSET_STRUCTURE, rejected.stage());
    }

    @Test
    void rejectsIccCarryingWebpAsUnsupported() {
        byte[] raw = insertChunk(fixture("lossy.webp"), "ICCP", new byte[]{0x01, 0x02, 0x03});
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(raw, AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_UNSUPPORTED, rejected.code());
        assertEquals(FailureStage.ASSET_DESCRIPTOR, rejected.stage());
        assertEquals("/ICCP", rejected.pointer());
    }

    @Test
    void rejectsUnknownFourCc() {
        byte[] raw = insertChunk(fixture("lossy.webp"), "JUNK", new byte[]{0x00, 0x00});
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(raw, AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_INVALID, rejected.code());
        assertEquals("/JUNK", rejected.pointer());
    }

    @Test
    void rejectsNonWebpRiffMagic() {
        byte[] raw = fixture("lossy.webp");
        raw[11] = 'C';
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(raw, AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_INVALID, rejected.code());
        assertEquals("/", rejected.pointer());
    }

    @Test
    void rejectsMismatchedRiffSize() {
        byte[] raw = fixture("lossy.webp");
        raw[4] += 1;
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(raw, AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_INVALID, rejected.code());
        assertEquals("/", rejected.pointer());
    }

    @Test
    void rejectsTruncatedWebp() {
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(Arrays.copyOf(fixture("lossy.webp"), 20), AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_INVALID, rejected.code());
        assertEquals("/", rejected.pointer());
    }

    @Test
    void rejectsOversizedCanvasThroughTheSharedCapacityGuardBeforeFrameDecode() {
        byte[] raw = insertVp8xAndExif(fixture("lossy.webp"), exifTiffOrientation(6));
        raw[24] = 0x20;
        raw[25] = 0x4E;
        raw[26] = 0x00; // canvas width minus one = 20,000

        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(raw, AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED, rejected.code());
        assertEquals(FailureStage.ASSET_STRUCTURE, rejected.stage());
        assertEquals("/VP8X", rejected.pointer());
        assertEquals(AssetAcceptanceAuthority.Limit.IMAGE_EDGE_PIXELS,
                rejected.limit().orElseThrow());
    }

    @Test
    void appliesExifOrientationFromExifChunk() {
        byte[] raw = insertVp8xAndExif(fixture("lossy.webp"), exifTiffOrientation(6));
        var outcome = AUTHORITY.admit(raw, AssetKind.IMAGE);
        var admitted = assertInstanceOf(AssetAcceptanceAuthority.Admitted.class, outcome);
        var descriptor = assertInstanceOf(
                AssetAcceptanceAuthority.ImageDescriptor.class, admitted.descriptor()
        );
        assertEquals(2, descriptor.encodedWidthPx());
        assertEquals(3, descriptor.encodedHeightPx());
        assertEquals(Orientation.ROTATE_90_CW, descriptor.orientation());
        assertEquals(3, descriptor.logicalWidthPx());
        assertEquals(2, descriptor.logicalHeightPx());
    }

    // ---------- helpers ----------

    private static byte[] insertVp8xAndExif(byte[] raw, byte[] exifTiff) {
        byte[] vp8x = vp8xChunk(0x08, 1, 2); // EXIF flag, canvas 2x3 (minus one)
        byte[] exif = chunkBytes("EXIF", exifTiff);
        var out = new ByteArrayOutputStream(raw.length + vp8x.length + exif.length);
        out.writeBytes(Arrays.copyOf(raw, 4)); // "RIFF"
        out.writeBytes(new byte[4]); // size placeholder
        out.writeBytes(Arrays.copyOfRange(raw, 8, 12)); // "WEBP"
        int imageChunk = findImageChunk(raw);
        out.writeBytes(vp8x);
        out.writeBytes(exif);
        out.write(raw, imageChunk, raw.length - imageChunk);
        byte[] result = out.toByteArray();
        writeLe32(result, 4, result.length - 8);
        return result;
    }

    private static byte[] vp8xChunk(int flags, int widthMinusOne, int heightMinusOne) {
        var data = new byte[10];
        data[0] = (byte) flags;
        data[4] = (byte) (widthMinusOne & 0xFF);
        data[5] = (byte) ((widthMinusOne >>> 8) & 0xFF);
        data[6] = (byte) ((widthMinusOne >>> 16) & 0xFF);
        data[7] = (byte) (heightMinusOne & 0xFF);
        data[8] = (byte) ((heightMinusOne >>> 8) & 0xFF);
        data[9] = (byte) ((heightMinusOne >>> 16) & 0xFF);
        return chunkBytes("VP8X", data);
    }

    private static byte[] chunkBytes(String fourCc, byte[] payload) {
        var out = new ByteArrayOutputStream(8 + payload.length + 1);
        out.writeBytes(fourCc.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeLe32(out, payload.length);
        out.writeBytes(payload);
        if (payload.length % 2 != 0) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static byte[] insertChunk(byte[] raw, String fourCc, byte[] payload) {
        var out = new ByteArrayOutputStream(raw.length + 8 + payload.length + 1);
        out.writeBytes(Arrays.copyOf(raw, 4)); // "RIFF"
        out.writeBytes(new byte[4]); // size placeholder
        out.writeBytes(Arrays.copyOfRange(raw, 8, 12)); // "WEBP"
        // new chunk before the image data chunk
        int imageChunk = findImageChunk(raw);
        out.write(raw, 12, imageChunk - 12);
        out.writeBytes(fourCc.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeLe32(out, payload.length);
        out.writeBytes(payload);
        if (payload.length % 2 != 0) {
            out.write(0);
        }
        out.write(raw, imageChunk, raw.length - imageChunk);
        byte[] result = out.toByteArray();
        writeLe32(result, 4, result.length - 8);
        return result;
    }

    private static int findImageChunk(byte[] raw) {
        int position = 12;
        while (position + 8 <= raw.length) {
            String fourCc = new String(raw, position, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (fourCc.equals("VP8 ") || fourCc.equals("VP8L")) {
                return position;
            }
            int size = (raw[position + 4] & 0xFF)
                    | ((raw[position + 5] & 0xFF) << 8)
                    | ((raw[position + 6] & 0xFF) << 16)
                    | ((raw[position + 7] & 0xFF) << 24);
            position += 8 + size + (size % 2);
        }
        throw new AssertionError("image chunk not found");
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeLe32(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static byte[] exifTiffOrientation(int orientation) {
        var data = new byte[2 + 2 + 4 + 2 + 12 + 4];
        data[0] = 'M';
        data[1] = 'M';
        data[3] = 0x2A;
        data[7] = 0x08;
        data[9] = 0x01;
        data[10] = 0x01;
        data[11] = 0x12;
        data[13] = 0x03;
        data[17] = 0x01;
        data[19] = (byte) orientation;
        return data;
    }

    private static String sha256Hex(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
