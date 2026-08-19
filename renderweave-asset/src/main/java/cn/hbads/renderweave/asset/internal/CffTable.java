package cn.hbads.renderweave.asset.internal;

/**
 * Structural parser for the CFF table of a single-face, non-variable OpenType font:
 * header, INDEXes, Top DICT (operand-counted), Private DICT (Subrs), CharStrings INDEX
 * count match, and a Type 2 CharString token walk with operand counting, hintmask bytes
 * and bounded local/global subr resolution with cycle protection.
 */
final class CffTable {

    private static final int MAX_STACK = 48;
    private static final int MAX_SUBR_DEPTH = 10;

    private CffTable() {
    }

    static boolean valid(byte[] raw, int offset, int length, int expectedGlyphs) {
        int position = offset;
        int end = offset + length;
        if (position + 4 > end) {
            return false;
        }
        int major = raw[position] & 0xFF;
        int minor = raw[position + 1] & 0xFF;
        int headerSize = raw[position + 2] & 0xFF;
        int offSize = raw[position + 3] & 0xFF;
        if (major != 1 || minor != 0 || headerSize < 4 || offSize < 1 || offSize > 4
                || position + headerSize > end) {
            return false;
        }
        position = offset + headerSize;

        Index nameIndex = parseIndex(raw, position, end);
        if (nameIndex == null || nameIndex.count != 1) {
            return false;
        }
        Index topDictIndex = parseIndex(raw, nameIndex.nextPosition, end);
        if (topDictIndex == null || topDictIndex.count != 1) {
            return false;
        }
        Index stringIndex = parseIndex(raw, topDictIndex.nextPosition, end);
        if (stringIndex == null) {
            return false;
        }
        Index globalSubrIndex = parseIndex(raw, stringIndex.nextPosition, end);
        if (globalSubrIndex == null) {
            return false;
        }

        Dict topDict = parseDict(raw, topDictIndex.dataOffset, topDictIndex.dataEnd, true);
        if (topDict == null || topDict.charStringsOffset < 0) {
            return false;
        }
        if (offset + topDict.charStringsOffset >= end) {
            return false;
        }

        Index charStrings = parseIndex(raw, offset + (int) topDict.charStringsOffset, end);
        if (charStrings == null || charStrings.count != expectedGlyphs) {
            return false;
        }

        Index localSubrIndex = null;
        if (topDict.privateSize >= 0 && topDict.privateOffset >= 0) {
            long privateStart = offset + topDict.privateOffset;
            long privateEnd = privateStart + topDict.privateSize;
            if (privateStart < offset || privateEnd > end) {
                return false;
            }
            Dict privateDict = parseDict(raw, (int) privateStart, (int) privateEnd, false);
            if (privateDict == null) {
                return false;
            }
            if (privateDict.subrsOffset >= 0) {
                localSubrIndex = parseIndex(
                        raw,
                        (int) (privateStart + privateDict.subrsOffset),
                        (int) privateEnd
                );
                if (localSubrIndex == null) {
                    return false;
                }
            }
        }

        int localCount = localSubrIndex == null ? 0 : localSubrIndex.count;
        int globalCount = globalSubrIndex.count;
        for (int i = 0; i < charStrings.count; i++) {
            if (!validCharString(
                    raw,
                    charStrings.offsetOf(i),
                    charStrings.offsetOf(i + 1),
                    localSubrIndex,
                    globalSubrIndex,
                    localCount,
                    globalCount,
                    0,
                    new boolean[localCount + globalCount],
                    false
            )) {
                return false;
            }
        }
        return true;
    }

