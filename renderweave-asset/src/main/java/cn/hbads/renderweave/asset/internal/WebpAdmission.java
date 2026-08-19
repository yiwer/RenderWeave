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
import java.util.Optional;

final class WebpAdmission {

    private static final int MAX_EDGE_PIXELS = 20_000;
    private static final long MAX_TOTAL_PIXELS = 100_000_000L;

    private WebpAdmission() {
    }

    static boolean looksLikeWebp(byte[] raw) {
        return raw.length >= 12
                && raw[0] == 'R'
                && raw[1] == 'I'
                && raw[2] == 'F'
                && raw[3] == 'F'
                && raw[8] == 'W'
                && raw[9] == 'E'
                && raw[10] == 'B'
                && raw[11] == 'P';
    }

    static Acceptance admit(byte[] raw) {
        Scan scan = scan(raw);
        if (scan.rejection != null) {
            return scan.rejection;
        }
        if (scan.iccpSeen) {
            // Canonical sRGB ICC byte equality lands with the frozen acceptance manifest.
            return rejected(FailureCode.ASSET_CONTENT_UNSUPPORTED, FailureStage.ASSET_DESCRIPTOR, "/ICCP");
        }

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException decodeFailure) {
            return rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_DECODE, "/");
        }
        if (image == null || image.getWidth() != scan.width || image.getHeight() != scan.height) {
            return rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_DECODE, "/");
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
        return CanonicalAssetAcceptanceAuthority.admitted(AssetKind.IMAGE, raw, descriptor);
    }

    private static Scan scan(byte[] raw) {
        if (raw.length < 12) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
            );
        }
        if (!"RIFF".equals(fourCc(raw, 0)) || !"WEBP".equals(fourCc(raw, 8))) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
            );
        }
        long riffSize = leU32(raw, 4);
        if (riffSize + 8 != raw.length) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
            );
        }

        int position = 12;
        boolean sawVp8x = false;
        boolean sawImage = false;
        boolean iccpSeen = false;
        int width = -1;
        int height = -1;
        int orientation = 0;

        while (position < raw.length) {
            if (position + 8 > raw.length) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
                );
            }
            String fourCc = fourCc(raw, position);
            long chunkSize = leU32(raw, position + 4);
            long paddedEnd = position + 8 + chunkSize + (chunkSize % 2);
            if (paddedEnd > raw.length) {
                return Scan.rejected(
                        rejected(
                                FailureCode.ASSET_CONTENT_INVALID,
                                FailureStage.ASSET_STRUCTURE,
                                "/" + fourCc.trim()
                        )
                );
            }
            if (sawImage) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
                );
            }

            switch (fourCc) {
                case "VP8X" -> {
                    if (sawVp8x || chunkSize < 10) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_INVALID,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/VP8X"
                                )
                        );
                    }
                    int flags = raw[position + 8] & 0xFF;
                    if ((flags & 0x01) != 0 || (flags & 0x40) != 0 || (flags & 0x80) != 0) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_INVALID,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/VP8X"
                                )
                        );
                    }
                    if ((flags & 0x20) != 0) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_UNSUPPORTED,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/VP8X"
                                )
                        );
                    }
                    sawVp8x = true;
                    long canvasWidth = leU24(raw, position + 12) + 1;
                    long canvasHeight = leU24(raw, position + 15) + 1;
                    if (canvasWidth > MAX_EDGE_PIXELS || canvasHeight > MAX_EDGE_PIXELS) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/VP8X",
                                        Limit.IMAGE_EDGE_PIXELS
                                )
                        );
                    }
                    if (canvasWidth * canvasHeight > MAX_TOTAL_PIXELS) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/VP8X",
                                        Limit.IMAGE_TOTAL_PIXELS
                                )
                        );
                    }
                    width = (int) canvasWidth;
                    height = (int) canvasHeight;
                }
                case "ANIM", "ANMF" -> {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_UNSUPPORTED,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/" + fourCc.trim()
                            )
                    );
                }
                case "VP8 ", "VP8L" -> {
                    if (chunkSize < (fourCc.equals("VP8L") ? 5 : 10)) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_INVALID,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/" + fourCc.trim()
                                )
                        );
                    }
                    int frameWidth;
                    int frameHeight;
                    if (fourCc.equals("VP8L")) {
                        int b0 = raw[position + 8] & 0xFF;
                        if (b0 != 0x2F) {
                            return Scan.rejected(
                                    rejected(
                                            FailureCode.ASSET_CONTENT_INVALID,
                                            FailureStage.ASSET_STRUCTURE,
                                            "/VP8L"
                                    )
                            );
                        }
                        int b1 = raw[position + 9] & 0xFF;
                        int b2 = raw[position + 10] & 0xFF;
                        int b3 = raw[position + 11] & 0xFF;
                        int b4 = raw[position + 12] & 0xFF;
                        frameWidth = (b1 | ((b2 & 0x3F) << 8)) + 1;
                        frameHeight = ((b2 >>> 6) | (b3 << 2) | ((b4 & 0x0F) << 10)) + 1;
                        if ((b4 >>> 5) != 0) {
                            return Scan.rejected(
                                    rejected(
                                            FailureCode.ASSET_CONTENT_UNSUPPORTED,
                                            FailureStage.ASSET_STRUCTURE,
                                            "/VP8L"
                                    )
                            );
                        }
                    } else {
                        int frameTag = raw[position + 8] & 0xFF;
                        if ((frameTag & 0x01) != 0 || (frameTag & 0x0E) != 0 || (frameTag & 0x80) != 0) {
                            return Scan.rejected(
                                    rejected(
                                            FailureCode.ASSET_CONTENT_UNSUPPORTED,
                                            FailureStage.ASSET_STRUCTURE,
                                            "/VP8 "
                                    )
                            );
                        }
                        if ((raw[position + 11] & 0xFF) != 0x9D
                                || (raw[position + 12] & 0xFF) != 0x01
                                || (raw[position + 13] & 0xFF) != 0x2A) {
                            return Scan.rejected(
                                    rejected(
                                            FailureCode.ASSET_CONTENT_INVALID,
                                            FailureStage.ASSET_STRUCTURE,
                                            "/VP8 "
                                    )
                            );
                        }
                        // Simple-format VP8 headers carry the frame dimensions directly.
                        frameWidth = ((raw[position + 14] & 0xFF)
                                | ((raw[position + 15] & 0x3F) << 8));
                        frameHeight = ((raw[position + 16] & 0xFF)
                                | ((raw[position + 17] & 0x3F) << 8));
                    }
                    if (frameWidth <= 0 || frameHeight <= 0) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_INVALID,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/" + fourCc.trim()
                                )
                        );
                    }
                    if (sawVp8x && (frameWidth != width || frameHeight != height)) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_INVALID,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/" + fourCc.trim()
                                )
                        );
                    }
                    if (!sawVp8x) {
                        width = frameWidth;
                        height = frameHeight;
                    }
                    if (frameWidth > MAX_EDGE_PIXELS || frameHeight > MAX_EDGE_PIXELS) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/" + fourCc.trim(),
                                        Limit.IMAGE_EDGE_PIXELS
                                )
                        );
                    }
                    if ((long) frameWidth * frameHeight > MAX_TOTAL_PIXELS) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/" + fourCc.trim(),
                                        Limit.IMAGE_TOTAL_PIXELS
                                )
                        );
                    }
                    sawImage = true;
                }
                case "ICCP" -> iccpSeen = true;
                case "EXIF" -> {
                    int parsed = ExifOrientationReader.orientation(
                            copyOf(raw, position + 8, (int) chunkSize)
                    );
                    if (parsed == ExifOrientationReader.MALFORMED || parsed == ExifOrientationReader.CONFLICT) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_INVALID,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/EXIF"
                                )
                        );
                    }
                    if (parsed != 0) {
                        if (orientation != 0 && orientation != parsed) {
                            return Scan.rejected(
                                    rejected(
                                            FailureCode.ASSET_CONTENT_INVALID,
                                            FailureStage.ASSET_STRUCTURE,
                                            "/EXIF"
                                    )
                            );
                        }
                        orientation = parsed;
                    }
                }
                case "ALPH", "XMP " -> {
                    // Structural metadata: no technical semantics beyond the decode itself.
                }
                default -> {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_INVALID,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/" + fourCc.trim()
                            )
                    );
                }
            }
            position = (int) paddedEnd;
        }

        if (!sawImage) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
            );
        }
        return Scan.complete(width, height, iccpSeen, orientation);
    }

    private static String fourCc(byte[] raw, int offset) {
        return new String(raw, offset, 4, StandardCharsets.US_ASCII);
    }

    private static byte[] copyOf(byte[] raw, int offset, int length) {
        var copy = new byte[length];
        System.arraycopy(raw, offset, copy, 0, length);
        return copy;
    }

    private static long leU32(byte[] raw, int offset) {
        return (raw[offset] & 0xFFL)
                | ((raw[offset + 1] & 0xFFL) << 8)
                | ((raw[offset + 2] & 0xFFL) << 16)
                | ((raw[offset + 3] & 0xFFL) << 24);
    }

    private static long leU24(byte[] raw, int offset) {
        return (raw[offset] & 0xFFL)
                | ((raw[offset + 1] & 0xFFL) << 8)
                | ((raw[offset + 2] & 0xFFL) << 16);
    }

    private static Admitted admitted(byte[] raw, ImageDescriptor descriptor) {
        return CanonicalAssetAcceptanceAuthority.admitted(AssetKind.IMAGE, raw, descriptor);
    }

    private static Rejected rejected(FailureCode code, FailureStage stage, String pointer) {
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

    private record Scan(int width, int height, boolean iccpSeen, int orientation, Acceptance rejection) {
        static Scan complete(int width, int height, boolean iccpSeen, int orientation) {
            return new Scan(width, height, iccpSeen, orientation, null);
        }

        static Scan rejected(Acceptance rejection) {
            return new Scan(-1, -1, false, 0, rejection);
        }
    }
}
