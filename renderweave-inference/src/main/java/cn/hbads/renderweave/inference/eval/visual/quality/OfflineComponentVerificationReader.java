package cn.hbads.renderweave.inference.eval.visual.quality;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strictly binds one independently replayed A2 summary to its payload-safe evidence envelope. */
final class OfflineComponentVerificationReader {
    private static final int MAXIMUM_EVIDENCE_BYTES = 4 * 1024 * 1024;
    private static final int MAXIMUM_SUMMARY_BYTES = 1024 * 1024;
    private static final String ASSURANCE = "A2_CROSS_IMPLEMENTATION_RECOMPUTE";
    private static final String[] FORBIDDEN = {
            "base64", "data:image", "ocrtext", "ocr_text", "prompttext", "modeloutput",
            "candidatejson", "rootdocument", "boundingbox", "\"bbox\"", "inspectionrequest",
            "providerrequest", "providerresponse", "bearer "
    };
    private static final Map<FrozenQualityEvidencePack.Component, Set<String>> SUMMARY_FIELDS = Map.of(
            FrozenQualityEvidencePack.Component.RAPIDOCR_CAUSAL, Set.of(
                    "actualAcquisitions", "assurance", "attributionResults", "caseCount",
                    "evaluationIdentity", "evidenceIdentity", "externalProviderCostMicrosCny",
                    "metricsEquivalentCases", "observationEquivalentCases", "protocolIdentity",
                    "providerAttempts", "providerReservations", "repositoryRevision", "result",
                    "runCount", "verifierVersion"),
            FrozenQualityEvidencePack.Component.R3_PROBE, Set.of(
                    "assignmentIdentity", "assurance", "caseCount", "devCases", "disposition",
                    "evidenceIdentity", "externalProviderCostMicrosCny", "holdoutCases",
                    "providerAttempts", "providerReservations", "reasonCode", "repositoryRevision",
                    "result", "runs", "triggered", "verifierVersion"),
            FrozenQualityEvidencePack.Component.R5_PROBE, Set.of(
                    "actualAcquisitions", "assignmentIdentity", "assurance", "caseCount",
                    "deterministicCases", "devCases", "disposition", "evaluationIdentity",
                    "evidenceIdentity", "externalProviderCostMicrosCny", "holdoutCases",
                    "providerAttempts", "providerReservations", "reasonCode", "repositoryRevision",
                    "result", "runs", "transformIdentity", "triggered", "verifierVersion"));
    private static final Map<String, String> RAPIDOCR_ATTRIBUTIONS = Map.of(
            "LAYOUT", "OBSERVED_CONTRIBUTOR",
            "MATERIALIZER", "MISSING",
            "OBSERVATION", "OBSERVED_CONTRIBUTOR",
            "ORDER_REPEAT", "MISSING",
            "SCORER", "EXCLUDED_BY_CURRENT_EVIDENCE",
            "SEMANTIC", "OBSERVED_CONTRIBUTOR",
            "SHAPE_CODEC", "EXCLUDED_BY_CURRENT_EVIDENCE",
            "STATIC_VIEW", "MISSING");
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    FrozenQualityEvidencePack.ComponentVerification read(
            FrozenQualityEvidencePack.Component component,
            byte[] evidenceBytes,
            String expectedEvidenceIdentity,
            byte[] verificationSummaryBytes,
            String expectedRepositoryRevision
    ) {
        Objects.requireNonNull(component, "component");
        validateBytes(evidenceBytes, MAXIMUM_EVIDENCE_BYTES, "QUALITY_REPAIR_COMPONENT_EVIDENCE_BYTES_INVALID");
        validateBytes(verificationSummaryBytes, MAXIMUM_SUMMARY_BYTES,
                "QUALITY_REPAIR_COMPONENT_SUMMARY_BYTES_INVALID");
        if (expectedEvidenceIdentity == null || !expectedEvidenceIdentity.matches(
                java.util.regex.Pattern.quote(component.evidenceIdentityVersion()) + ":[0-9a-f]{64}")) {
            throw invalid("QUALITY_REPAIR_COMPONENT_EVIDENCE_IDENTITY_INVALID");
        }
        if (expectedRepositoryRevision == null || !expectedRepositoryRevision.matches("[0-9a-f]{40}")) {
            throw invalid("QUALITY_REPAIR_COMPONENT_REVISION_INVALID");
        }

        var envelope = parse(evidenceBytes, "QUALITY_REPAIR_COMPONENT_EVIDENCE_CONTRACT_INVALID");
        validateDecodedPayload(envelope);
        if (!envelope.isObject() || envelope.size() != 3 || !envelope.has("envelopeVersion")
                || !envelope.has("evidenceIdentity") || !envelope.has("evidence")
                || !component.evidenceEnvelopeVersion().equals(requiredText(
                envelope, "envelopeVersion", "QUALITY_REPAIR_COMPONENT_ENVELOPE_VERSION_INVALID"))
                || !expectedEvidenceIdentity.equals(requiredText(
                envelope, "evidenceIdentity", "QUALITY_REPAIR_COMPONENT_EVIDENCE_IDENTITY_DRIFT"))) {
            throw invalid("QUALITY_REPAIR_COMPONENT_EVIDENCE_IDENTITY_DRIFT");
        }

        var summary = parse(verificationSummaryBytes, "QUALITY_REPAIR_COMPONENT_SUMMARY_CONTRACT_INVALID");
        validateDecodedPayload(summary);
        validateSummarySchema(component, summary);
        if (!summary.isObject()
                || !"PASS".equals(requiredText(summary, "result", "QUALITY_REPAIR_COMPONENT_RESULT_INVALID"))
                || !ASSURANCE.equals(requiredText(
                summary, "assurance", "QUALITY_REPAIR_COMPONENT_ASSURANCE_INVALID"))
                || !component.verifierVersion().equals(requiredText(
                summary, "verifierVersion", "QUALITY_REPAIR_COMPONENT_VERIFIER_VERSION_INVALID"))
                || !expectedEvidenceIdentity.equals(requiredText(
                summary, "evidenceIdentity", "QUALITY_REPAIR_COMPONENT_SUMMARY_IDENTITY_DRIFT"))
                || !expectedRepositoryRevision.equals(requiredText(
                summary, "repositoryRevision", "QUALITY_REPAIR_COMPONENT_REVISION_DRIFT"))) {
            throw invalid("QUALITY_REPAIR_COMPONENT_SUMMARY_AUTHORITY_INVALID");
        }
        if (requiredLong(summary, "providerAttempts") != 0
                || requiredLong(summary, "providerReservations") != 0
                || requiredLong(summary, "externalProviderCostMicrosCny") != 0) {
            throw invalid("QUALITY_REPAIR_COMPONENT_PROVIDER_USAGE_NONZERO");
        }
        return new FrozenQualityEvidencePack.ComponentVerification(
                component,
                expectedEvidenceIdentity,
                sha256(evidenceBytes),
                sha256(verificationSummaryBytes),
                component.verifierVersion(),
                ASSURANCE,
                expectedRepositoryRevision,
                FrozenQualityEvidencePack.VerificationResult.PASS,
                new FrozenQualityEvidencePack.ExternalProviderUsage(0, 0, 0));
    }

