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

class JpegAdmissionTest {

    private static final AssetAcceptanceAuthority AUTHORITY = new CanonicalAssetAcceptanceAuthority();

    private static byte[] fixture(String name) {
        try (var stream = JpegAdmissionTest.class.getResourceAsStream("/asset-fixtures/" + name)) {
            assertTrue(stream != null, "missing fixture " + name);
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void admitsGrayscaleBaselineJpegWithExactDescriptor() {
        byte[] raw = fixture("grayscale-baseline.jpg");
        var outcome = AUTHORITY.admit(raw, AssetKind.IMAGE);
        var admitted = assertInstanceOf(AssetAcceptanceAuthority.Admitted.class, outcome);
        assertEquals(AssetKind.IMAGE, admitted.kind());
        assertEquals(raw.length, admitted.byteLength());
        assertEquals(sha256Hex(raw), admitted.sha256());
        assertEquals("renderweave-asset-acceptance/1.0", admitted.acceptanceProfileId());
        var descriptor = assertInstanceOf(
                AssetAcceptanceAuthority.ImageDescriptor.class, admitted.descriptor()
        );
        assertEquals(2, descriptor.encodedWidthPx());
        assertEquals(3, descriptor.encodedHeightPx());
        assertEquals(Orientation.IDENTITY, descriptor.orientation());
        assertEquals(2, descriptor.logicalWidthPx());
        assertEquals(3, descriptor.logicalHeightPx());
        assertEquals(1, descriptor.frameCount());
        assertEquals(AssetAcceptanceAuthority.ColorEncoding.SRGB_8BIT, descriptor.colorEncoding());
    }

    @Test
    void admitsProgressiveYcbcrJpeg() {
        var outcome = AUTHORITY.admit(fixture("ycbcr-progressive.jpg"), AssetKind.IMAGE);
        var admitted = assertInstanceOf(AssetAcceptanceAuthority.Admitted.class, outcome);
        var descriptor = assertInstanceOf(
                AssetAcceptanceAuthority.ImageDescriptor.class, admitted.descriptor()
        );
        assertEquals(2, descriptor.encodedWidthPx());
        assertEquals(3, descriptor.encodedHeightPx());
    }

    @Test
    void rejectsCmykJpegAsUnsupported() {
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(fixture("cmyk.jpg"), AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_UNSUPPORTED, rejected.code());
        assertEquals(FailureStage.ASSET_STRUCTURE, rejected.stage());
        assertEquals("/sof", rejected.pointer());
    }

    @Test
    void rejectsIccCarryingJpegAsUnsupported() {
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(fixture("icc-profile.jpg"), AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_UNSUPPORTED, rejected.code());
        assertEquals(FailureStage.ASSET_DESCRIPTOR, rejected.stage());
        assertEquals("/ICC", rejected.pointer());
    }

    @Test
    void rejectsNonEightBitPrecisionAsUnsupported() {
        byte[] raw = fixture("grayscale-baseline.jpg");
        int sof = findMarker(raw, 0xC0);
        raw[sof + 4] = 12; // precision byte
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(raw, AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_UNSUPPORTED, rejected.code());
        assertEquals("/sof", rejected.pointer());
    }

    @Test
    void rejectsArithmeticConditioningAsUnsupported() {
        byte[] raw = fixture("grayscale-baseline.jpg");
        byte[] withDac = insertSegment(raw, 0xCC, new byte[]{0x00, 0x00});
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(withDac, AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_UNSUPPORTED, rejected.code());
        assertEquals("/dac", rejected.pointer());
    }

    @Test
    void rejectsTruncatedJpeg() {
        byte[] raw = fixture("grayscale-baseline.jpg");
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(Arrays.copyOf(raw, 20), AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_INVALID, rejected.code());
        assertEquals(FailureStage.ASSET_STRUCTURE, rejected.stage());
        assertEquals("/", rejected.pointer());
    }

    @Test
    void rejectsOversizedFrameThroughTheSharedCapacityGuardBeforeDecode() {
        byte[] raw = fixture("grayscale-baseline.jpg");
        int sof = findMarker(raw, 0xC0);
        raw[sof + 7] = 0x4E;
        raw[sof + 8] = 0x21; // width = 20,001

        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(raw, AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED, rejected.code());
        assertEquals(FailureStage.ASSET_STRUCTURE, rejected.stage());
        assertEquals("/sof", rejected.pointer());
        assertEquals(AssetAcceptanceAuthority.Limit.IMAGE_EDGE_PIXELS,
                rejected.limit().orElseThrow());
    }

    @Test
    void rejectsFrameDataThatFailsFullDecode() {
        byte[] raw = fixture("grayscale-baseline.jpg");
        int sos = findMarker(raw, 0xDA);
        raw[sos + 6] = 0x33; // SOS component selects undefined DC/AC Huffman tables
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(raw, AssetKind.IMAGE)
        );
        assertEquals(FailureCode.ASSET_CONTENT_INVALID, rejected.code());
        assertEquals(FailureStage.ASSET_DECODE, rejected.stage());
        assertEquals("/SOS", rejected.pointer());
    }

    @Test
    void appliesExifOrientationFromApp1Segment() {
        byte[] raw = fixture("grayscale-baseline.jpg");
        byte[] withExif = insertApp1(raw, exifTiffOrientation(6));
        var outcome = AUTHORITY.admit(withExif, AssetKind.IMAGE);
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

    private static int findMarker(byte[] raw, int marker) {
        for (int i = 0; i + 1 < raw.length; i++) {
            if ((raw[i] & 0xFF) == 0xFF && (raw[i + 1] & 0xFF) == marker) {
                return i;
            }
        }
        throw new AssertionError("marker " + Integer.toHexString(marker) + " not found");
    }

    private static byte[] insertApp1(byte[] raw, byte[] exifTiff) {
        var out = new ByteArrayOutputStream(raw.length + 12 + exifTiff.length);
        out.write(raw, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1);
        int length = 2 + 6 + exifTiff.length;
        out.write((length >>> 8) & 0xFF);
        out.write(length & 0xFF);
        out.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0x00, 0x00});
        out.writeBytes(exifTiff);
        out.write(raw, 2, raw.length - 2);
        return out.toByteArray();
    }

    private static byte[] insertSegment(byte[] raw, int marker, byte[] payload) {
        var out = new ByteArrayOutputStream(raw.length + 4 + payload.length);
        out.write(raw, 0, 2); // SOI
        out.write(0xFF);
        out.write(marker);
        int length = 2 + payload.length;
        out.write((length >>> 8) & 0xFF);
        out.write(length & 0xFF);
        out.writeBytes(payload);
        out.write(raw, 2, raw.length - 2);
        return out.toByteArray();
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
