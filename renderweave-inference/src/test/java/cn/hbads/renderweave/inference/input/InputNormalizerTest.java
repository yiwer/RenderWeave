package cn.hbads.renderweave.inference.input;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputNormalizerTest {

    @Test
    void requiresExplicitExternalTransferConfirmationBeforeWritingAnything() {
        var store = new MemoryBlobStore();
        var error = assertThrows(InvalidInferenceInputException.class, () -> new InputNormalizer(store).normalize(
                input(InferenceMode.JSON_ONLY, false, List.of(), List.of(json("{\"name\":\"Ada\"}")))
        ));

        assertEquals("INFERENCE_EXTERNAL_TRANSFER_NOT_CONFIRMED", error.code());
        assertEquals("/externalTransferConfirmed", error.pointer());
        assertTrue(store.bytesByLocator.isEmpty());
    }

    @Test
    void enforcesTheInputCountContractForAllThreeModes() throws Exception {
        var png = image(2, 1, Color.BLUE, "png");
        var sample = json("{\"name\":\"Ada\"}");
        var cases = List.of(
                input(InferenceMode.IMAGE_ONLY, true, List.of(), List.of()),
                input(InferenceMode.IMAGE_ONLY, true, List.of(png), List.of(sample)),
                input(InferenceMode.JSON_ONLY, true, List.of(png), List.of(sample)),
                input(InferenceMode.COMBINED, true, List.of(png), List.of()),
                input(InferenceMode.COMBINED, true, List.of(), List.of(sample))
        );

        for (var invalid : cases) {
            var error = assertThrows(InvalidInferenceInputException.class,
                    () -> new InputNormalizer(new MemoryBlobStore()).normalize(invalid));
            assertEquals("INFERENCE_INPUT_COUNT_INVALID", error.code());
        }
    }

    @Test
    void acceptsOnlyDecodedPngOrJpegMagicRejectsAnimationAndDownscalesOversizedDimensions() throws Exception {
        var unsupported = assertThrows(InvalidInferenceInputException.class,
                () -> normalizeImage(new InferenceInput.BinaryInput(
                        "fake.png", "image/png", "not-a-png".getBytes(StandardCharsets.UTF_8)
                )));
        assertEquals("INFERENCE_IMAGE_FORMAT_UNSUPPORTED", unsupported.code());

        var mismatched = assertThrows(InvalidInferenceInputException.class,
                () -> normalizeImage(binary(
                        "real.png", "image/jpeg", imageBytes(2, 1, Color.BLUE, "png")
                )));
        assertEquals("INFERENCE_IMAGE_MEDIA_TYPE_MISMATCH", mismatched.code());

        var apng = assertThrows(InvalidInferenceInputException.class,
                () -> normalizeImage(binary("animated.png", "image/png", addEmptyPngChunk(
                        imageBytes(2, 1, Color.BLUE, "png"), "acTL"
                ))));
        assertEquals("INFERENCE_IMAGE_ANIMATED", apng.code());

        var tooWide = normalizeImage(image(4097, 1, Color.BLUE, "png"));
        var normalized = tooWide.artifacts().getFirst();
        assertEquals(4096, normalized.width());
        assertEquals(1, normalized.height());

        var extreme = assertThrows(InvalidInferenceInputException.class, () -> normalizeImage(binary(
                "extreme.png", "image/png", withPngDimensions(
                        imageBytes(1, 1, Color.BLUE, "png"), ImageNormalizer.MAX_SOURCE_LONG_EDGE + 1, 1
                )
        )));
        assertEquals("INFERENCE_IMAGE_DIMENSIONS_INVALID", extreme.code());
        assertEquals(ImageNormalizer.MAX_SOURCE_LONG_EDGE + 1, extreme.args().get("width"));
    }

    @Test
    void enforcesCountItemAndBatchByteLimitsBeforeParsingOrDecoding() throws Exception {
        var elevenImages = java.util.stream.IntStream.range(0, InputNormalizer.MAX_IMAGES + 1)
                .mapToObj(index -> binary("image-" + index + ".png", "image/png", new byte[]{1}))
                .toList();
        var imageCount = assertThrows(InvalidInferenceInputException.class, () -> new InputNormalizer(
                new MemoryBlobStore()).normalize(input(InferenceMode.IMAGE_ONLY, true, elevenImages, List.of())));
        assertEquals("INFERENCE_INPUT_COUNT_INVALID", imageCount.code());

        var largeImage = binary(
                "large.png", "image/png", new byte[InputNormalizer.MAX_IMAGE_BYTES + 1]
        );
        var imageSize = assertThrows(InvalidInferenceInputException.class, () -> normalizeImage(largeImage));
        assertEquals("INFERENCE_IMAGE_SIZE_INVALID", imageSize.code());

        var batchImages = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> binary(
                        "batch-" + index + ".png", "image/png", new byte[8 * 1024 * 1024]
                ))
                .toList();
        var imageBatch = assertThrows(InvalidInferenceInputException.class, () -> new InputNormalizer(
                new MemoryBlobStore()).normalize(input(InferenceMode.IMAGE_ONLY, true, batchImages, List.of())));
        assertEquals("INFERENCE_IMAGE_BATCH_TOO_LARGE", imageBatch.code());

        var largeJson = binary(
                "large.json", "application/json", new byte[InputNormalizer.MAX_JSON_SAMPLE_BYTES + 1]
        );
        var jsonSize = assertThrows(InvalidInferenceInputException.class, () -> new InputNormalizer(
                new MemoryBlobStore()).normalize(input(InferenceMode.JSON_ONLY, true, List.of(), List.of(largeJson))));
        assertEquals("INFERENCE_JSON_SIZE_INVALID", jsonSize.code());

        var batchJson = java.util.stream.IntStream.range(0, 9)
                .mapToObj(index -> binary(
                        "batch-" + index + ".json", "application/json", new byte[240 * 1024]
                ))
                .toList();
        var jsonBatch = assertThrows(InvalidInferenceInputException.class, () -> new InputNormalizer(
                new MemoryBlobStore()).normalize(input(InferenceMode.JSON_ONLY, true, List.of(), batchJson)));
        assertEquals("INFERENCE_JSON_BATCH_TOO_LARGE", jsonBatch.code());

        var mediaType = assertThrows(InvalidInferenceInputException.class, () -> new InputNormalizer(
                new MemoryBlobStore()).normalize(input(
                        InferenceMode.JSON_ONLY, true, List.of(),
                        List.of(binary("sample.json", "text/plain", "{}".getBytes(StandardCharsets.UTF_8)))
                )));
        assertEquals("INFERENCE_JSON_MEDIA_TYPE_UNSUPPORTED", mediaType.code());
    }

    @Test
    void appliesExifOrientationAndStoresMetadataFreeSrgbPng() throws Exception {
        var jpeg = imageBytes(2, 1, Color.ORANGE, "jpeg");
        var withExif = addExifOrientation(jpeg, 6);
        var store = new MemoryBlobStore();

        var normalized = new InputNormalizer(store).normalize(input(
                InferenceMode.IMAGE_ONLY,
                true,
                List.of(binary("oriented.jpg", "image/jpeg", withExif)),
                List.of()
        ));

        var artifact = normalized.artifacts().getFirst();
        var stored = store.read(artifact.locator());
        assertEquals("image/png", artifact.mediaType());
        assertEquals(1, artifact.width());
        assertEquals(2, artifact.height());
        assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, Arrays.copyOf(stored, 4));
        assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains("Exif"));
    }

    @Test
    void boundsTheDecoderWorkingSetForVeryLargeSourceDimensions() {
        assertEquals(1, ImageNormalizer.sourceSubsampling(4097, 4097));
        assertEquals(3, ImageNormalizer.sourceSubsampling(10_000, 10_000));
    }

    @Test
    void rejectsDuplicateMembersNonObjectRootsAndDepthAboveThirtyTwo() {
        assertJsonError("{\"name\":1,\"name\":2}", "INFERENCE_JSON_DUPLICATE_MEMBER");
        assertJsonError("[1,2,3]", "INFERENCE_JSON_ROOT_INVALID");

        var deep = new StringBuilder("{\"root\":");
        deep.append("{\"x\":".repeat(31));
        deep.append("1");
        deep.append("}".repeat(32));
        assertJsonError(deep.toString(), "INFERENCE_JSON_DEPTH_EXCEEDED");
    }

    @Test
    void persistsOnlyAStableStructuralJsonProfileWithoutSampleValues() {
        var store = new MemoryBlobStore();
        var normalized = new InputNormalizer(store).normalize(input(
                InferenceMode.JSON_ONLY,
                true,
                List.of(),
                List.of(json("{\"displayName\":\"top-secret-value\",\"items\":[{\"price\":12.50}]}"))
        ));

        var artifact = normalized.artifacts().getFirst();
        var profile = new String(store.read(artifact.locator()), StandardCharsets.UTF_8);
        assertEquals(NormalizedArtifact.Kind.JSON_PROFILE, artifact.kind());
        assertTrue(profile.contains("\"pointer\":\"/displayName\""));
        assertTrue(profile.contains("\"pointer\":\"/items/*/price\""));
        assertTrue(profile.contains("\"kinds\":[\"decimal\"]"));
        assertFalse(profile.contains("top-secret-value"));
        assertFalse(profile.contains("12.50"));
    }

    @Test
    void combinedModePersistsAnOrderedImageAndOneReducedJsonProfile() throws Exception {
        var normalized = new InputNormalizer(new MemoryBlobStore()).normalize(input(
                InferenceMode.COMBINED,
                true,
                List.of(image(3, 2, Color.CYAN, "png")),
                List.of(json("{\"title\":\"卡片\"}"), json("{\"title\":\"Card\"}"))
        ));

        assertEquals(InferenceMode.COMBINED, normalized.mode());
        assertEquals(List.of(NormalizedArtifact.Kind.IMAGE, NormalizedArtifact.Kind.JSON_PROFILE),
                normalized.artifacts().stream().map(NormalizedArtifact::kind).toList());
        assertEquals(List.of(NormalizedArtifact.Kind.IMAGE, NormalizedArtifact.Kind.JSON_PROFILE),
                normalized.references().stream().map(NormalizedInputReference::kind).toList());
    }

    @Test
    void removesOnlyNewlyCreatedArtifactsWhenAStorageWriteFails() throws Exception {
        var store = new MemoryBlobStore();
        store.existingWriteNumbers.add(1);
        store.failWriteNumber = 3;
        var images = List.of(
                image(2, 1, Color.RED, "png"),
                image(2, 1, Color.GREEN, "png"),
                image(2, 1, Color.BLUE, "png")
        );

        var error = assertThrows(InferenceStorageException.class, () -> new InputNormalizer(store).normalize(
                input(InferenceMode.IMAGE_ONLY, true, images, List.of())
        ));

        assertEquals("STORAGE_WRITE_FAILED", error.code());
        assertEquals(List.of("blob-2"), store.deletedLocators);
        assertFalse(store.deletedLocators.contains("blob-1"));
    }

    @Test
    void inputFingerprintIsRepeatableAndOrderSensitive() throws Exception {
        var red = image(2, 1, Color.RED, "png");
        var blue = image(2, 1, Color.BLUE, "png");
        var normalizer = new InputNormalizer(new MemoryBlobStore());

        var first = normalizer.normalize(input(InferenceMode.IMAGE_ONLY, true, List.of(red, blue), List.of()));
        var replay = normalizer.normalize(input(InferenceMode.IMAGE_ONLY, true, List.of(red, blue), List.of()));
        var reordered = normalizer.normalize(input(InferenceMode.IMAGE_ONLY, true, List.of(blue, red), List.of()));

        assertEquals(first.inputFingerprint(), replay.inputFingerprint());
        assertNotEquals(first.inputFingerprint(), reordered.inputFingerprint());
        assertTrue(first.inputFingerprint().matches("[a-f0-9]{64}"));
        assertEquals(List.of(0, 1), first.references().stream().map(NormalizedInputReference::ordinal).toList());
    }

    @Test
    void duplicateImagesShareOneArtifactButKeepBothEvidenceOrdinals() throws Exception {
        var image = image(2, 1, Color.MAGENTA, "png");
        var normalized = new InputNormalizer(new MemoryBlobStore()).normalize(input(
                InferenceMode.IMAGE_ONLY, true, List.of(image, image), List.of()
        ));

        assertEquals(1, normalized.artifacts().size());
        assertEquals(2, normalized.references().size());
        assertEquals(List.of(0, 1), normalized.references().stream()
                .map(NormalizedInputReference::ordinal).toList());
        assertEquals(1, normalized.references().stream()
                .map(NormalizedInputReference::artifactId).distinct().count());
    }

    @Test
    void largeArraysProduceWildcardStructuralNodesInsteadOfOneNodePerIndex() {
        var values = java.util.stream.IntStream.range(0, 10_000)
                .mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(","));
        var store = new MemoryBlobStore();
        var normalized = new InputNormalizer(store).normalize(input(
                InferenceMode.JSON_ONLY, true, List.of(), List.of(json("{\"items\":[" + values + "]}"))
        ));
        var profile = new String(store.read(normalized.artifacts().getFirst().locator()), StandardCharsets.UTF_8);

        assertTrue(profile.contains("\"pointer\":\"/items/*\""));
        assertTrue(profile.contains("\"occurrences\":10000"));
        assertFalse(profile.contains("/items/9999"));
        assertTrue(profile.length() < 2_000);
    }

    private static void assertJsonError(String value, String expectedCode) {
        var error = assertThrows(InvalidInferenceInputException.class,
                () -> new InputNormalizer(new MemoryBlobStore()).normalize(input(
                        InferenceMode.JSON_ONLY, true, List.of(), List.of(json(value))
                )));
        assertEquals(expectedCode, error.code());
    }

    private static NormalizedInput normalizeImage(InferenceInput.BinaryInput image) {
        return new InputNormalizer(new MemoryBlobStore()).normalize(input(
                InferenceMode.IMAGE_ONLY, true, List.of(image), List.of()
        ));
    }

    private static InferenceInput input(
            InferenceMode mode,
            boolean confirmed,
            List<InferenceInput.BinaryInput> images,
            List<InferenceInput.BinaryInput> samples
    ) {
        return new InferenceInput(mode, "replay-v1", "fixture-01", confirmed, images, samples);
    }

    private static InferenceInput.BinaryInput image(int width, int height, Color color, String format)
            throws Exception {
        return binary("sample." + format, "image/" + format, imageBytes(width, height, color, format));
    }

    private static byte[] imageBytes(int width, int height, Color color, String format) throws Exception {
        var source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, format, output));
        return output.toByteArray();
    }

    private static InferenceInput.BinaryInput json(String value) {
        return binary("sample.json", "application/json", value.getBytes(StandardCharsets.UTF_8));
    }

    private static InferenceInput.BinaryInput binary(String name, String mediaType, byte[] bytes) {
        return new InferenceInput.BinaryInput(name, mediaType, bytes);
    }

    private static byte[] addEmptyPngChunk(byte[] png, String type) {
        var result = new byte[png.length + 12];
        System.arraycopy(png, 0, result, 0, 8);
        var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(typeBytes, 0, result, 12, 4);
        System.arraycopy(png, 8, result, 20, png.length - 8);
        return result;
    }

    private static byte[] withPngDimensions(byte[] png, int width, int height) {
        var result = png.clone();
        var buffer = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(16, width);
        buffer.putInt(20, height);
        var crc = new CRC32();
        crc.update(result, 12, 17);
        buffer.putInt(29, (int) crc.getValue());
        return result;
    }

    private static byte[] addExifOrientation(byte[] jpeg, int orientation) {
        var exif = new byte[]{
                'E', 'x', 'i', 'f', 0, 0,
                'I', 'I', 42, 0, 8, 0, 0, 0,
                1, 0,
                0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0,
                0, 0, 0, 0
        };
        var segmentLength = exif.length + 2;
        var result = new byte[jpeg.length + exif.length + 4];
        result[0] = jpeg[0];
        result[1] = jpeg[1];
        result[2] = (byte) 0xff;
        result[3] = (byte) 0xe1;
        result[4] = (byte) (segmentLength >>> 8);
        result[5] = (byte) segmentLength;
        System.arraycopy(exif, 0, result, 6, exif.length);
        System.arraycopy(jpeg, 2, result, 6 + exif.length, jpeg.length - 2);
        return result;
    }

    private static final class MemoryBlobStore implements BlobStore {
        private final Map<String, byte[]> bytesByLocator = new LinkedHashMap<>();
        private final List<Integer> existingWriteNumbers = new ArrayList<>();
        private final List<String> deletedLocators = new ArrayList<>();
        private int writes;
        private int failWriteNumber = -1;

        @Override
        public WriteReceipt write(String artifactId, byte[] bytes) {
            writes++;
            if (writes == failWriteNumber) throw new IllegalStateException("simulated storage failure");
            var locator = "blob-" + writes;
            var created = !existingWriteNumbers.contains(writes);
            if (created) bytesByLocator.put(locator, bytes.clone());
            return new WriteReceipt(locator, created);
        }

        @Override
        public byte[] read(String locator) {
            var bytes = bytesByLocator.get(locator);
            if (bytes == null) throw new IllegalArgumentException("Missing blob " + locator);
            return bytes.clone();
        }

        @Override
        public void delete(String locator) {
            deletedLocators.add(locator);
            bytesByLocator.remove(locator);
        }
    }
}
