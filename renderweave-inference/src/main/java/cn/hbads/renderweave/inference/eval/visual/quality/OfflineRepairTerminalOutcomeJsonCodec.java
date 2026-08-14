package cn.hbads.renderweave.inference.eval.visual.quality;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Strict canonical codec for payload-safe conditional ticket outcomes. */
public final class OfflineRepairTerminalOutcomeJsonCodec {
    private static final String ENVELOPE_VERSION =
            "renderweave-offline-repair-terminal-outcome-envelope/1.0";
    private static final int MAXIMUM_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public byte[] write(OfflineRepairTerminalOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return encode(new Envelope(ENVELOPE_VERSION, outcomeIdentity(outcome), outcome));
    }

    public OfflineRepairTerminalOutcome read(byte[] bytes) {
        return verifiedEnvelope(bytes).outcome();
    }

    public OfflineRepairTerminalOutcome read(byte[] bytes, String expectedIdentity) {
        var envelope = verifiedEnvelope(bytes);
        if (!Objects.equals(expectedIdentity, envelope.outcomeIdentity())) {
            throw new IllegalArgumentException("OFFLINE_TERMINAL_OUTCOME_IDENTITY_DRIFT");
        }
        return envelope.outcome();
    }

    public String outcomeIdentity(OfflineRepairTerminalOutcome outcome) {
        return OfflineRepairTerminalOutcome.IDENTITY_PREFIX
                + sha256(encode(Objects.requireNonNull(outcome, "outcome")));
    }

    private Envelope verifiedEnvelope(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("OFFLINE_TERMINAL_OUTCOME_BYTES_INVALID");
        }
        try {
            var envelope = JSON.readValue(bytes, Envelope.class);
            if (!ENVELOPE_VERSION.equals(envelope.envelopeVersion()) || envelope.outcome() == null
                    || !outcomeIdentity(envelope.outcome()).equals(envelope.outcomeIdentity())) {
                throw new IllegalArgumentException("OFFLINE_TERMINAL_OUTCOME_IDENTITY_DRIFT");
            }
            return envelope;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("OFFLINE_TERMINAL_OUTCOME_CONTRACT_INVALID", failure);
        }
    }

    private static byte[] encode(Object value) {
        try {
            return JSON.writeValueAsBytes(canonicalNode(JSON.valueToTree(value)));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("OFFLINE_TERMINAL_OUTCOME_ENCODING_FAILED", failure);
        }
    }

    private static JsonNode canonicalNode(JsonNode source) {
        if (source.isObject()) {
            var result = JSON.createObjectNode();
            var properties = new java.util.ArrayList<>(source.properties());
            properties.sort(java.util.Map.Entry.comparingByKey());
            properties.forEach(property -> result.set(property.getKey(), canonicalNode(property.getValue())));
            return result;
        }
        if (source.isArray()) {
            var result = JSON.createArrayNode();
            source.forEach(item -> result.add(canonicalNode(item)));
            return result;
        }
        return source;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    private record Envelope(
            String envelopeVersion,
            String outcomeIdentity,
            OfflineRepairTerminalOutcome outcome
    ) { }
}