    private static boolean validCharString(
            byte[] raw,
            int start,
            int end,
            Index localSubrIndex,
            Index globalSubrIndex,
            int localCount,
            int globalCount,
            int depth,
            boolean[] activeSubrs,
            boolean isSubr
    ) {
        if (depth > MAX_SUBR_DEPTH || start > end) {
            return false;
        }
        int position = start;
        long[] stack = new long[MAX_STACK];
        int sp = 0;
        boolean widthAllowed = true;
        boolean sawEndchar = false;
        boolean sawReturn = false;
        while (position < end) {
            int b0 = raw[position] & 0xFF;
            if (b0 == 28 || b0 == 30 || (b0 >= 32 && b0 <= 254) || b0 == 255) {
                long[] number = readNumber(raw, position, end);
                if (number == null || sp >= MAX_STACK) {
                    return false;
                }
                stack[sp++] = number[0];
                position = (int) number[1];
                continue;
            }
            if (b0 == 12) {
                if (position + 1 >= end) {
                    return false;
                }
                int op = raw[position + 1] & 0xFF;
                int count = escapedCharStringOperatorCount(op);
                if (count < 0 || sp != count) {
                    return false;
                }
                if (op == 34 || op == 35 || op == 36 || op == 37) {
                    widthAllowed = false;
                }
                sp = 0;
                position += 2;
                continue;
            }
            switch (b0) {
                case 1, 3, 18, 23 -> { // hstem / vstem / hstemhm / vstemhm
                    if (sp < 2 || sp % 2 != 0) {
                        return false;
                    }
                    sp = 0;
                }
                case 4, 22 -> { // vmoveto / hmoveto
                    if (!clearingOperands(sp, 1, widthAllowed)) {
                        return false;
                    }
                    sp = 0;
                    widthAllowed = false;
                }
                case 21 -> { // rmoveto
                    if (!clearingOperands(sp, 2, widthAllowed)) {
                        return false;
                    }
                    sp = 0;
                    widthAllowed = false;
                }
                case 5, 6, 7 -> { // rlineto / hlineto / vlineto
                    if (sp < 1) {
                        return false;
                    }
                    sp = 0;
                    widthAllowed = false;
                }
                case 8 -> { // rrcurveto
                    if (sp < 6 || sp % 6 != 0) {
                        return false;
                    }
                    sp = 0;
                    widthAllowed = false;
                }
                case 10, 29 -> { // callsubr / callgsubr
                    if (sp != 1) {
                        return false;
                    }
                    int subr = (int) stack[0];
                    int count = b0 == 10 ? localCount : globalCount;
                    if (subr < 0 || subr >= count || subr >= activeSubrs.length) {
                        return false;
                    }
                    Index index = b0 == 10 ? localSubrIndex : globalSubrIndex;
                    int slot = b0 == 10 ? subr : localCount + subr;
                    if (activeSubrs[slot]) {
                        return false;
                    }
                    activeSubrs[slot] = true;
                    boolean ok = validCharString(
                            raw,
                            index.offsetOf(subr),
                            index.offsetOf(subr + 1),
                            localSubrIndex,
                            globalSubrIndex,
                            localCount,
                            globalCount,
                            depth + 1,
                            activeSubrs,
                            true
                    );
                    activeSubrs[slot] = false;
                    if (!ok) {
                        return false;
                    }
                    sp = 0;
                }
                case 11 -> { // return
                    if (sp != 0) {
                        return false;
                    }
                    sawReturn = true;
                }
                case 14 -> { // endchar
                    if (sp != 0 && sp != 4 && !(widthAllowed && (sp == 1 || sp == 5))) {
                        return false;
                    }
                    sp = 0;
                    sawEndchar = true;
                }
                case 19, 20 -> { // hintmask / cntrmask
                    int stems = sp;
                    if (stems % 2 != 0) {
                        if (!widthAllowed) {
                            return false;
                        }
                        stems--;
                    }
                    int maskBytes = (stems / 2 + 7) / 8;
                    if (position + 1 + maskBytes > end) {
                        return false;
                    }
                    position += 1 + maskBytes;
                    sp = 0;
                    widthAllowed = false;
                }
                case 24, 25 -> { // rcurveline / rlinecurve
                    if (sp < 8 || sp % 2 != 0) {
                        return false;
                    }
                    sp = 0;
                    widthAllowed = false;
                }
                case 26, 27 -> { // vvcurveto / hhcurveto
                    if (sp < 4 || (sp % 4 != 0 && sp % 4 != 1)) {
                        return false;
                    }
                    sp = 0;
                    widthAllowed = false;
                }
                case 30, 31 -> { // vhcurveto / hvcurveto
                    if (sp < 4 || (sp % 8 != 4 && sp % 8 != 5)) {
                        return false;
                    }
                    sp = 0;
                    widthAllowed = false;
                }
                default -> {
                    return false;
                }
            }
            position += 1;
        }
        return isSubr ? sawReturn : sawEndchar;
    }

    private static boolean clearingOperands(int sp, int exact, boolean widthAllowed) {
        return sp == exact || (widthAllowed && sp == exact + 1);
    }

    private static int escapedCharStringOperatorCount(int op) {
        return switch (op) {
            case 3, 4, 10, 11, 12, 15, 20, 24, 28, 30 -> 2;
            case 5, 9, 14, 18, 21, 26, 27, 29 -> 1;
            case 22 -> 4;
            case 23 -> 0;
            case 34 -> 7;
            case 35 -> 13;
            case 36 -> 9;
            case 37 -> 11;
            default -> -1;
        };
    }

    /** Returns {value, nextPosition} or null when malformed. */
    private static long[] readNumber(byte[] raw, int position, int end) {
        int b0 = raw[position] & 0xFF;
        if (b0 >= 32 && b0 <= 246) {
            return new long[]{b0 - 139, position + 1};
        }
        if (b0 >= 247 && b0 <= 250) {
            if (position + 1 >= end) {
                return null;
            }
            return new long[]{(b0 - 247) * 256 + (raw[position + 1] & 0xFF) + 108, position + 2};
        }
        if (b0 >= 251 && b0 <= 254) {
            if (position + 1 >= end) {
                return null;
            }
            return new long[]{-(b0 - 251) * 256 - (raw[position + 1] & 0xFF) - 108, position + 2};
        }
        if (b0 == 28) {
            if (position + 2 >= end) {
                return null;
            }
            return new long[]{
                    (short) (((raw[position + 1] & 0xFF) << 8) | (raw[position + 2] & 0xFF)),
                    position + 3
            };
        }
        if (b0 == 30) {
            int real = parseReal(raw, position + 1, end);
            if (real < 0) {
                return null;
            }
            return new long[]{0, real};
        }
        if (b0 == 255) {
            if (position + 4 >= end) {
                return null;
            }
            long value = ((long) raw[position + 1] << 24)
                    | ((raw[position + 2] & 0xFFL) << 16)
                    | ((raw[position + 3] & 0xFFL) << 8)
                    | (raw[position + 4] & 0xFFL);
            return new long[]{value, position + 5};
        }
        return null;
    }

