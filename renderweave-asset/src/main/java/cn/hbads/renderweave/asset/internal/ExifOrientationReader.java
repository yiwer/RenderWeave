package cn.hbads.renderweave.asset.internal;

/**
 * Minimal TIFF/IFD0 walker that extracts the EXIF orientation tag (0x0112) from a raw EXIF
 * profile byte block. Returns 0 when no orientation tag is present; returns a value outside
 * 1..8 only through the malformed/conflict error markers used by callers.
 */
final class ExifOrientationReader {

    static final int MALFORMED = -1;
    static final int CONFLICT = -2;

    private ExifOrientationReader() {
    }

    static int orientation(byte[] data) {
        if (data.length < 8) {
            return MALFORMED;
        }
        try {
            return parse(data);
        } catch (ArrayIndexOutOfBoundsException outOfBounds) {
            return MALFORMED;
        }
    }

    private static int parse(byte[] data) {
        boolean littleEndian;
        if (data[0] == 'I' && data[1] == 'I') {
            littleEndian = true;
        } else if (data[0] == 'M' && data[1] == 'M') {
            littleEndian = false;
        } else {
            return MALFORMED;
        }
        if (u16(data, 2, littleEndian) != 0x002A) {
            return MALFORMED;
        }
        long ifd0 = u32(data, 4, littleEndian);
        if (ifd0 > data.length - 2) {
            return MALFORMED;
        }
        int entryCount = u16(data, (int) ifd0, littleEndian);
        long offset = ifd0 + 2;
        int orientation = 0;
        for (int i = 0; i < entryCount; i++) {
            if (offset > data.length - 12) {
                return MALFORMED;
            }
            int tag = u16(data, (int) offset, littleEndian);
            int type = u16(data, (int) offset + 2, littleEndian);
            long count = u32(data, (int) offset + 4, littleEndian);
            long valueOffset = offset + 8;
            if (tag == 0x0112) {
                if (type != 0x0003 || count != 1) {
                    return MALFORMED;
                }
                int value = u16(data, (int) valueOffset, littleEndian);
                if (orientation != 0 && orientation != value) {
                    return CONFLICT;
                }
                orientation = value;
            }
            offset += 12;
        }
        if (orientation == 0) {
            return 0; // no orientation tag: identity
        }
        if (orientation < 1 || orientation > 8) {
            return MALFORMED;
        }
        return orientation;
    }

    private static int u16(byte[] data, int offset, boolean littleEndian) {
        if (offset < 0 || offset > data.length - 2) {
            throw new ArrayIndexOutOfBoundsException(offset);
        }
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        return littleEndian ? (b0 | (b1 << 8)) : ((b0 << 8) | b1);
    }

    private static long u32(byte[] data, int offset, boolean littleEndian) {
        if (offset < 0 || offset > data.length - 4) {
            throw new ArrayIndexOutOfBoundsException(offset);
        }
        long b0 = data[offset] & 0xFFL;
        long b1 = data[offset + 1] & 0xFFL;
        long b2 = data[offset + 2] & 0xFFL;
        long b3 = data[offset + 3] & 0xFFL;
        return littleEndian
                ? (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24))
                : ((b0 << 24) | (b1 << 16) | (b2 << 8) | b3);
    }
}
