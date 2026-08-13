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

/** Strict canonical and tamper-evident codec for an N7 live semantic report. */
public final class N7LiveSemanticEvaluationReportJsonCodec {
    private static final String ENVELOPE_VERSION = "renderweave-n7-live-semantic-report-envelope/1.0";
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

    public byte[] write(N7LiveSemanticEvaluationReport report) {
        Objects.requireNonNull(report, "report");
        return encode(new Envelope(ENVELOPE_VERSION, reportIdentity(report), report));
    }

    public N7LiveSemanticEvaluationReport read(byte[] bytes, String expectedIdentity) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("N7_LIVE_REPORT_BYTES_INVALID");
        }
        try {
            var envelope = JSON.readValue(bytes, Envelope.class);
            if (!ENVELOPE_VERSION.equals(envelope.envelopeVersion()) || envelope.report() == null
                    || !Objects.equals(expectedIdentity, envelope.reportIdentity())
                    || !reportIdentity(envelope.report()).equals(envelope.reportIdentity())) {
                throw new IllegalArgumentException("N7_LIVE_REPORT_IDENTITY_DRIFT");
            }
            return envelope.report();
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("N7_LIVE_REPORT_CONTRACT_INVALID", failure);
        }
    }

    public String reportIdentity(N7LiveSemanticEvaluationReport report) {
        Objects.requireNonNull(report, "report");
        return N7LiveSemanticEvaluationReport.VERSION + ":" + sha256(encode(report));
    }

    private static byte[] encode(Object value) {
        try {
            return JSON.writeValueAsBytes(canonicalNode(JSON.valueToTree(value)));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("N7_LIVE_REPORT_ENCODING_FAILED", failure);
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
            String reportIdentity,
            N7LiveSemanticEvaluationReport report
    ) { }
}
