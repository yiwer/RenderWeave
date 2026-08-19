package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FailureCode;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FailureStage;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FontFlavor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FontAdmissionTest {

    private static final AssetAcceptanceAuthority AUTHORITY = new CanonicalAssetAcceptanceAuthority();

    private static byte[] fixture() {
        return fixture("minimal-ttf.ttf");
    }

    private static byte[] fixture(String name) {
        try (var stream = FontAdmissionTest.class.getResourceAsStream("/asset-fixtures/" + name)) {
            assertTrue(stream != null, "missing " + name + " fixture");
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void admitsMinimalTtfWithExactDescriptor() {
        byte[] raw = fixture();
        var outcome = AUTHORITY.admit(raw, AssetKind.FONT);
        var admitted = assertInstanceOf(AssetAcceptanceAuthority.Admitted.class, outcome);
        assertEquals(AssetKind.FONT, admitted.kind());
        assertEquals(raw.length, admitted.byteLength());
        assertEquals(sha256Hex(raw), admitted.sha256());
        assertEquals("renderweave-asset-acceptance/1.0", admitted.acceptanceProfileId());
        var descriptor = assertInstanceOf(
                AssetAcceptanceAuthority.FontDescriptor.class, admitted.descriptor()
        );
        assertEquals(0, descriptor.faceIndex());
        assertEquals(FontFlavor.TRUETYPE_GLYF, descriptor.flavor());
        assertEquals(1000, descriptor.unitsPerEm());
    }

    @Test
    void rejectsNonOutlineFontMagic() {
        byte[] raw = fixture();
        raw[0] = 'w';
        raw[1] = 'O';
        raw[2] = 'F';
        raw[3] = 'F';
        assertRejected(raw, FailureCode.ASSET_CONTENT_INVALID, "/");
    }

    @Test
    void rejectsFontCollectionMagic() {
        byte[] raw = fixture();
        raw[0] = 't';
        raw[1] = 't';
        raw[2] = 'c';
        raw[3] = 'f';
        assertRejected(raw, FailureCode.ASSET_CONTENT_INVALID, "/");
    }

    @Test
    void rejectsBannedVariableTableAsUnsupported() {
        byte[] raw = fixture();
        replaceDirectoryTag(raw, "post", "fvar");
        var rejected = assertRejected(raw, FailureCode.ASSET_CONTENT_UNSUPPORTED, "/tables");
        assertEquals(FailureStage.ASSET_STRUCTURE, rejected.stage());
    }

    @Test
    void rejectsCorruptTableChecksum() {
        byte[] raw = fixture();
        int glyf = directoryIndex(raw, "glyf");
        int glyfOffset = u32(raw, 12 + glyf * 16 + 8);
        raw[glyfOffset + 20] ^= 0x01;
        assertRejected(raw, FailureCode.ASSET_CONTENT_INVALID, "/glyf");
    }

    @Test
    void rejectsTruncatedFont() {
        assertRejected(
                Arrays.copyOf(fixture(), 100),
                FailureCode.ASSET_CONTENT_INVALID,
                "/tables"
        );
    }

    @Test
    void rejectsLocaThatDoesNotMatchGlyfLength() {
        byte[] raw = fixture();
        int head = directoryIndex(raw, "head");
        int headOffset = u32(raw, 12 + head * 16 + 8);
        boolean shortFormat = u16(raw, headOffset + 50) == 0;
        int loca = directoryIndex(raw, "loca");
        int locaOffset = u32(raw, 12 + loca * 16 + 8);
        int numGlyphs = u16(raw, u32(raw, 12 + directoryIndex(raw, "maxp") * 16 + 8) + 4);
        int lastEntryOffset = locaOffset + numGlyphs * (shortFormat ? 2 : 4);
        long patched = shortFormat
                ? u16(raw, lastEntryOffset) + 1
                : (u32(raw, lastEntryOffset) & 0xFFFFFFFFL) + 1;
        byte[] replacement = shortFormat
                ? new byte[]{(byte) (patched >>> 8), (byte) patched}
                : new byte[]{
                        (byte) (patched >>> 24),
                        (byte) (patched >>> 16),
                        (byte) (patched >>> 8),
                        (byte) patched
                };
        patchAndFix(raw, "loca", lastEntryOffset - locaOffset, replacement);
        assertRejected(raw, FailureCode.ASSET_CONTENT_INVALID, "/loca");
    }

    @Test
    void rejectsGlyfWithInvalidContourCount() {
        byte[] raw = fixture();
        int glyf = directoryIndex(raw, "glyf");
        int glyfOffset = u32(raw, 12 + glyf * 16 + 8);
        patchAndFix(raw, "glyf", 0, new byte[]{(byte) 0xFF, (byte) 0xFB});
        assertRejected(raw, FailureCode.ASSET_CONTENT_INVALID, "/glyf");
    }

    @Test
    void rejectsUnitsPerEmOutOfRange() {
        byte[] raw = fixture();
        int head = directoryIndex(raw, "head");
        int headOffset = u32(raw, 12 + head * 16 + 8);
        patchAndFix(raw, "head", 18, new byte[]{0x40, 0x01}); // 16385 > 16384
        assertRejected(raw, FailureCode.ASSET_CONTENT_INVALID, "/head");
    }

    @Test
    void admitsMinimalOtfWithCffDescriptor() {
        byte[] raw = fixture("minimal-otf.otf");
        var outcome = AUTHORITY.admit(raw, AssetKind.FONT);
        var admitted = assertInstanceOf(AssetAcceptanceAuthority.Admitted.class, outcome);
        var descriptor = assertInstanceOf(
                AssetAcceptanceAuthority.FontDescriptor.class, admitted.descriptor()
        );
        assertEquals(0, descriptor.faceIndex());
        assertEquals(FontFlavor.CFF, descriptor.flavor());
        assertEquals(1000, descriptor.unitsPerEm());
        assertEquals(sha256Hex(raw), admitted.sha256());
    }

    @Test
    void rejectsCffWhoseCharStringsDoNotMatchMaxp() {
        byte[] raw = fixture("minimal-otf.otf");
        int maxp = directoryIndex(raw, "maxp");
        int maxpOffset = u32(raw, 12 + maxp * 16 + 8);
        patchAndFix(raw, "maxp", 4, new byte[]{0x00, 0x03}); // numGlyphs 2 -> 3
        assertRejected(raw, FailureCode.ASSET_CONTENT_INVALID, "/CFF ");
    }

    @Test
    void rejectsCffWithInvalidHeaderMajorVersion() {
        byte[] raw = fixture("minimal-otf.otf");
        int cff = directoryIndex(raw, "CFF ");
        int cffOffset = u32(raw, 12 + cff * 16 + 8);
        patchAndFix(raw, "CFF ", 0, new byte[]{0x02});
        assertRejected(raw, FailureCode.ASSET_CONTENT_INVALID, "/CFF ");
    }

    // ---------- helpers (spec-formula reimplementations for patching) ----------

    private static AssetAcceptanceAuthority.Rejected assertRejected(
            byte[] raw,
            FailureCode code,
            String pointer
    ) {
        var rejected = assertInstanceOf(
                AssetAcceptanceAuthority.Rejected.class,
                AUTHORITY.admit(raw, AssetKind.FONT)
        );
        assertEquals(code, rejected.code());
        assertEquals(pointer, rejected.pointer());
        return rejected;
    }

    private static int directoryIndex(byte[] raw, String tag) {
        int numTables = u16(raw, 4);
        for (int i = 0; i < numTables; i++) {
            int base = 12 + i * 16;
            if (tag.equals(new String(raw, base, 4, StandardCharsets.US_ASCII))) {
                return i;
            }
        }
        throw new AssertionError("table " + tag + " not found");
    }

    private static void replaceDirectoryTag(byte[] raw, String from, String to) {
        int base = 12 + directoryIndex(raw, from) * 16;
        byte[] bytes = to.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, raw, base, 4);
    }

    private static void patchAndFix(byte[] raw, String tag, int relativeOffset, byte[] newBytes) {
        int record = 12 + directoryIndex(raw, tag) * 16;
        int tableOffset = u32(raw, record + 8);
        int tableLength = u32(raw, record + 12);
        System.arraycopy(newBytes, 0, raw, tableOffset + relativeOffset, newBytes.length);
        writeU32(raw, record + 4, tableChecksum(raw, tableOffset, tableLength));
        int headRecord = 12 + directoryIndex(raw, "head") * 16;
        int headOffset = u32(raw, headRecord + 8);
        writeU32(raw, headOffset + 8, 0); // zero adjustment before measuring
        writeU32(raw, headRecord + 4, tableChecksum(raw, headOffset, u32(raw, headRecord + 12)));
        long adjustment = (0xB1B0AFBAL - fileChecksum(raw)) & 0xFFFFFFFFL;
        writeU32(raw, headOffset + 8, adjustment);
    }

    private static long tableChecksum(byte[] raw, int offset, int length) {
        long sum = 0;
        for (int i = 0; i < length; i += 4) {
            long value = 0;
            for (int j = 0; j < 4; j++) {
                int index = i + j;
                int b = index < length ? raw[offset + index] & 0xFF : 0;
                value = (value << 8) | b;
            }
            sum += value;
        }
        return sum & 0xFFFFFFFFL;
    }

    private static long fileChecksum(byte[] raw) {
        return tableChecksum(raw, 0, raw.length);
    }

    private static int u16(byte[] raw, int offset) {
        return ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
    }

    private static int u32(byte[] raw, int offset) {
        return ((raw[offset] & 0xFF) << 24)
                | ((raw[offset + 1] & 0xFF) << 16)
                | ((raw[offset + 2] & 0xFF) << 8)
                | (raw[offset + 3] & 0xFF);
    }

    private static void writeU32(byte[] raw, int offset, long value) {
        raw[offset] = (byte) (value >>> 24);
        raw[offset + 1] = (byte) (value >>> 16);
        raw[offset + 2] = (byte) (value >>> 8);
        raw[offset + 3] = (byte) value;
    }

    private static String sha256Hex(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
