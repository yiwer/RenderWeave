package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Acceptance;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Admitted;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FailureCode;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FailureStage;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FontDescriptor;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FontFlavor;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Rejected;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class FontAdmission {

    private static final int MAGIC_TRUETYPE = 0x00010000;
    private static final int MAGIC_OTTO = 0x4F54544F; // 'OTTO'
    private static final long CHECKSUM_MAGIC = 0xB1B0AFBAL;
    private static final long HEAD_MAGIC = 0x5F0F3CF5L;

    private static final Set<String> BANNED_TABLES = Set.of(
            "COLR", "CPAL", "CBDT", "CBLC", "sbix", "SVG ", "EBDT", "EBLC", "EBSC",
            "bdat", "bloc", "fvar", "gvar", "CFF2", "Silf", "Glat", "Gloc",
            "morx", "mort", "feat"
    );

    private static final Set<String> GLYF_REQUIRED = Set.of(
            "cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "name", "OS/2", "post"
    );

    private static final Set<String> CFF_REQUIRED = Set.of(
            "CFF ", "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post"
    );

    private FontAdmission() {
    }

    static Acceptance admit(byte[] raw) {
        if (raw.length < 12) {
            return invalid("/");
        }
        long magic = u32(raw, 0);
        boolean glyfFlavor;
        if (magic == MAGIC_TRUETYPE) {
            glyfFlavor = true;
        } else if (magic == MAGIC_OTTO) {
            glyfFlavor = false;
        } else {
            // Collections (ttcf), wOFF, wOF2 and legacy 'true'/'typ1' are all rejected.
            return invalid("/");
        }

        int numTables = u16(raw, 4);
        if (numTables == 0 || 12L + (long) numTables * 16 > raw.length) {
            return invalid("/tables");
        }
        Map<String, Record> tables = new HashMap<>();
        for (int i = 0; i < numTables; i++) {
            int base = 12 + i * 16;
            String tag = tag(raw, base);
            long checksum = u32(raw, base + 4);
            long offset = u32(raw, base + 8);
            long length = u32(raw, base + 12);
            if (offset + length > raw.length) {
                return invalid("/tables");
            }
            if (tables.putIfAbsent(tag, new Record(checksum, (int) offset, (int) length)) != null) {
                return invalid("/tables");
            }
        }
        for (String banned : BANNED_TABLES) {
            if (tables.containsKey(banned)) {
                return unsupported("/tables");
            }
        }
        for (String required : glyfFlavor ? GLYF_REQUIRED : CFF_REQUIRED) {
            if (!tables.containsKey(required)) {
                return invalid("/tables");
            }
        }

        for (Map.Entry<String, Record> entry : tables.entrySet()) {
            Record record = entry.getValue();
            long actual = tableChecksum(raw, record.offset, record.length);
            if (entry.getKey().equals("head")) {
                // The head checksum is defined with checkSumAdjustment zeroed.
                actual -= u32(raw, record.offset + 8);
            }
            if (actual != record.checksum) {
                return invalid("/" + entry.getKey().trim());
            }
        }
        Record head = tables.get("head");
        // checkSumAdjustment is stored so the entire-file checksum equals 0xB1B0AFBA.
        if (fileChecksum(raw) != CHECKSUM_MAGIC) {
            return invalid("/head");
        }

        if (head.length < 54 || u32(raw, head.offset + 12) != HEAD_MAGIC) {
            return invalid("/head");
        }
        int unitsPerEm = u16(raw, head.offset + 18);
        if (unitsPerEm < 16 || unitsPerEm > 16384) {
            return invalid("/head");
        }
        int indexToLocFormat = u16(raw, head.offset + 50);
        if (indexToLocFormat > 1) {
            return invalid("/head");
        }

        Record maxp = tables.get("maxp");
        if (maxp.length < 6) {
            return invalid("/maxp");
        }
        long maxpVersion = u32(raw, maxp.offset);
        if (maxpVersion != 0x00005000L && maxpVersion != 0x00010000L) {
            return invalid("/maxp");
        }
        int numGlyphs = u16(raw, maxp.offset + 4);
        if (numGlyphs == 0) {
            return invalid("/maxp");
        }
        if (glyfFlavor && maxpVersion != 0x00010000L) {
            return invalid("/maxp"); // glyf flavor requires maxp 1.0
        }
        if (!glyfFlavor && maxpVersion != 0x00005000L) {
            return invalid("/maxp"); // CFF flavor requires maxp 0.5
        }

        if (glyfFlavor) {
            Record loca = tables.get("loca");
            Record glyf = tables.get("glyf");
            int entrySize = indexToLocFormat == 0 ? 2 : 4;
            if (loca.length != (numGlyphs + 1) * entrySize) {
                return invalid("/loca");
            }
            long lastLoca = indexToLocFormat == 0
                    ? (long) u16(raw, loca.offset + numGlyphs * 2) * 2
                    : u32(raw, loca.offset + numGlyphs * 4);
            if (lastLoca != glyf.length) {
                return invalid("/loca");
            }
            int[] glyphOffsets = new int[numGlyphs + 1];
            for (int g = 0; g <= numGlyphs; g++) {
                long value = indexToLocFormat == 0
                        ? (long) u16(raw, loca.offset + g * 2) * 2
                        : u32(raw, loca.offset + g * 4);
                if (value > glyf.length || (g > 0 && value < glyphOffsets[g - 1])) {
                    return invalid("/loca");
                }
                glyphOffsets[g] = (int) value;
            }

            int[][] compositeReferences = new int[numGlyphs][];
            for (int g = 0; g < numGlyphs; g++) {
                int start = glyphOffsets[g];
                int end = glyphOffsets[g + 1];
                if (start == end) {
                    continue;
                }
                var glyph = parseGlyph(raw, glyf.offset + start, glyf.offset + end, numGlyphs);
                if (glyph == null) {
                    return invalid("/glyf");
                }
                compositeReferences[g] = glyph;
            }
            byte[] visitState = new byte[numGlyphs];
            for (int g = 0; g < numGlyphs; g++) {
                if (compositeReferences[g] != null && !acyclic(g, compositeReferences, visitState)) {
                    return invalid("/glyf");
                }
            }
        } else {
            Record cff = tables.get("CFF ");
            if (!CffTable.valid(raw, cff.offset, cff.length, numGlyphs)) {
                return invalid("/CFF ");
            }
        }

        Record cmap = tables.get("cmap");
        if (!validCmap(raw, cmap.offset, cmap.length)) {
            return invalid("/cmap");
        }

        var descriptor = new FontDescriptor(
                0,
                glyfFlavor ? FontFlavor.TRUETYPE_GLYF : FontFlavor.CFF,
                unitsPerEm
        );
        return admitted(raw, descriptor);
    }

    /** Returns referenced composite glyph ids, or null when the glyph structure is invalid. */
    private static int[] parseGlyph(byte[] raw, int start, int end, int numGlyphs) {
        if (end - start < 10) {
            return null;
        }
        int contourCount = i16(raw, start);
        if (contourCount < -1) {
            return null;
        }
        if (contourCount == -1) {
            return parseComposite(raw, start, end, numGlyphs);
        }
        int position = start + 10;
        int points = 0;
        for (int c = 0; c < contourCount; c++) {
            if (position + 2 > end) {
                return null;
            }
            int point = u16(raw, position);
            position += 2;
            if (point < points) {
                return null;
            }
            points = point + 1;
        }
        if (position + 2 > end) {
            return null;
        }
        int instructionLength = u16(raw, position);
        position += 2;
        if (position + instructionLength > end) {
            return null;
        }
        position += instructionLength;
        int[] flags = new int[points];
        int i = 0;
        while (i < points) {
            if (position >= end) {
                return null;
            }
            int flag = raw[position++] & 0xFF;
            flags[i++] = flag;
            if ((flag & 0x08) != 0) {
                if (position >= end) {
                    return null;
                }
                int repeat = raw[position++] & 0xFF;
                if (i + repeat > points) {
                    return null;
                }
                for (int r = 0; r < repeat; r++) {
                    flags[i++] = flag;
                }
            }
        }
        for (int f : flags) {
            if ((f & 0x02) != 0) {
                if (position + 1 > end) {
                    return null;
                }
                position += 1;
            } else if ((f & 0x10) == 0) {
                if (position + 2 > end) {
                    return null;
                }
                position += 2;
            }
        }
        for (int f : flags) {
            if ((f & 0x04) != 0) {
                if (position + 1 > end) {
                    return null;
                }
                position += 1;
            } else if ((f & 0x20) == 0) {
                if (position + 2 > end) {
                    return null;
                }
                position += 2;
            }
        }
        return position <= end ? new int[0] : null;
    }

    private static int[] parseComposite(byte[] raw, int start, int end, int numGlyphs) {
        int position = start + 10;
        java.util.List<Integer> references = new java.util.ArrayList<>();
        int componentFlags = 0;
        do {
            if (position + 4 > end) {
                return null;
            }
            componentFlags = u16(raw, position);
            position += 2;
            int glyphIndex = u16(raw, position);
            position += 2;
            if (glyphIndex >= numGlyphs) {
                return null;
            }
            references.add(glyphIndex);
            int argumentBytes = (componentFlags & 0x0001) != 0 ? 4 : 2;
            if (position + argumentBytes > end) {
                return null;
            }
            position += argumentBytes;
            if ((componentFlags & 0x0008) != 0) {
                if (position + 2 > end) {
                    return null;
                }
                position += 2;
            } else if ((componentFlags & 0x0040) != 0) {
                if (position + 4 > end) {
                    return null;
                }
                position += 4;
            } else if ((componentFlags & 0x0080) != 0) {
                if (position + 8 > end) {
                    return null;
                }
                position += 8;
            }
        } while ((componentFlags & 0x0020) == 0);
        if ((componentFlags & 0x0100) != 0) {
            if (position + 2 > end) {
                return null;
            }
            int instructionLength = u16(raw, position);
            position += 2;
            if (position + instructionLength > end) {
                return null;
            }
        }
        return position <= end ? references.stream().mapToInt(Integer::intValue).toArray() : null;
    }

    private static boolean acyclic(int glyph, int[][] references, byte[] state) {
        if (state[glyph] == 1) {
            return false; // cycle
        }
        if (state[glyph] == 2) {
            return true;
        }
        state[glyph] = 1;
        for (int reference : references[glyph]) {
            if (references[reference] != null && !acyclic(reference, references, state)) {
                return false;
            }
        }
        state[glyph] = 2;
        return true;
    }

    private static boolean validCmap(byte[] raw, int offset, int length) {
        if (length < 4 || u16(raw, offset) != 0) {
            return false;
        }
        int numTables = u16(raw, offset + 2);
        if (numTables == 0 || 4L + (long) numTables * 8 > length) {
            return false;
        }
        boolean sawValidUnicodeSubtable = false;
        for (int i = 0; i < numTables; i++) {
            int record = offset + 4 + i * 8;
            long subtableOffset = u32(raw, record + 4);
            if (subtableOffset >= length) {
                return false;
            }
            int subtable = offset + (int) subtableOffset;
            int format = u16(raw, subtable);
            if (format == 4) {
                if (subtable + 14 > offset + length) {
                    return false;
                }
                int segCountX2 = u16(raw, subtable + 6);
                if (segCountX2 == 0 || segCountX2 % 2 != 0 || subtable + 16L + segCountX2 * 4 > offset + length) {
                    return false;
                }
                sawValidUnicodeSubtable = true;
            } else if (format == 12) {
                if (subtable + 16 > offset + length) {
                    return false;
                }
                long groups = u32(raw, subtable + 12);
                if (groups == 0 || 16L + groups * 12 > length - subtableOffset) {
                    return false;
                }
                sawValidUnicodeSubtable = true;
            } else if (format == 0 || format == 6) {
                if (subtable + 6 > offset + length) {
                    return false;
                }
                sawValidUnicodeSubtable = true;
            }
        }
        return sawValidUnicodeSubtable;
    }

    private static String tag(byte[] raw, int offset) {
        return new String(raw, offset, 4, StandardCharsets.US_ASCII);
    }

    private static int u16(byte[] raw, int offset) {
        return ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
    }

    private static int i16(byte[] raw, int offset) {
        return (short) u16(raw, offset);
    }

    private static long u32(byte[] raw, int offset) {
        return (((long) raw[offset] & 0xFF) << 24)
                | ((raw[offset + 1] & 0xFFL) << 16)
                | ((raw[offset + 2] & 0xFFL) << 8)
                | (raw[offset + 3] & 0xFFL);
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

    private static Admitted admitted(byte[] raw, FontDescriptor descriptor) {
        return CanonicalAssetAcceptanceAuthority.admitted(AssetKind.FONT, raw, descriptor);
    }

    private static Rejected invalid(String pointer) {
        return new Rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, pointer, Optional.empty());
    }

    private static Rejected unsupported(String pointer) {
        return new Rejected(
                FailureCode.ASSET_CONTENT_UNSUPPORTED,
                FailureStage.ASSET_STRUCTURE,
                pointer,
                Optional.empty()
        );
    }

    private record Record(long checksum, int offset, int length) {
    }
}
