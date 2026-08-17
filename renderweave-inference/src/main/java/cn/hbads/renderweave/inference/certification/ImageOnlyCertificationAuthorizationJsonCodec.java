package cn.hbads.renderweave.inference.certification;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class ImageOnlyCertificationAuthorizationJsonCodec {
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public ImageOnlyCertificationAuthorization read(byte[] source) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("CERTIFICATION_AUTHORIZATION_JSON_EMPTY");
        }
        try {
            return JSON.readValue(source, ImageOnlyCertificationAuthorization.class);
        } catch (Exception failure) {
            throw new IllegalArgumentException("CERTIFICATION_AUTHORIZATION_JSON_INVALID", failure);
        }
    }

    public byte[] write(ImageOnlyCertificationAuthorization authorization) {
        try {
            return JSON.writeValueAsBytes(authorization);
        } catch (Exception failure) {
            throw new IllegalArgumentException("CERTIFICATION_AUTHORIZATION_JSON_INVALID", failure);
        }
    }
}