    private static int parseReal(byte[] raw, int start, int end) {
        int position = start;
        while (position < end) {
            int b = raw[position] & 0xFF;
            int high = (b >>> 4) & 0x0F;
            if (high == 0x0F) {
                return position + 1;
            }
            if (high >= 0x0A && high != 0x0E) {
                return -1;
            }
            int low = b & 0x0F;
            if (low == 0x0F) {
                return position + 1;
            }
            if (low >= 0x0A && low != 0x0E) {
                return -1;
            }
            position++;
        }
        return -1;
    }

    private static Index parseIndex(byte[] raw, int position, int end) {
        if (position + 2 > end) {
            return null;
        }
        int count = u16(raw, position);
        position += 2;
        if (count == 0) {
            return new Index(0, new int[]{0}, position, position, position);
        }
        if (position >= end) {
            return null;
        }
        int offSize = raw[position] & 0xFF;
        position++;
        if (offSize < 1 || offSize > 4 || position + (count + 1) * offSize > end) {
            return null;
        }
        int[] offsets = new int[count + 1];
        for (int i = 0; i <= count; i++) {
            long value = 0;
            for (int j = 0; j < offSize; j++) {
                value = (value << 8) | (raw[position + i * offSize + j] & 0xFF);
            }
            offsets[i] = (int) (value - 1);
        }
        int dataOffset = position + (count + 1) * offSize;
        long dataEnd = dataOffset + (long) offsets[count] - offsets[0];
        if (offsets[0] != 0 || dataEnd > end) {
            return null;
        }
        return new Index(count, offsets, (int) dataEnd, dataOffset, (int) dataEnd);
    }

    private static Dict parseDict(byte[] raw, int start, int end, boolean topLevel) {
        int position = start;
        long[] stack = new long[MAX_STACK];
        int sp = 0;
        long charStringsOffset = -1;
        long privateSize = -1;
        long privateOffset = -1;
        long subrsOffset = -1;
        while (position < end) {
            int b0 = raw[position] & 0xFF;
            if (b0 <= 21) {
                int count = dictOperatorCount(b0, topLevel);
                if (count < 0 || sp < count) {
                    return null;
                }
                if (b0 == 17 && topLevel) {
                    charStringsOffset = stack[sp - 1];
                } else if (b0 == 18 && topLevel) {
                    privateSize = stack[sp - 2];
                    privateOffset = stack[sp - 1];
                } else if (b0 == 19 && !topLevel) {
                    subrsOffset = stack[sp - 1];
                }
                sp -= count;
                position++;
            } else if (b0 == 12) {
                if (position + 1 >= end) {
                    return null;
                }
                int count = dictEscapedOperatorCount(raw[position + 1] & 0xFF);
                if (count < 0 || sp < count) {
                    return null;
                }
                sp -= count;
                position += 2;
            } else {
                long[] number = readNumber(raw, position, end);
                if (number == null || sp >= MAX_STACK) {
                    return null;
                }
                stack[sp++] = number[0];
                position = (int) number[1];
            }
        }
        return new Dict(charStringsOffset, privateOffset, privateSize, subrsOffset);
    }

    private static int dictOperatorCount(int op, boolean topLevel) {
        return switch (op) {
            case 0, 6, 7, 8, 9, 11 -> 0;
            case 1, 2, 3, 4, 10, 13, 15, 16, 17, 20, 21 -> 1;
            case 5 -> 4;
            case 18 -> 2;
            case 19 -> topLevel ? -1 : 1;
            default -> -1;
        };
    }

    private static int dictEscapedOperatorCount(int op) {
        return switch (op) {
            case 0, 1, 2, 3, 4, 5, 6, 8, 20, 21, 22, 31, 32, 33, 34, 35, 36, 37, 38 -> 1;
            case 7 -> 4;
            case 23 -> 2;
            case 30 -> 3;
            case 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 24, 25, 26, 27, 28, 29 -> 0;
            default -> -1;
        };
    }

    private static int u16(byte[] raw, int offset) {
        return ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
    }

    private record Index(int count, int[] offsets, int nextPosition, int dataOffset, int dataEnd) {
        int offsetOf(int i) {
            return dataOffset + offsets[i];
        }
    }

    private record Dict(long charStringsOffset, long privateOffset, long privateSize, long subrsOffset) {
    }
}
