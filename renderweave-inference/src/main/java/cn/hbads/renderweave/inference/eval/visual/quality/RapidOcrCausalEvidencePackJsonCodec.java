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

/** Strict canonical codec for the payload-safe RapidOCR causal evidence pack. */
public final class RapidOcrCausalEvidencePackJsonCodec {
    private static final String ENVELOPE_VERSION =
            "renderweave-rapidocr-causal-evidence-envelope/1.0";
    private static final int MAXIMUM_BYTES = 4 * 1024 * 1024;
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

    public byte[] write(RapidOcrCausalEvidencePack evidence) {
        Objects.requireNonNull(evidence, "evidence");
        return encode(new Envelope(ENVELOPE_VERSION, evidenceIdentity(evidence), evidence));
    }

    public RapidOcrCausalEvidencePack read(byte[] bytes, String expectedIdentity) {
        var envelope = verifiedEnvelope(bytes);
        if (!Objects.equals(expectedIdentity, envelope.evidenceIdentity())) {
            throw new IllegalArgumentException("RAPIDOCR_CAUSAL_IDENTITY_DRIFT");
        }
        return envelope.evidence();
    }

    public RapidOcrCausalEvidencePack read(byte[] bytes) {
        return verifiedEnvelope(bytes).evidence();
    }

    private Envelope verifiedEnvelope(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("RAPIDOCR_CAUSAL_BYTES_INVALID");
        }
        try {
            var envelope = JSON.readValue(bytes, Envelope.class);
            if (!ENVELOPE_VERSION.equals(envelope.envelopeVersion()) || envelope.evidence() == null
                    || !evidenceIdentity(envelope.evidence()).equals(envelope.evidenceIdentity())) {
                throw new IllegalArgumentException("RAPIDOCR_CAUSAL_IDENTITY_DRIFT");
            }
            return envelope;
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("RAPIDOCR_CAUSAL_CONTRACT_INVALID", failure);
        }
    }

    public String evidenceIdentity(RapidOcrCausalEvidencePack evidence) {
        return "renderweave-rapidocr-causal-evidence/1.0:"
                + sha256(encode(Objects.requireNonNull(evidence, "evidence")));
    }

    private static byte[] encode(Object value) {
        try {
            return JSON.writeValueAsBytes(canonicalNode(JSON.valueToTree(value)));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("RAPIDOCR_CAUSAL_ENCODING_FAILED", failure);
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
            String evidenceIdentity,
            RapidOcrCausalEvidencePack evidence
    ) { }
}
