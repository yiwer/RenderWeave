package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FailureCode;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FailureStage;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Limit;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Orientation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetAcceptanceKernelTest {

    private static final AssetAcceptanceAuthority AUTHORITY = new CanonicalAssetAcceptanceAuthority();

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    // ---------- inputs and budgets ----------

    @Test
    void rejectsNullInputsAndEmptyContent() {
        assertThrows(NullPointerException.class, () -> AUTHORITY.admit(null, AssetKind.IMAGE));
        assertThrows(NullPointerException.class, () -> AUTHORITY.admit(new byte[]{1}, null));

        var outcome = AUTHORITY.admit(new byte[0], AssetKind.IMAGE);
        var rejected = assertRejected(outcome, FailureCode.ASSET_CONTENT_INVALID, "/");
        assertEquals(FailureStage.ASSET_STRUCTURE, rejected.stage());
        assertTrue(rejected.limit().isEmpty());
    }

    @Test
    void rejectsContentThatMatchesNoKnownFormatMagic() {
        var outcome = AUTHORITY.admit(
                "GIF89a not a png or jpeg".getBytes(StandardCharsets.US_ASCII),
                AssetKind.IMAGE
        );
        var rejected = assertRejected(outcome, FailureCode.ASSET_CONTENT_INVALID, "/");
        assertEquals(FailureStage.ASSET_STRUCTURE, rejected.stage());
    }

    @Test
    void enforcesRawByteBudgetsPerKind() {
        var tooLargeImage = new byte[64 * 1024 * 1024 + 1];
        tooLargeImage[0] = (byte) 0x89;
        var imageOutcome = AUTHORITY.admit(tooLargeImage, AssetKind.IMAGE);
        var imageRejected = assertRejected(
                imageOutcome, FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED, "/"
        );
        assertEquals(FailureStage.ASSET_STRUCTURE, imageRejected.stage());
        assertEquals(Limit.RAW_BYTES, imageRejected.limit().orElseThrow());

        var tooLargeFont = new byte[32 * 1024 * 1024 + 1];
        tooLargeFont[0] = 0x00;
        var fontOutcome = AUTHORITY.admit(tooLargeFont, AssetKind.FONT);
        var fontRejected = assertRejected(
                fontOutcome, FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED, "/"
        );
        assertEquals(Limit.RAW_BYTES, fontRejected.limit().orElseThrow());
    }

    // ---------- PNG admission ----------

    @Test
    void admitsMinimalTruecolorPngWithExactDescriptor() {
        byte[] raw = png(
                chunk("IHDR", ihdr(1, 1, 8, 6, 0)),
                chunk("IDAT", deflate(new byte[]{0x00, 0x12, 0x34, 0x56, 0x78})),
                chunk("IEND", new byte[0])
        );

        var outcome = AUTHORITY.admit(raw, AssetKind.IMAGE);
        var admitted = assertInstanceOf(AssetAcceptanceAuthority.Admitted.class, outcome);
        assertEquals(AssetKind.IMAGE, admitted.kind());
        assertEquals(raw.length, admitted.byteLength());
        assertEquals(sha256Hex(raw), admitted.sha256());
        assertEquals("renderweave-asset-acceptance/1.0", admitted.acceptanceProfileId());
        var descriptor = assertInstanceOf(
                AssetAcceptanceAuthority.ImageDescriptor.class, admitted.descriptor()
        );
        assertEquals(1, descriptor.encodedWidthPx());
        assertEquals(1, descriptor.encodedHeightPx());
        assertEquals(Orientation.IDENTITY, descriptor.orientation());
        assertEquals(1, descriptor.logicalWidthPx());
        assertEquals(1, descriptor.logicalHeightPx());
        assertEquals(1, descriptor.frameCount());
        assertEquals(AssetAcceptanceAuthority.ColorEncoding.SRGB_8BIT, descriptor.colorEncoding());
    }

    @Test
    void admitsGrayscaleAndIndexedPngCombinations() {
        var grayscale = png(
                chunk("IHDR", ihdr(1, 1, 8, 0, 0)),
                chunk("IDAT", deflate(new byte[]{0x00, 0x40})),
                chunk("IEND", new byte[0])
        );
        assertInstanceOf(AssetAcceptanceAuthority.Admitted.class,
                AUTHORITY.admit(grayscale, AssetKind.IMAGE));

        var indexed = png(
                chunk("IHDR", ihdr(1, 1, 8, 3, 0)),
                chunk("PLTE", new byte[]{0x10, 0x20, 0x30}),
                chunk("IDAT", deflate(new byte[]{0x00, 0x00})),
                chunk("IEND", new byte[0])
        );
        assertInstanceOf(AssetAcceptanceAuthority.Admitted.class,
                AUTHORITY.admit(indexed, AssetKind.IMAGE));
    }

    @Test
    void rejectsSixteenBitPngAsUnsupported() {
        byte[] raw = png(
                chunk("IHDR", ihdr(1, 1, 16, 6, 0)),
                chunk("IDAT", deflate(new byte[]{0x00, 0x12, 0x34})),
                chunk("IEND", new byte[0])
        );
        var rejected = assertRejected(
                AUTHORITY.admit(raw, AssetKind.IMAGE),
                FailureCode.ASSET_CONTENT_UNSUPPORTED,
                "/ihdr"
        );
        assertEquals(FailureStage.ASSET_STRUCTURE, rejected.stage());
    }

    @Test
    void rejectsAnimatedPngAsUnsupported() {
        byte[] raw = png(
                chunk("IHDR", ihdr(1, 1, 8, 6, 0)),
                chunk("acTL", new byte[]{0, 0, 0, 1, 0, 0, 0, 0}),
                chunk("IDAT", deflate(new byte[]{0x00, 0x12, 0x34, 0x56, 0x78})),
                chunk("IEND", new byte[0])
        );
        var rejected = assertRejected(
                AUTHORITY.admit(raw, AssetKind.IMAGE),
                FailureCode.ASSET_CONTENT_UNSUPPORTED,
                "/acTL"
        );
        assertEquals(FailureStage.ASSET_STRUCTURE, rejected.stage());
    }

    @Test
    void rejectsChunksWithInvalidCrc() {
        byte[] raw = png(
                chunk("IHDR", ihdr(1, 1, 8, 6, 0)),
                chunk("IDAT", deflate(new byte[]{0x00, 0x12, 0x34, 0x56, 0x78})),
                chunk("IEND", new byte[0])
        );
        raw[16] ^= 0x01; // corrupt IHDR width byte, stored CRC no longer matches
        var rejected = assertRejected(
                AUTHORITY.admit(raw, AssetKind.IMAGE),
                FailureCode.ASSET_CONTENT_INVALID,
                "/IHDR"
        );
        assertEquals(FailureStage.ASSET_STRUCTURE, rejected.stage());
    }

    @Test
    void rejectsCorruptImageDataThatFailsFullDecode() {
        byte[] raw = png(
                chunk("IHDR", ihdr(1, 1, 8, 6, 0)),
                chunk("IDAT", deflate(new byte[]{0x00})),
                chunk("IEND", new byte[0])
        );
        var rejected = assertRejected(
                AUTHORITY.admit(raw, AssetKind.IMAGE),
                FailureCode.ASSET_CONTENT_INVALID,
                "/IDAT"
        );
        assertEquals(FailureStage.ASSET_DECODE, rejected.stage());
    }

    @Test
    void enforcesImageDimensionLimitsBeforeDecode() {
        var wide = png(
                chunk("IHDR", ihdr(20001, 1, 8, 6, 0)),
                chunk("IDAT", deflate(new byte[]{0x00})),
                chunk("IEND", new byte[0])
        );
        var wideRejected = assertRejected(
                AUTHORITY.admit(wide, AssetKind.IMAGE),
                FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED,
                "/ihdr"
        );
        assertEquals(Limit.IMAGE_EDGE_PIXELS, wideRejected.limit().orElseThrow());

        var dense = png(
                chunk("IHDR", ihdr(10001, 10001, 8, 6, 0)),
                chunk("IDAT", deflate(new byte[]{0x00})),
                chunk("IEND", new byte[0])
        );
        var denseRejected = assertRejected(
                AUTHORITY.admit(dense, AssetKind.IMAGE),
                FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED,
                "/ihdr"
        );
        assertEquals(Limit.IMAGE_TOTAL_PIXELS, denseRejected.limit().orElseThrow());
    }

    @Test
    void appliesExifOrientationToLogicalDimensions() {
        byte[] raw = png(
                chunk("IHDR", ihdr(2, 1, 8, 6, 0)),
                chunk("eXIf", exifTiffOrientation(6)),
                chunk("IDAT", deflate(new byte[]{0x00, 0x12, 0x34, 0x56, 0x78, 0x11, 0x22, 0x33, 0x44})),
                chunk("IEND", new byte[0])
        );
        var outcome = AUTHORITY.admit(raw, AssetKind.IMAGE);
        var admitted = assertInstanceOf(AssetAcceptanceAuthority.Admitted.class, outcome);
        var descriptor = assertInstanceOf(
                AssetAcceptanceAuthority.ImageDescriptor.class, admitted.descriptor()
        );
        assertEquals(2, descriptor.encodedWidthPx());
        assertEquals(1, descriptor.encodedHeightPx());
        assertEquals(Orientation.ROTATE_90_CW, descriptor.orientation());
        assertEquals(1, descriptor.logicalWidthPx());
        assertEquals(2, descriptor.logicalHeightPx());
    }

    @Test
    void acceptsSrgbDeclarationAndRejectsNonCanonicalOrConflictingIcc() {
        var withSrgb = png(
                chunk("IHDR", ihdr(1, 1, 8, 6, 0)),
                chunk("sRGB", new byte[]{0x00}),
                chunk("IDAT", deflate(new byte[]{0x00, 0x12, 0x34, 0x56, 0x78})),
                chunk("IEND", new byte[0])
        );
        assertInstanceOf(AssetAcceptanceAuthority.Admitted.class,
                AUTHORITY.admit(withSrgb, AssetKind.IMAGE));

        var withIcc = png(
                chunk("IHDR", ihdr(1, 1, 8, 6, 0)),
                chunk("iCCP", iccp("profile", deflate(new byte[]{0x01, 0x02, 0x03}))),
                chunk("IDAT", deflate(new byte[]{0x00, 0x12, 0x34, 0x56, 0x78})),
                chunk("IEND", new byte[0])
        );
        var iccRejected = assertRejected(
                AUTHORITY.admit(withIcc, AssetKind.IMAGE),
                FailureCode.ASSET_CONTENT_UNSUPPORTED,
                "/iCCP"
        );
        assertEquals(FailureStage.ASSET_DESCRIPTOR, iccRejected.stage());

        var conflicting = png(
                chunk("IHDR", ihdr(1, 1, 8, 6, 0)),
                chunk("sRGB", new byte[]{0x00}),
                chunk("iCCP", iccp("profile", deflate(new byte[]{0x01, 0x02, 0x03}))),
                chunk("IDAT", deflate(new byte[]{0x00, 0x12, 0x34, 0x56, 0x78})),
                chunk("IEND", new byte[0])
        );
        assertRejected(
                AUTHORITY.admit(conflicting, AssetKind.IMAGE),
                FailureCode.ASSET_CONTENT_UNSUPPORTED,
                "/iCCP"
        );
    }

    @Test
    void rejectsIndexedPngWithoutRequiredPalette() {
        byte[] raw = png(
                chunk("IHDR", ihdr(1, 1, 8, 3, 0)),
                chunk("IDAT", deflate(new byte[]{0x00, 0x00})),
                chunk("IEND", new byte[0])
        );
        var rejected = assertRejected(
                AUTHORITY.admit(raw, AssetKind.IMAGE),
                FailureCode.ASSET_CONTENT_INVALID,
                "/PLTE"
        );
        assertEquals(FailureStage.ASSET_STRUCTURE, rejected.stage());
    }

    // ---------- fixture helpers (independent PNG-byte construction) ----------

    private static AssetAcceptanceAuthority.Rejected assertRejected(
            AssetAcceptanceAuthority.Acceptance outcome,
            FailureCode code,
            String pointer
    ) {
        var rejected = assertInstanceOf(AssetAcceptanceAuthority.Rejected.class, outcome);
        assertEquals(code, rejected.code());
        assertEquals(pointer, rejected.pointer());
        return rejected;
    }

    private static byte[] png(byte[]... chunks) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(PNG_SIGNATURE);
        for (byte[] chunk : chunks) {
            writeU32(out, chunk.length - 4);
            out.writeBytes(chunk);
            var crc = new CRC32();
            crc.update(chunk);
            writeU32(out, (int) crc.getValue());
        }
        return out.toByteArray();
    }

    private static byte[] chunk(String type, byte[] data) {
        var chunk = new byte[4 + data.length];
        chunk[0] = (byte) type.charAt(0);
        chunk[1] = (byte) type.charAt(1);
        chunk[2] = (byte) type.charAt(2);
        chunk[3] = (byte) type.charAt(3);
        System.arraycopy(data, 0, chunk, 4, data.length);
        return chunk;
    }

    private static byte[] ihdr(int width, int height, int bitDepth, int colorType, int interlace) {
        var data = new byte[13];
        writeU32(data, 0, width);
        writeU32(data, 4, height);
        data[8] = (byte) bitDepth;
        data[9] = (byte) colorType;
        data[12] = (byte) interlace;
        return data;
    }

    private static byte[] iccp(String name, byte[] compressedProfile) {
        var nameBytes = name.getBytes(StandardCharsets.ISO_8859_1);
        var data = new byte[nameBytes.length + 1 + 1 + compressedProfile.length];
        System.arraycopy(nameBytes, 0, data, 0, nameBytes.length);
        data[nameBytes.length + 1] = 0x00; // compression method
        System.arraycopy(compressedProfile, 0, data, nameBytes.length + 2, compressedProfile.length);
        return data;
    }

    private static byte[] exifTiffOrientation(int orientation) {
        var data = new byte[2 + 2 + 4 + 2 + 12 + 4];
        data[0] = 'M';
        data[1] = 'M';
        writeU16(data, 2, 0x002A);
        writeU32(data, 4, 0x00000008);
        writeU16(data, 8, 1);
        writeU16(data, 10, 0x0112);
        writeU16(data, 12, 0x0003);
        writeU32(data, 14, 1);
        writeU16(data, 18, orientation);
        return data;
    }

    private static byte[] deflate(byte[] raw) {
        var deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        var out = new ByteArrayOutputStream();
        var buffer = new byte[64];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            out.write(buffer, 0, count);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static String sha256Hex(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU32(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 24) & 0xFF);
        target[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 3] = (byte) (value & 0xFF);
    }

    private static void writeU16(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 1] = (byte) (value & 0xFF);
    }
}
