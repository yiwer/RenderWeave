package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Acceptance;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Admitted;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ColorEncoding;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FailureCode;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FailureStage;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ImageDescriptor;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Limit;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Orientation;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Rejected;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.CRC32;

final class PngAdmission {

    private static final byte[] SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private static final int TYPE_IHDR = 0x49484452;
    private static final int TYPE_PLTE = 0x504C5445;
    private static final int TYPE_IDAT = 0x49444154;
    private static final int TYPE_IEND = 0x49454E44;
    private static final int TYPE_AC_TL = 0x6163544C;
    private static final int TYPE_TRNS = 0x74524E53;
    private static final int TYPE_SRGB = 0x73524742;
    private static final int TYPE_ICCP = 0x69434350;
    private static final int TYPE_EXIF = 0x65584966; // "eXIf"

    private static final int MAX_EDGE_PIXELS = 20_000;
    private static final long MAX_TOTAL_PIXELS = 100_000_000L;

    private PngAdmission() {
    }

    static boolean looksLikePng(byte[] raw) {
        return raw.length >= 8 && Arrays.equals(SIGNATURE, Arrays.copyOf(raw, 8));
    }

    static Acceptance admit(byte[] raw) {
        Scan scan = scan(raw);
        if (scan.rejection != null) {
            return scan.rejection;
        }

        // Color contract is decided from chunk facts before decoding, because the JDK PNG
        // reader parses iCCP itself and may fail on profiles the policy already rejects.
        if (scan.srgbSeen && scan.iccpSeen) {
            return rejected(FailureCode.ASSET_CONTENT_UNSUPPORTED, FailureStage.ASSET_DESCRIPTOR, "/iCCP");
        }
        if (scan.iccpSeen) {
            // Canonical sRGB ICC byte equality lands with the frozen acceptance manifest.
            return rejected(FailureCode.ASSET_CONTENT_UNSUPPORTED, FailureStage.ASSET_DESCRIPTOR, "/iCCP");
        }

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException decodeFailure) {
            return rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_DECODE, "/IDAT");
        }
        if (image == null || image.getWidth() != scan.width || image.getHeight() != scan.height) {
            return rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_DECODE, "/IDAT");
        }

        Orientation orientation = scan.orientation == 0
                ? Orientation.IDENTITY
                : Orientation.values()[scan.orientation - 1];
        boolean swapsDimensions = scan.orientation >= 5 && scan.orientation <= 8;
        int logicalWidth = swapsDimensions ? scan.height : scan.width;
        int logicalHeight = swapsDimensions ? scan.width : scan.height;
        var descriptor = new ImageDescriptor(
                scan.width,
                scan.height,
                orientation,
                logicalWidth,
                logicalHeight,
                1,
                ColorEncoding.SRGB_8BIT
        );
        return admitted(raw, descriptor);
    }

    private static Scan scan(byte[] raw) {
        int position = 8;
        boolean first = true;
        boolean sawIdat = false;
        boolean sawIend = false;
        boolean sawPlte = false;
        boolean srgbSeen = false;
        boolean iccpSeen = false;
        int width = -1;
        int height = -1;
        int colorType = -1;
        int orientation = 0;

        while (position < raw.length) {
            if (raw.length - position < 12) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
                );
            }
            int length = u32(raw, position);
            int type = u32(raw, position + 4);
            if (raw.length - position < 12 + length) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
                );
            }
            int storedCrc = u32(raw, position + 8 + length);
            int actualCrc = crc32(raw, position + 4, 4 + length);
            if (actualCrc != storedCrc) {
                return Scan.rejected(
                        rejected(
                                FailureCode.ASSET_CONTENT_INVALID,
                                FailureStage.ASSET_STRUCTURE,
                                "/" + typeString(type)
                        )
                );
            }

            if (first) {
                if (type != TYPE_IHDR) {
                    return Scan.rejected(
                            rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
                    );
                }
                first = false;
                width = u32(raw, position + 8);
                height = u32(raw, position + 12);
                int bitDepth = raw[position + 16] & 0xFF;
                colorType = raw[position + 17] & 0xFF;
                int compression = raw[position + 18] & 0xFF;
                int filter = raw[position + 19] & 0xFF;
                int interlace = raw[position + 20] & 0xFF;
                if (compression != 0 || filter != 0 || interlace > 1) {
                    return Scan.rejected(
                            rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/ihdr")
                    );
                }
                if (width <= 0 || height <= 0) {
                    return Scan.rejected(
                            rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/ihdr")
                    );
                }
                if (width > MAX_EDGE_PIXELS || height > MAX_EDGE_PIXELS) {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/ihdr",
                                    Limit.IMAGE_EDGE_PIXELS
                            )
                    );
                }
                if ((long) width * height > MAX_TOTAL_PIXELS) {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/ihdr",
                                    Limit.IMAGE_TOTAL_PIXELS
                            )
                    );
                }
                if (bitDepth == 16) {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_UNSUPPORTED,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/ihdr"
                            )
                    );
                }
                if (!legalDepthAndColor(bitDepth, colorType)) {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_UNSUPPORTED,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/ihdr"
                            )
                    );
                }
            } else if (type == TYPE_AC_TL) {
                return Scan.rejected(
                        rejected(
                                FailureCode.ASSET_CONTENT_UNSUPPORTED,
                                FailureStage.ASSET_STRUCTURE,
                                "/acTL"
                        )
                );
            } else if (type == TYPE_PLTE) {
                if (colorType == 0 || colorType == 4) {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_INVALID,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/PLTE"
                            )
                    );
                }
                if (length == 0 || length % 3 != 0 || length > 768) {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_INVALID,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/PLTE"
                            )
                    );
                }
                sawPlte = true;
            } else if (type == TYPE_TRNS) {
                if (colorType == 4 || colorType == 6) {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_INVALID,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/tRNS"
                            )
                    );
                }
            } else if (type == TYPE_IDAT) {
                sawIdat = true;
            } else if (type == TYPE_SRGB) {
                if (length != 1 || (raw[position + 8] & 0xFF) > 3) {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_INVALID,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/sRGB"
                            )
                    );
                }
                srgbSeen = true;
            } else if (type == TYPE_ICCP) {
                iccpSeen = true;
            } else if (type == TYPE_EXIF) {
                int parsed = ExifOrientationReader.orientation(
                        Arrays.copyOfRange(raw, position + 8, position + 8 + length)
                );
                if (parsed == ExifOrientationReader.MALFORMED || parsed == ExifOrientationReader.CONFLICT) {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_INVALID,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/eXIf"
                            )
                    );
                }
                orientation = parsed;
            } else if (type == TYPE_IEND) {
                sawIend = true;
                break;
            } else if (isCritical(type)) {
                return Scan.rejected(
                        rejected(
                                FailureCode.ASSET_CONTENT_INVALID,
                                FailureStage.ASSET_STRUCTURE,
                                "/" + typeString(type)
                        )
                );
            }
            position += 12 + length;
        }

        if (!sawIend || !sawIdat) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
            );
        }
        if (colorType == 3 && !sawPlte) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/PLTE")
            );
        }
        return Scan.complete(width, height, srgbSeen, iccpSeen, orientation);
    }

    private static boolean legalDepthAndColor(int bitDepth, int colorType) {
        return switch (colorType) {
            case 0 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            case 2 -> bitDepth == 8;
            case 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            case 4, 6 -> bitDepth == 8;
            default -> false;
        };
    }

    private static boolean isCritical(int type) {
        return (type & 0x2000_0000) != 0;
    }

    private static String typeString(int type) {
        return new String(
                new byte[]{
                        (byte) (type >>> 24),
                        (byte) (type >>> 16),
                        (byte) (type >>> 8),
                        (byte) type
                },
                StandardCharsets.US_ASCII
        );
    }

    private static int u32(byte[] raw, int offset) {
        return ((raw[offset] & 0xFF) << 24)
                | ((raw[offset + 1] & 0xFF) << 16)
                | ((raw[offset + 2] & 0xFF) << 8)
                | (raw[offset + 3] & 0xFF);
    }

    private static int crc32(byte[] raw, int offset, int length) {
        var crc = new CRC32();
        crc.update(raw, offset, length);
        return (int) crc.getValue();
    }

    private static Admitted admitted(byte[] raw, ImageDescriptor descriptor) {
        return CanonicalAssetAcceptanceAuthority.admitted(AssetKind.IMAGE, raw, descriptor);
    }

    private static Rejected rejected(
            FailureCode code,
            FailureStage stage,
            String pointer
    ) {
        return new Rejected(code, stage, pointer, Optional.empty());
    }

    private static Rejected rejected(
            FailureCode code,
            FailureStage stage,
            String pointer,
            Limit limit
    ) {
        return new Rejected(code, stage, pointer, Optional.of(limit));
    }

    private record Scan(
            int width,
            int height,
            boolean srgbSeen,
            boolean iccpSeen,
            int orientation,
            Acceptance rejection
    ) {
        static Scan complete(
                int width,
                int height,
                boolean srgbSeen,
                boolean iccpSeen,
                int orientation
        ) {
            return new Scan(width, height, srgbSeen, iccpSeen, orientation, null);
        }

        static Scan rejected(Acceptance rejection) {
            return new Scan(-1, -1, false, false, 0, rejection);
        }
    }
}