    private static JsonNode parse(byte[] bytes, String code) {
        try {
            return JSON.readTree(bytes);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(code, failure);
        }
    }

    private static String requiredText(JsonNode source, String field, String code) {
        var value = source.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(code);
        }
        return value.textValue();
    }

    private static long requiredLong(JsonNode source, String field) {
        var value = source.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid("QUALITY_REPAIR_COMPONENT_ACCOUNTING_INVALID");
        }
        return value.longValue();
    }

    private static boolean requiredBoolean(JsonNode source, String field) {
        var value = source.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid("QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID");
        }
        return value.booleanValue();
    }

    private static void validateSummarySchema(
            FrozenQualityEvidencePack.Component component,
            JsonNode summary
    ) {
        if (!summary.isObject() || !Set.copyOf(summary.properties().stream()
                .map(Map.Entry::getKey).toList()).equals(SUMMARY_FIELDS.get(component))) {
            throw invalid("QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID");
        }
        switch (component) {
            case RAPIDOCR_CAUSAL -> {
                if (requiredLong(summary, "actualAcquisitions") != 120
                        || requiredLong(summary, "caseCount") != 60
                        || requiredLong(summary, "metricsEquivalentCases") != 60
                        || requiredLong(summary, "observationEquivalentCases") != 60
                        || requiredLong(summary, "runCount") != 2
                        || !requiredText(summary, "evaluationIdentity",
                        "QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID").matches(
                        "renderweave-rapidocr-shadow-evaluation/1\\.0:[0-9a-f]{64}")
                        || !requiredText(summary, "protocolIdentity",
                        "QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID").matches(
                        "renderweave-offline-quality-evaluation-protocol/1\\.0:[0-9a-f]{64}")) {
                    throw invalid("QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID");
                }
                var attributions = summary.get("attributionResults");
                if (attributions == null || !attributions.isObject()
                        || attributions.size() != RAPIDOCR_ATTRIBUTIONS.size()
                        || RAPIDOCR_ATTRIBUTIONS.entrySet().stream().anyMatch(entry ->
                        !entry.getValue().equals(requiredText(attributions, entry.getKey(),
                                "QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID")))) {
                    throw invalid("QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID");
                }
            }
            case R3_PROBE -> {
                if (requiredLong(summary, "caseCount") != 4
                        || requiredLong(summary, "devCases") != 3
                        || requiredLong(summary, "holdoutCases") != 1
                        || requiredLong(summary, "runs") != 2
                        || requiredBoolean(summary, "triggered")
                        || !"MISSING".equals(requiredText(
                        summary, "disposition", "QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID"))
                        || !"R3_OCR_OMISSION_NOT_EXCLUDED".equals(requiredText(
                        summary, "reasonCode", "QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID"))
                        || !requiredText(summary, "assignmentIdentity",
                        "QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID").matches(
                        "renderweave-r3-probe-assignment/1\\.0:[0-9a-f]{64}")) {
                    throw invalid("QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID");
                }
            }
            case R5_PROBE -> {
                if (requiredLong(summary, "actualAcquisitions") != 16
                        || requiredLong(summary, "caseCount") != 4
                        || requiredLong(summary, "deterministicCases") != 4
                        || requiredLong(summary, "devCases") != 3
                        || requiredLong(summary, "holdoutCases") != 1
                        || requiredLong(summary, "runs") != 2
                        || !requiredBoolean(summary, "triggered")
                        || !"TRIGGERED".equals(requiredText(
                        summary, "disposition", "QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID"))
                        || !"R5_ORACLE_DIFFERENTIAL_CONFIRMED".equals(requiredText(
                        summary, "reasonCode", "QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID"))
                        || !requiredText(summary, "assignmentIdentity",
                        "QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID").matches(
                        "renderweave-r5-probe-assignment/1\\.0:[0-9a-f]{64}")
                        || !requiredText(summary, "evaluationIdentity",
                        "QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID").matches(
                        "renderweave-r5-oracle-evaluation/1\\.0:[0-9a-f]{64}")
                        || !requiredText(summary, "transformIdentity",
                        "QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID").matches(
                        "renderweave-r5-oracle-higher-resolution/1\\.0:[0-9a-f]{64}")) {
                    throw invalid("QUALITY_REPAIR_COMPONENT_SUMMARY_SCHEMA_INVALID");
                }
            }
        }
    }

    private static void validateDecodedPayload(JsonNode value) {
        if (value.isObject()) {
            for (var property : value.properties()) {
                requirePayloadSafe(property.getKey());
                validateDecodedPayload(property.getValue());
            }
        } else if (value.isArray()) {
            value.forEach(OfflineComponentVerificationReader::validateDecodedPayload);
        } else if (value.isTextual()) {
            requirePayloadSafe(value.textValue());
        }
    }

    private static void requirePayloadSafe(String value) {
        var searchable = value.toLowerCase(Locale.ROOT);
        for (var forbidden : FORBIDDEN) {
            if (searchable.contains(forbidden.replace("\"", ""))) {
                throw invalid("QUALITY_REPAIR_COMPONENT_PAYLOAD_FORBIDDEN");
            }
        }
    }

    private static void validateBytes(byte[] bytes, int maximum, String code) {
        if (bytes == null || bytes.length == 0 || bytes.length > maximum) {
            throw invalid(code);
        }
        var searchable = new String(bytes, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        for (var forbidden : FORBIDDEN) {
            if (searchable.contains(forbidden)) {
                throw invalid("QUALITY_REPAIR_COMPONENT_PAYLOAD_FORBIDDEN");
            }
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
