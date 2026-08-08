package cn.hbads.renderweave.inference.input;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InputNormalizer {
    public static final int MAX_IMAGES = 10;
    public static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_IMAGE_BATCH_BYTES = 30 * 1024 * 1024;
    public static final int MAX_JSON_SAMPLES = 20;
    public static final int MAX_JSON_SAMPLE_BYTES = 256 * 1024;
    public static final int MAX_JSON_BATCH_BYTES = 2 * 1024 * 1024;

    private final BlobStore blobStore;
    private final ImageNormalizer imageNormalizer;
    private final StrictJsonSampleProfiler jsonProfiler;

    public InputNormalizer(BlobStore blobStore) {
        this(blobStore, new ImageNormalizer(), new StrictJsonSampleProfiler());
    }

    InputNormalizer(
            BlobStore blobStore,
            ImageNormalizer imageNormalizer,
            StrictJsonSampleProfiler jsonProfiler
    ) {
        this.blobStore = java.util.Objects.requireNonNull(blobStore, "blobStore");
        this.imageNormalizer = java.util.Objects.requireNonNull(imageNormalizer, "imageNormalizer");
        this.jsonProfiler = java.util.Objects.requireNonNull(jsonProfiler, "jsonProfiler");
    }

    public NormalizedInput normalize(InferenceInput input) {
        validateEnvelope(input);
        var fingerprint = fingerprint(input);
        var artifacts = new LinkedHashMap<String, NormalizedArtifact>();
        var references = new ArrayList<NormalizedInputReference>();
        var receipts = new ArrayList<BlobStore.WriteReceipt>();
        try {
            for (var index = 0; index < input.images().size(); index++) {
                var image = input.images().get(index);
                var normalized = imageNormalizer.normalize(image);
                var bytes = normalized.pngBytes();
                var artifactId = sha256(bytes);
                if (!artifacts.containsKey(artifactId)) {
                    var receipt = blobStore.write(artifactId, bytes);
                    receipts.add(receipt);
                    artifacts.put(artifactId, new NormalizedArtifact(
                            artifactId, NormalizedArtifact.Kind.IMAGE, receipt.locator(), "image/png",
                            bytes.length, normalized.width(), normalized.height()
                    ));
                }
                references.add(new NormalizedInputReference(
                        NormalizedArtifact.Kind.IMAGE, index, artifactId
                ));
            }
            if (!input.jsonSamples().isEmpty()) {
                var profile = jsonProfiler.profile(input.jsonSamples());
                var artifactId = sha256(profile);
                var receipt = blobStore.write(artifactId, profile);
                receipts.add(receipt);
                artifacts.put(artifactId, new NormalizedArtifact(
                        artifactId, NormalizedArtifact.Kind.JSON_PROFILE, receipt.locator(),
                        "application/vnd.renderweave.json-profile+json", profile.length, null, null
                ));
                references.add(new NormalizedInputReference(
                        NormalizedArtifact.Kind.JSON_PROFILE, 0, artifactId
                ));
            }
            return new NormalizedInput(
                    input.mode(), input.profileId(), input.sourceReference(), fingerprint,
                    List.copyOf(artifacts.values()), references,
                    receipts.stream().filter(BlobStore.WriteReceipt::created)
                            .map(BlobStore.WriteReceipt::locator).toList()
            );
        } catch (InvalidInferenceInputException exception) {
            cleanup(receipts, exception);
            throw exception;
        } catch (RuntimeException exception) {
            var storage = new InferenceStorageException(
                    "STORAGE_WRITE_FAILED", "Normalized input could not be stored", exception
            );
            cleanup(receipts, storage);
            throw storage;
        }
    }

    private static void validateEnvelope(InferenceInput input) {
        if (!input.externalTransferConfirmed()) {
            throw new InvalidInferenceInputException(
                    "INFERENCE_EXTERNAL_TRANSFER_NOT_CONFIRMED", "/externalTransferConfirmed",
                    "Explicit external-transfer confirmation is required before a run is queued"
            );
        }
        var imageCount = input.images().size();
        var jsonCount = input.jsonSamples().size();
        var validCounts = switch (input.mode()) {
            case IMAGE_ONLY -> imageCount >= 1 && imageCount <= MAX_IMAGES && jsonCount == 0;
            case JSON_ONLY -> imageCount == 0 && jsonCount >= 1 && jsonCount <= MAX_JSON_SAMPLES;
            case COMBINED -> imageCount >= 1 && imageCount <= MAX_IMAGES
                    && jsonCount >= 1 && jsonCount <= MAX_JSON_SAMPLES;
        };
        if (!validCounts) {
            throw new InvalidInferenceInputException(
                    "INFERENCE_INPUT_COUNT_INVALID", "",
                    Map.of("mode", input.mode().wireName(), "imageCount", imageCount, "jsonSampleCount", jsonCount),
                    "Input counts do not match the selected inference mode", null
            );
        }
        validateSizes(input.images(), MAX_IMAGE_BYTES, MAX_IMAGE_BATCH_BYTES, "/images", "INFERENCE_IMAGE");
        validateSizes(input.jsonSamples(), MAX_JSON_SAMPLE_BYTES, MAX_JSON_BATCH_BYTES, "/jsonSamples", "INFERENCE_JSON");
        for (var index = 0; index < input.jsonSamples().size(); index++) {
            var mediaType = input.jsonSamples().get(index).mediaType();
            if (!"application/json".equalsIgnoreCase(mediaType)) {
                throw new InvalidInferenceInputException(
                        "INFERENCE_JSON_MEDIA_TYPE_UNSUPPORTED", "/jsonSamples/" + index,
                        Map.of("declaredMediaType", mediaType),
                        "Inference JSON samples must use application/json", null
                );
            }
        }
    }

    private static void validateSizes(
            List<InferenceInput.BinaryInput> inputs,
            int maximumItemBytes,
            int maximumBatchBytes,
            String pointer,
            String codePrefix
    ) {
        long total = 0;
        for (var index = 0; index < inputs.size(); index++) {
            var length = inputs.get(index).bytes().length;
            if (length == 0 || length > maximumItemBytes) {
                throw new InvalidInferenceInputException(
                        codePrefix + "_SIZE_INVALID", pointer + "/" + index,
                        Map.of("actualBytes", length, "maximumBytes", maximumItemBytes),
                        "An inference input exceeds its per-item byte limit", null
                );
            }
            total += length;
        }
        if (total > maximumBatchBytes) {
            throw new InvalidInferenceInputException(
                    codePrefix + "_BATCH_TOO_LARGE", pointer,
                    Map.of("actualBytes", total, "maximumBytes", maximumBatchBytes),
                    "Inference inputs exceed their aggregate byte limit", null
            );
        }
    }

    private void cleanup(List<BlobStore.WriteReceipt> receipts, RuntimeException primary) {
        for (var index = receipts.size() - 1; index >= 0; index--) {
            var receipt = receipts.get(index);
            if (!receipt.created()) continue;
            try {
                blobStore.delete(receipt.locator());
            } catch (RuntimeException cleanupFailure) {
                primary.addSuppressed(cleanupFailure);
            }
        }
    }

    private static String fingerprint(InferenceInput input) {
        var digest = sha256Digest();
        update(digest, input.mode().wireName().getBytes(StandardCharsets.UTF_8));
        update(digest, input.profileId().getBytes(StandardCharsets.UTF_8));
        update(digest, input.sourceReference().getBytes(StandardCharsets.UTF_8));
        for (var image : input.images()) {
            update(digest, image.mediaType().getBytes(StandardCharsets.UTF_8));
            update(digest, image.bytes());
        }
        for (var sample : input.jsonSamples()) {
            update(digest, sample.bytes());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }
}
