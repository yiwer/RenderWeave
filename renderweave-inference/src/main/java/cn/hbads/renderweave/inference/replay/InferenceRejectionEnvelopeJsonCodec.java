package cn.hbads.renderweave.inference.replay;

import cn.hbads.renderweave.inference.run.InferenceStage;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Strict bounded codec for the optional payload-free attempt rejection envelope. */
public final class InferenceRejectionEnvelopeJsonCodec {
    public static final int MAX_BYTES = 4 * 1024;

    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public String write(InferenceRejectionEnvelope envelope) {
        try {
            var json = JSON.writeValueAsString(new PersistedEnvelope(
                    envelope.primaryCode(), envelope.earliestStage().name(),
                    envelope.detailCodes(), envelope.detailCodeCount()
            ));
            requireBounded(json);
            return json;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Rejection envelope could not be encoded", exception);
        }
    }

    public InferenceRejectionEnvelope parse(String json) {
        try {
            requireBounded(json);
            var persisted = JSON.readValue(json, PersistedEnvelope.class);
            var envelope = new InferenceRejectionEnvelope(
                    persisted.primaryCode(), InferenceStage.valueOf(persisted.earliestStage()),
                    persisted.detailCodes()
            );
            if (persisted.detailCodeCount() != envelope.detailCodeCount()) {
                throw new IllegalArgumentException("Rejection detailCodeCount does not match details");
            }
            return envelope;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Rejection envelope is invalid", exception);
        }
    }

    private static void requireBounded(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Rejection envelope JSON is required");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Rejection envelope JSON is too large");
        }
    }

    private record PersistedEnvelope(
            String primaryCode,
            String earliestStage,
            List<String> detailCodes,
            int detailCodeCount
    ) { }
}
