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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class JpegAdmission {

    private static final int SOF0 = 0xC0;
    private static final int SOF2 = 0xC2;
    private static final int DHT = 0xC4;
    private static final int DQT = 0xDB;
    private static final int DRI = 0xDD;
    private static final int SOS = 0xDA;
    private static final int EOI = 0xD9;
    private static final int SOI = 0xD8;
    private static final int TEM = 0x01;
    private static final int COM = 0xFE;

    private JpegAdmission() {
    }

    static boolean looksLikeJpeg(byte[] raw) {
        return raw.length >= 3
                && (raw[0] & 0xFF) == 0xFF
                && (raw[1] & 0xFF) == SOI
                && (raw[2] & 0xFF) == 0xFF;
    }

    static Acceptance admit(byte[] raw) {
        Scan scan = scan(raw);
        if (scan.rejection != null) {
            return scan.rejection;
        }

        if (scan.iccSegments != null && !scan.iccSegments.isEmpty()) {
            byte[] assembled = assembleIcc(scan.iccSegments);
            if (assembled == null || !IccPolicy.isCanonicalSrgb(assembled)) {
                return rejected(FailureCode.ASSET_CONTENT_UNSUPPORTED, FailureStage.ASSET_DESCRIPTOR, "/ICC");
            }
        }

        BufferedImage image;
        try {
            // Pixel decode does not need APP/COM metadata segments; the structure scan above
            // already validated and consumed them.
            image = ImageIO.read(new ByteArrayInputStream(stripMetadataSegments(raw)));
        } catch (IOException decodeFailure) {
            return rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_DECODE, "/SOS");
        }
        if (image == null || image.getWidth() != scan.width || image.getHeight() != scan.height) {
            return rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_DECODE, "/SOS");
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
        int position = 2;
        boolean sawSof = false;
        boolean sawSos = false;
        boolean sawEoi = false;
        int width = -1;
        int height = -1;
        int components = -1;
        int adobeTransform = -1;
        int orientation = 0;
        int iccCount = -1;
        Map<Integer, byte[]> iccSegments = null;

        while (position < raw.length) {
            if ((raw[position] & 0xFF) != 0xFF || position + 1 >= raw.length) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
                );
            }
            int marker = raw[position + 1] & 0xFF;
            if (marker == 0x00 || marker == 0xFF) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
                );
            }
            if (marker == EOI) {
                sawEoi = true;
                position += 2;
                break;
            }
            if (marker == SOI) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
                );
            }
            if (marker >= 0xD0 && marker <= 0xD7) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
                );
            }
            if (marker == TEM) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_UNSUPPORTED, FailureStage.ASSET_STRUCTURE, "/")
                );
            }
            if (position + 4 > raw.length) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
                );
            }
            int length = u16(raw, position + 2);
            if (length < 2 || position + 2 + length > raw.length) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
                );
            }
            byte[] payload = Arrays.copyOfRange(raw, position + 4, position + 2 + length);

            switch (marker) {
                case SOF0, SOF2 -> {
                    if (sawSof) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_UNSUPPORTED,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/sof"
                                )
                        );
                    }
                    Scan frame = parseFrame(payload);
                    if (frame.rejection != null) {
                        return frame;
                    }
                    sawSof = true;
                    width = frame.width;
                    height = frame.height;
                    components = frame.components;
                }
                case 0xC1, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF ->
                        Scan.rejected(rejected(
                                FailureCode.ASSET_CONTENT_UNSUPPORTED,
                                FailureStage.ASSET_STRUCTURE,
                                "/sof"
                        ));
                case DHT -> {
                    var table = parseDht(payload);
                    if (table != null) {
                        return table;
                    }
                }
                case DQT -> {
                    var table = parseDqt(payload);
                    if (table != null) {
                        return table;
                    }
                }
                case DRI -> {
                    if (payload.length != 2) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_INVALID,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/dri"
                                )
                        );
                    }
                }
                case 0xCC -> {
                    return Scan.rejected(rejected(
                            FailureCode.ASSET_CONTENT_UNSUPPORTED,
                            FailureStage.ASSET_STRUCTURE,
                            "/dac"
                    ));
                }
                case 0xDC, 0xDE, 0xDF -> Scan.rejected(rejected(
                        FailureCode.ASSET_CONTENT_UNSUPPORTED,
                        FailureStage.ASSET_STRUCTURE,
                        "/"
                ));
                case SOS -> {
                    if (!sawSof) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_INVALID,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/"
                                )
                        );
                    }
                    int componentCount = payload[0] & 0xFF;
                    if (payload.length != 1 + componentCount * 2 + 3) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_INVALID,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/sos"
                                )
                        );
                    }
                    sawSos = true;
                    position += 2 + length;
                    int nextMarker = skipEntropy(raw, position);
                    if (nextMarker < 0) {
                        return Scan.rejected(
                                rejected(
                                        FailureCode.ASSET_CONTENT_INVALID,
                                        FailureStage.ASSET_STRUCTURE,
                                        "/"
                                )
                        );
                    }
                    position = nextMarker;
                    continue;
                }
                case 0xE0, 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7,
                        0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xED, 0xEE, 0xEF -> {
                    var app = parseApp(payload, orientation, adobeTransform, iccCount, iccSegments);
                    if (app.rejection != null) {
                        return app;
                    }
                    orientation = app.orientation;
                    adobeTransform = app.adobeTransform;
                    iccCount = app.iccCount;
                    iccSegments = app.iccSegments;
                }
                case COM -> {
                    // Comment: no technical semantics.
                }
                default -> {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_INVALID,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/"
                            )
                    );
                }
            }
            position += 2 + length;
        }

        if (!sawSof || !sawSos || !sawEoi) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/")
            );
        }
        if (iccCount > 0 && iccSegments.size() != iccCount) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/app2")
            );
        }
        if (components == 3 && adobeTransform == 0) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_UNSUPPORTED, FailureStage.ASSET_STRUCTURE, "/sof")
            );
        }
        if (adobeTransform == 2) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_UNSUPPORTED, FailureStage.ASSET_STRUCTURE, "/sof")
            );
        }
        return Scan.complete(width, height, orientation, iccSegments);
    }

    private static Scan parseFrame(byte[] payload) {
        if (payload.length < 6) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/sof")
            );
        }
        int precision = payload[0] & 0xFF;
        if (precision != 8) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_UNSUPPORTED, FailureStage.ASSET_STRUCTURE, "/sof")
            );
        }
        int height = u16(payload, 1);
        int width = u16(payload, 3);
        int components = payload[5] & 0xFF;
        if (components == 0 || payload.length != 6 + components * 3) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/sof")
            );
        }
        if (components != 1 && components != 3) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_UNSUPPORTED, FailureStage.ASSET_STRUCTURE, "/sof")
            );
        }
        if (width <= 0 || height <= 0) {
            return Scan.rejected(
                    rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/sof")
            );
        }
        var capacityLimit = AssetContentCapacityGuard.imageDimensionsExceeded(width, height);
        if (capacityLimit.isPresent()) {
            return Scan.rejected(
                    rejected(
                            FailureCode.ASSET_CONTENT_LIMIT_EXCEEDED,
                            FailureStage.ASSET_STRUCTURE,
                            "/sof",
                            capacityLimit.orElseThrow()
                    )
            );
        }
        return Scan.frame(width, height, components);
    }

    private static Scan parseDht(byte[] payload) {
        int offset = 0;
        while (offset < payload.length) {
            int classAndId = payload[offset] & 0xFF;
            offset++;
            int tableClass = classAndId >>> 4;
            int tableId = classAndId & 0x0F;
            if (tableClass > 1 || tableId > 3) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/dht")
                );
            }
            if (offset + 16 > payload.length) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/dht")
                );
            }
            int symbolCount = 0;
            for (int i = 0; i < 16; i++) {
                symbolCount += payload[offset + i] & 0xFF;
            }
            offset += 16;
            if (symbolCount > 256 || offset + symbolCount > payload.length) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/dht")
                );
            }
            offset += symbolCount;
        }
        return null;
    }

    private static Scan parseDqt(byte[] payload) {
        int offset = 0;
        while (offset < payload.length) {
            int precisionAndId = payload[offset] & 0xFF;
            offset++;
            if ((precisionAndId >>> 4) != 0) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_UNSUPPORTED, FailureStage.ASSET_STRUCTURE, "/dqt")
                );
            }
            offset += 64;
            if (offset > payload.length) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/dqt")
                );
            }
        }
        return null;
    }

    private static Scan parseApp(
            byte[] payload,
            int orientation,
            int adobeTransform,
            int iccCount,
            Map<Integer, byte[]> iccSegments
    ) {
        if (payload.length >= 6
                && payload[0] == 'E' && payload[1] == 'x' && payload[2] == 'i'
                && payload[3] == 'f' && payload[4] == 0x00 && payload[5] == 0x00) {
            int parsed = ExifOrientationReader.orientation(Arrays.copyOfRange(payload, 6, payload.length));
            if (parsed == ExifOrientationReader.MALFORMED || parsed == ExifOrientationReader.CONFLICT) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/app1")
                );
            }
            if (parsed != 0) {
                if (orientation != 0 && orientation != parsed) {
                    return Scan.rejected(
                            rejected(
                                    FailureCode.ASSET_CONTENT_INVALID,
                                    FailureStage.ASSET_STRUCTURE,
                                    "/app1"
                            )
                    );
                }
                orientation = parsed;
            }
            return Scan.app(orientation, adobeTransform, iccCount, iccSegments);
        }
        if (payload.length >= 12
                && payload[0] == 'I' && payload[1] == 'C' && payload[2] == 'C'
                && payload[3] == '_' && payload[4] == 'P' && payload[5] == 'R'
                && payload[6] == 'O' && payload[7] == 'F' && payload[8] == 'I'
                && payload[9] == 'L' && payload[10] == 'E' && payload[11] == 0x00) {
            int sequence = payload[12] & 0xFF;
            int count = payload[13] & 0xFF;
            if (count == 0 || sequence < 1 || sequence > count) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/app2")
                );
            }
            if (iccCount == -1) {
                iccCount = count;
            } else if (iccCount != count) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/app2")
                );
            }
            if (iccSegments == null) {
                iccSegments = new HashMap<>();
            }
            if (iccSegments.containsKey(sequence)) {
                return Scan.rejected(
                        rejected(FailureCode.ASSET_CONTENT_INVALID, FailureStage.ASSET_STRUCTURE, "/app2")
                );
            }
            iccSegments.put(sequence, Arrays.copyOfRange(payload, 14, payload.length));
            return Scan.app(orientation, adobeTransform, iccCount, iccSegments);
        }
        if (payload.length >= 12
                && payload[0] == 'A' && payload[1] == 'd' && payload[2] == 'o'
                && payload[3] == 'b' && payload[4] == 'e') {
            adobeTransform = payload[11] & 0xFF;
        }
        return Scan.app(orientation, adobeTransform, iccCount, iccSegments);
    }

    private static int skipEntropy(byte[] raw, int position) {
        while (position < raw.length) {
            if ((raw[position] & 0xFF) != 0xFF) {
                position++;
                continue;
            }
            if (position + 1 >= raw.length) {
                return -1;
            }
            int next = raw[position + 1] & 0xFF;
            if (next == 0x00) {
                position += 2;
                continue;
            }
            if (next == 0xFF) {
                position += 1;
                continue;
            }
            if (next >= 0xD0 && next <= 0xD7) {
                position += 2;
                continue;
            }
            return position;
        }
        return -1;
    }

    private static byte[] stripMetadataSegments(byte[] raw) {
        var out = new ByteArrayOutputStream(raw.length);
        int position = 0;
        while (position < raw.length) {
            if ((raw[position] & 0xFF) != 0xFF || position + 1 >= raw.length) {
                out.write(raw, position, raw.length - position);
                break;
            }
            int marker = raw[position + 1] & 0xFF;
            if (marker == 0x00 || marker == 0xFF || marker == SOS) {
                // Entropy and non-segment markers are copied verbatim.
                out.write(raw, position, raw.length - position);
                break;
            }
            if (marker >= 0xE0 && marker <= 0xEF || marker == COM) {
                if (position + 4 > raw.length) {
                    out.write(raw, position, raw.length - position);
                    break;
                }
                int length = u16(raw, position + 2);
                if (length < 2 || position + 2 + length > raw.length) {
                    out.write(raw, position, raw.length - position);
                    break;
                }
                position += 2 + length;
                continue;
            }
            out.write(raw, position, raw.length - position);
            break;
        }
        return out.toByteArray();
    }

    private static byte[] assembleIcc(Map<Integer, byte[]> segments) {
        long total = 0;
        for (byte[] segment : segments.values()) {
            total += segment.length;
        }
        if (total > 16 * 1024 * 1024) {
            return null;
        }
        var out = new java.io.ByteArrayOutputStream((int) total);
        for (int sequence = 1; sequence <= segments.size(); sequence++) {
            byte[] segment = segments.get(sequence);
            if (segment == null) {
                return null;
            }
            out.writeBytes(segment);
        }
        return out.toByteArray();
    }

    private static int u16(byte[] raw, int offset) {
        return ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
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

    private record Scan(
            int width,
            int height,
            int orientation,
            Map<Integer, byte[]> iccSegments,
            int adobeTransform,
            int iccCount,
            int components,
            Acceptance rejection
    ) {
        static Scan frame(int width, int height, int components) {
            return new Scan(width, height, 0, null, -1, -1, components, null);
        }

        static Scan complete(
                int width,
                int height,
                int orientation,
                Map<Integer, byte[]> iccSegments
        ) {
            return new Scan(width, height, orientation, iccSegments, -1, -1, -1, null);
        }

        static Scan app(
                int orientation,
                int adobeTransform,
                int iccCount,
                Map<Integer, byte[]> iccSegments
        ) {
            return new Scan(-1, -1, orientation, iccSegments, adobeTransform, iccCount, -1, null);
        }

        static Scan rejected(Acceptance rejection) {
            return new Scan(-1, -1, 0, null, -1, -1, -1, rejection);
        }
    }
}
