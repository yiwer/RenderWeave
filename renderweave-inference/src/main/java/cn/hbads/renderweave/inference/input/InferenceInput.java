package cn.hbads.renderweave.inference.input;

import java.util.List;
import java.util.Objects;

public record InferenceInput(
        InferenceMode mode,
        String profileId,
        String sourceReference,
        boolean externalTransferConfirmed,
        List<BinaryInput> images,
        List<BinaryInput> jsonSamples
) {
    public InferenceInput {
        Objects.requireNonNull(mode, "mode");
        profileId = requireText(profileId, "profileId");
        sourceReference = requireText(sourceReference, "sourceReference");
        images = List.copyOf(Objects.requireNonNull(images, "images"));
        jsonSamples = List.copyOf(Objects.requireNonNull(jsonSamples, "jsonSamples"));
    }

    public record BinaryInput(String name, String mediaType, byte[] bytes) {
        public BinaryInput {
            name = requireText(name, "name");
            mediaType = requireText(mediaType, "mediaType");
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
