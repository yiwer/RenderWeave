package cn.hbads.renderweave.inference.input;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Decodes bounded PNG/JPEG input and emits metadata-free sRGB PNG bytes. */
public final class ImageNormalizer {
    public static final int MAX_LONG_EDGE = 4096;
    public static final long MAX_NORMALIZED_PIXELS = 16_000_000L;
    public static final int MAX_SOURCE_LONG_EDGE = 65_535;
    public static final long MAX_SOURCE_PIXELS = 25_000_000L;
    private static final long MAX_DECODE_PIXELS = 20_000_000L;
    private static final int PNG_SIGNATURE_BYTES = 8;

    public NormalizedImage normalize(InferenceInput.BinaryInput input) {
        var bytes = input.bytes();
        var format = detectFormat(bytes);
        var expectedMediaType = format == Format.PNG ? "image/png" : "image/jpeg";
        if (!expectedMediaType.equalsIgnoreCase(input.mediaType())) {
            throw new InvalidInferenceInputException(
                    "INFERENCE_IMAGE_MEDIA_TYPE_MISMATCH", "/images",
                    java.util.Map.of("declaredMediaType", input.mediaType(), "detectedMediaType", expectedMediaType),
                    "Declared image media type does not match decoded magic bytes", null
            );
        }
        if (format == Format.PNG && containsPngChunk(bytes, "acTL")) {
            throw invalid("INFERENCE_IMAGE_ANIMATED", "Animated PNG is not supported");
        }

        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid("INFERENCE_IMAGE_DECODE_FAILED", "Image format has no decoder");
            var reader = readers.next();
            try {
                reader.setInput(stream, false, true);
                var width = reader.getWidth(0);
                var height = reader.getHeight(0);
                var sourcePixels = (long) width * height;
                if (width < 1 || height < 1
                        || Math.max(width, height) > MAX_SOURCE_LONG_EDGE
                        || sourcePixels > MAX_SOURCE_PIXELS) {
                    throw new InvalidInferenceInputException(
                            "INFERENCE_IMAGE_DIMENSIONS_INVALID",
                            "/images",
                            java.util.Map.of(
                                    "width", width,
                                    "height", height,
                                    "maximumSourceLongEdge", MAX_SOURCE_LONG_EDGE,
                                    "maximumSourcePixels", MAX_SOURCE_PIXELS,
                                    "normalizedMaximumLongEdge", MAX_LONG_EDGE
                            ),
                            "Image dimensions exceed the supported boundary",
                            null
                    );
                }
                rejectMultipleFrames(reader);
                var readParameters = reader.getDefaultReadParam();
                var subsampling = sourceSubsampling(width, height);
                if (subsampling > 1) {
                    readParameters.setSourceSubsampling(subsampling, subsampling, 0, 0);
                }
                var decoded = reader.read(0, readParameters);
                if (decoded == null) throw invalid("INFERENCE_IMAGE_DECODE_FAILED", "Image decoder returned no pixels");
                var orientation = format == Format.JPEG ? jpegExifOrientation(bytes) : 1;
                var oriented = orient(decoded, orientation);
                var normalized = drawIntoSrgbAndFit(
                        oriented, format == Format.PNG && oriented.getColorModel().hasAlpha()
                );
                return new NormalizedImage(
                        encodePngInMemory(normalized), normalized.getWidth(), normalized.getHeight()
                );
            } finally {
                reader.dispose();
            }
        } catch (InvalidInferenceInputException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new InvalidInferenceInputException(
                    "INFERENCE_IMAGE_DECODE_FAILED", "/images", java.util.Map.of(),
                    "Image bytes cannot be decoded safely", exception
            );
        }
    }

    private static byte[] encodePngInMemory(BufferedImage image) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) throw new IOException("PNG encoder is unavailable");
        var output = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(output)) {
            var writer = writers.next();
            try {
                writer.setOutput(stream);
                writer.write(image);
                stream.flush();
            } finally {
                writer.dispose();
            }
        }
        return output.toByteArray();
    }

    private static void rejectMultipleFrames(javax.imageio.ImageReader reader) throws IOException {
        try {
            if (reader.getNumImages(true) != 1) {
                throw invalid("INFERENCE_IMAGE_ANIMATED", "Animated images are not supported");
            }
        } catch (UnsupportedOperationException ignored) {
            // PNG/JPEG readers may not expose a cheap count; APNG is rejected by its animation chunk.
        }
    }

    private static Format detectFormat(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && (bytes[4] & 0xff) == 0x0d && (bytes[5] & 0xff) == 0x0a
                && (bytes[6] & 0xff) == 0x1a && (bytes[7] & 0xff) == 0x0a) {
            return Format.PNG;
        }
        if (bytes.length >= 4 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[bytes.length - 2] & 0xff) == 0xff && (bytes[bytes.length - 1] & 0xff) == 0xd9) {
            return Format.JPEG;
        }
        throw invalid("INFERENCE_IMAGE_FORMAT_UNSUPPORTED", "Only PNG and JPEG magic bytes are accepted");
    }

    private static boolean containsPngChunk(byte[] bytes, String expectedType) {
        var offset = PNG_SIGNATURE_BYTES;
        while (offset + 12 <= bytes.length) {
            var length = readBigEndianInt(bytes, offset);
            if (length < 0 || offset + 12L + length > bytes.length) return false;
            var type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            if (expectedType.equals(type)) return true;
            offset += 12 + length;
            if ("IEND".equals(type)) return false;
        }
        return false;
    }

    private static int readBigEndianInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    static int sourceSubsampling(int width, int height) {
        var pixels = (long) width * height;
        if (pixels <= MAX_DECODE_PIXELS) return 1;
        return Math.max(1, (int) Math.ceil(Math.sqrt(pixels / (double) MAX_DECODE_PIXELS)));
    }

    private static BufferedImage drawIntoSrgbAndFit(BufferedImage source, boolean alpha) {
        var dimensions = targetDimensions(source.getWidth(), source.getHeight());
        var targetWidth = dimensions[0];
        var targetHeight = dimensions[1];
        var target = new BufferedImage(
                targetWidth, targetHeight,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB
        );
        var graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY
            );
            graphics.setRenderingHint(
                    RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY
            );
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    static int[] targetDimensions(int width, int height) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }
        var longEdgeScale = MAX_LONG_EDGE / (double) Math.max(width, height);
        var pixelScale = Math.sqrt(MAX_NORMALIZED_PIXELS / ((double) width * height));
        var scale = Math.min(1.0, Math.min(longEdgeScale, pixelScale));
        return new int[]{
                Math.max(1, (int) Math.floor(width * scale)),
                Math.max(1, (int) Math.floor(height * scale))
        };
    }

    private static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation <= 1 || orientation > 8) return source;
        var swap = orientation >= 5;
        var target = new BufferedImage(
                swap ? source.getHeight() : source.getWidth(),
                swap ? source.getWidth() : source.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );
        var width = source.getWidth();
        var height = source.getHeight();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                int targetX;
                int targetY;
                switch (orientation) {
                    case 2 -> { targetX = width - 1 - x; targetY = y; }
                    case 3 -> { targetX = width - 1 - x; targetY = height - 1 - y; }
                    case 4 -> { targetX = x; targetY = height - 1 - y; }
                    case 5 -> { targetX = y; targetY = x; }
                    case 6 -> { targetX = height - 1 - y; targetY = x; }
                    case 7 -> { targetX = height - 1 - y; targetY = width - 1 - x; }
                    case 8 -> { targetX = y; targetY = width - 1 - x; }
                    default -> { targetX = x; targetY = y; }
                }
                target.setRGB(targetX, targetY, source.getRGB(x, y));
            }
        }
        return target;
    }

    private static int jpegExifOrientation(byte[] bytes) {
        var offset = 2;
        while (offset + 4 <= bytes.length) {
            if ((bytes[offset] & 0xff) != 0xff) break;
            var marker = bytes[offset + 1] & 0xff;
            offset += 2;
            if (marker == 0xd9 || marker == 0xda) break;
            if (marker == 0x01 || marker >= 0xd0 && marker <= 0xd7) continue;
            if (offset + 2 > bytes.length) break;
            var segmentLength = ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
            if (segmentLength < 2 || offset + segmentLength > bytes.length) break;
            if (marker == 0xe1 && segmentLength >= 14
                    && bytes[offset + 2] == 'E' && bytes[offset + 3] == 'x'
                    && bytes[offset + 4] == 'i' && bytes[offset + 5] == 'f'
                    && bytes[offset + 6] == 0 && bytes[offset + 7] == 0) {
                var orientation = readTiffOrientation(bytes, offset + 8, segmentLength - 8);
                if (orientation >= 1 && orientation <= 8) return orientation;
            }
            offset += segmentLength;
        }
        return 1;
    }

    private static int readTiffOrientation(byte[] bytes, int base, int length) {
        if (length < 8 || base + length > bytes.length) return 1;
        var littleEndian = bytes[base] == 'I' && bytes[base + 1] == 'I';
        if (!littleEndian && !(bytes[base] == 'M' && bytes[base + 1] == 'M')) return 1;
        var order = littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        var buffer = ByteBuffer.wrap(bytes).order(order);
        var ifdOffset = buffer.getInt(base + 4);
        var ifd = base + ifdOffset;
        if (ifdOffset < 0 || ifd + 2 > base + length) return 1;
        var entries = Short.toUnsignedInt(buffer.getShort(ifd));
        for (var index = 0; index < entries; index++) {
            var entry = ifd + 2 + index * 12;
            if (entry + 12 > base + length) return 1;
            if (Short.toUnsignedInt(buffer.getShort(entry)) == 0x0112
                    && Short.toUnsignedInt(buffer.getShort(entry + 2)) == 3
                    && buffer.getInt(entry + 4) >= 1) {
                return Short.toUnsignedInt(buffer.getShort(entry + 8));
            }
        }
        return 1;
    }

    private static InvalidInferenceInputException invalid(String code, String message) {
        return new InvalidInferenceInputException(code, "/images", message);
    }

    private enum Format { PNG, JPEG }
}
