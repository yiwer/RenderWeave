package cn.hbads.renderweave.inference.eval.visual.quality;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Strict canonical codec for product-transform qualification evidence. */
public final class R5ProductTransformEvidenceJsonCodec {
    private static final String ENVELOPE_VERSION = "renderweave-r5-product-transform-envelope/1.0";
    private static final int MAXIMUM_BYTES = 1024 * 1024;
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder(
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

    public byte[] write(R5ProductTransformEvidence evidence) {
        return encode(new Envelope(ENVELOPE_VERSION, evidenceIdentity(evidence), evidence));
    }

    public R5ProductTransformEvidence read(byte[] bytes, String expectedIdentity) {
        var envelope = verified(bytes);
        if (!Objects.equals(expectedIdentity, envelope.evidenceIdentity())) {
            throw new IllegalArgumentException("R5_PRODUCT_EVIDENCE_IDENTITY_DRIFT");
        }
        return envelope.evidence();
    }

    public R5ProductTransformEvidence read(byte[] bytes) { return verified(bytes).evidence(); }

    public String evidenceIdentity(R5ProductTransformEvidence evidence) {
        return "renderweave-r5-product-transform-evidence/1.0:" + sha256(encode(
                Objects.requireNonNull(evidence, "evidence")));
    }

    private Envelope verified(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("R5_PRODUCT_EVIDENCE_BYTES_INVALID");
        }
        try {
            var envelope = JSON.readValue(bytes, Envelope.class);
            if (!ENVELOPE_VERSION.equals(envelope.envelopeVersion()) || envelope.evidence() == null
                    || !evidenceIdentity(envelope.evidence()).equals(envelope.evidenceIdentity())) {
                throw new IllegalArgumentException("R5_PRODUCT_EVIDENCE_IDENTITY_DRIFT");
            }
            return envelope;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("R5_PRODUCT_EVIDENCE_CONTRACT_INVALID", failure);
        }
    }

    private static byte[] encode(Object value) {
        try {
            return JSON.writeValueAsBytes(canonical(JSON.valueToTree(value)));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("R5_PRODUCT_EVIDENCE_ENCODING_FAILED", failure);
        }
    }

    private static JsonNode canonical(JsonNode source) {
        if (source.isObject()) {
            var result = JSON.createObjectNode();
            var properties = new java.util.ArrayList<>(source.properties());
            properties.sort(java.util.Map.Entry.comparingByKey());
            properties.forEach(property -> result.set(property.getKey(), canonical(property.getValue())));
            return result;
        }
        if (source.isArray()) {
            var result = JSON.createArrayNode();
            source.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return source;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private record Envelope(
            String envelopeVersion,
            String evidenceIdentity,
            R5ProductTransformEvidence evidence
    ) { }
}
