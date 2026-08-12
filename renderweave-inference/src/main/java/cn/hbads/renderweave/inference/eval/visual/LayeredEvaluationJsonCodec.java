package cn.hbads.renderweave.inference.eval.visual;

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

/** Strict canonical envelopes for controlled gold annotations and payload-safe evaluation records. */
public final class LayeredEvaluationJsonCodec {
    private static final String ANNOTATION_ENVELOPE_VERSION = "renderweave-layered-annotation-envelope/1.0";
    private static final String RECORD_ENVELOPE_VERSION = "renderweave-layered-record-envelope/1.0";
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

    public byte[] writeAnnotation(LayeredVisualAnnotation annotation) {
        Objects.requireNonNull(annotation, "annotation");
        return write(new AnnotationEnvelope(ANNOTATION_ENVELOPE_VERSION,
                annotationIdentity(annotation), annotation));
    }

    public LayeredVisualAnnotation readAnnotation(byte[] bytes, String expectedIdentity) {
        var envelope = read(bytes, AnnotationEnvelope.class, "ANNOTATION_CONTRACT_INVALID");
        if (!ANNOTATION_ENVELOPE_VERSION.equals(envelope.envelopeVersion()) || envelope.annotation() == null
                || !Objects.equals(expectedIdentity, envelope.annotationIdentity())
                || !annotationIdentity(envelope.annotation()).equals(envelope.annotationIdentity())) {
            throw new IllegalArgumentException("ANNOTATION_IDENTITY_DRIFT");
        }
        return envelope.annotation();
    }

    public String annotationIdentity(LayeredVisualAnnotation annotation) {
        Objects.requireNonNull(annotation, "annotation");
        return LayeredVisualAnnotation.VERSION + ":" + sha256(write(annotation));
    }

    public byte[] writeRecord(LayeredEvaluationRecord record) {
        Objects.requireNonNull(record, "record");
        return write(new RecordEnvelope(RECORD_ENVELOPE_VERSION, recordIdentity(record), record));
    }

    public LayeredEvaluationRecord readRecord(byte[] bytes, String expectedIdentity) {
        var envelope = read(bytes, RecordEnvelope.class, "EVALUATION_RECORD_CONTRACT_INVALID");
        if (!RECORD_ENVELOPE_VERSION.equals(envelope.envelopeVersion()) || envelope.record() == null
                || !Objects.equals(expectedIdentity, envelope.recordIdentity())
                || !recordIdentity(envelope.record()).equals(envelope.recordIdentity())) {
            throw new IllegalArgumentException("EVALUATION_RECORD_IDENTITY_DRIFT");
        }
        return envelope.record();
    }

    public String recordIdentity(LayeredEvaluationRecord record) {
        Objects.requireNonNull(record, "record");
        return LayeredEvaluationRecord.VERSION + ":" + sha256(write(record));
    }

    byte[] canonicalBytes(Object value) {
        return write(value);
    }

    private static byte[] write(Object value) {
        try {
            return JSON.writeValueAsBytes(canonicalNode(JSON.valueToTree(value)));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("LAYERED_EVALUATION_ENCODING_FAILED", failure);
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

    private static <T> T read(byte[] bytes, Class<T> type, String code) {
        if (bytes == null || bytes.length == 0 || bytes.length > 16 * 1024 * 1024) {
            throw new IllegalArgumentException(code);
        }
        try {
            return JSON.readValue(bytes, type);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(code, failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    private record AnnotationEnvelope(
            String envelopeVersion,
            String annotationIdentity,
            LayeredVisualAnnotation annotation
    ) { }

    private record RecordEnvelope(
            String envelopeVersion,
            String recordIdentity,
            LayeredEvaluationRecord record
    ) { }
}
