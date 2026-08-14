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
import java.util.Objects;

/** Strictly binds one independently replayed A2 summary to its payload-safe evidence envelope. */
public final class OfflineComponentVerificationReader {
    private static final int MAXIMUM_EVIDENCE_BYTES = 4 * 1024 * 1024;
    private static final int MAXIMUM_SUMMARY_BYTES = 1024 * 1024;
    private static final String ASSURANCE = "A2_CROSS_IMPLEMENTATION_RECOMPUTE";
    private static final String[] FORBIDDEN = {
            "base64", "data:image", "ocrtext", "ocr_text", "prompttext", "modeloutput",
            "candidatejson", "rootdocument", "boundingbox", "\"bbox\"", "inspectionrequest",
            "providerrequest", "providerresponse", "bearer "
    };
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public FrozenQualityEvidencePack.ComponentVerification read(
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
        if (!envelope.isObject() || envelope.size() != 3 || !envelope.has("envelopeVersion")
                || !envelope.has("evidenceIdentity") || !envelope.has("evidence")
                || !component.evidenceEnvelopeVersion().equals(requiredText(
                envelope, "envelopeVersion", "QUALITY_REPAIR_COMPONENT_ENVELOPE_VERSION_INVALID"))
                || !expectedEvidenceIdentity.equals(requiredText(
                envelope, "evidenceIdentity", "QUALITY_REPAIR_COMPONENT_EVIDENCE_IDENTITY_DRIFT"))) {
            throw invalid("QUALITY_REPAIR_COMPONENT_EVIDENCE_IDENTITY_DRIFT");
        }

        var summary = parse(verificationSummaryBytes, "QUALITY_REPAIR_COMPONENT_SUMMARY_CONTRACT_INVALID");
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
        if (requiredZero(summary, "providerAttempts") != 0
                || requiredZero(summary, "providerReservations") != 0
                || requiredZero(summary, "externalProviderCostMicrosCny") != 0) {
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

    private static long requiredZero(JsonNode source, String field) {
        var value = source.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid("QUALITY_REPAIR_COMPONENT_ACCOUNTING_INVALID");
        }
        return value.longValue();
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
