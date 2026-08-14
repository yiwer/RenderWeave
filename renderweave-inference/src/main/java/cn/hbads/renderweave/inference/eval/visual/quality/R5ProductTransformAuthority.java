package cn.hbads.renderweave.inference.eval.visual.quality;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable fail-closed authority produced by the exact product-transform A2 gate. */
public final class R5ProductTransformAuthority {
    public static final String VERSION = "renderweave-r5-product-transform-authority/1.0";
    private static final String RESOURCE = "visual-eval/r5/product-transform-authority-v1.json";
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    private R5ProductTransformAuthority() { }

    public static Decision load() {
        try (var input = R5ProductTransformAuthority.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalid("R5_PRODUCT_AUTHORITY_MISSING");
            var bytes = input.readAllBytes();
            var value = JSON.readValue(bytes, Decision.class);
            value.validate();
            return value.withAuthorityIdentity(VERSION + ":" + sha256(bytes));
        } catch (IOException failure) {
            throw new IllegalStateException("R5_PRODUCT_AUTHORITY_INVALID", failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }

    public record Decision(
            String authorityVersion,
            String repositoryRevision,
            String assignmentIdentity,
            String transformIdentity,
            String evaluationIdentity,
            String evidenceIdentity,
            String evidenceSha256,
            String a2Sha256,
            String a2Assurance,
            int runs,
            int actualAcquisitions,
            int deterministicCases,
            List<String> failedPredicates,
            List<String> failedCaseIds,
            String disposition,
            String freshJ1Disposition,
            R5ProductTransformEvidence.ExternalProviderUsage externalProviderUsage,
            int apiKeyReads,
            String authorityIdentity
    ) {
        private Decision(
                String authorityVersion,
                String repositoryRevision,
                String assignmentIdentity,
                String transformIdentity,
                String evaluationIdentity,
                String evidenceIdentity,
                String evidenceSha256,
                String a2Sha256,
                String a2Assurance,
                int runs,
                int actualAcquisitions,
                int deterministicCases,
                List<String> failedPredicates,
                List<String> failedCaseIds,
                String disposition,
                String freshJ1Disposition,
                R5ProductTransformEvidence.ExternalProviderUsage externalProviderUsage,
                int apiKeyReads
        ) {
            this(authorityVersion, repositoryRevision, assignmentIdentity, transformIdentity, evaluationIdentity,
                    evidenceIdentity, evidenceSha256, a2Sha256, a2Assurance, runs, actualAcquisitions,
                    deterministicCases, failedPredicates, failedCaseIds, disposition, freshJ1Disposition,
                    externalProviderUsage, apiKeyReads, null);
        }

        public Decision {
            failedPredicates = List.copyOf(Objects.requireNonNull(failedPredicates, "failedPredicates"));
            failedCaseIds = List.copyOf(Objects.requireNonNull(failedCaseIds, "failedCaseIds"));
        }

        private void validate() {
            if (!VERSION.equals(authorityVersion)
                    || repositoryRevision == null || !repositoryRevision.matches("[0-9a-f]{40}")
                    || assignmentIdentity == null || !assignmentIdentity.matches(
                    "renderweave-r5-product-transform-assignment/1\\.0:[0-9a-f]{64}")
                    || !R5ProductTransformEvidence.TRANSFORM_IDENTITY.equals(transformIdentity)
                    || evaluationIdentity == null || !evaluationIdentity.matches(
                    "renderweave-r5-product-transform-evaluation/1\\.0:[0-9a-f]{64}")
                    || evidenceIdentity == null || !evidenceIdentity.matches(
                    "renderweave-r5-product-transform-evidence/1\\.0:[0-9a-f]{64}")
                    || evidenceSha256 == null || !evidenceSha256.matches("[0-9a-f]{64}")
                    || a2Sha256 == null || !a2Sha256.matches("[0-9a-f]{64}")
                    || !"A2_CROSS_IMPLEMENTATION_RECOMPUTE".equals(a2Assurance)
                    || runs != 2 || actualAcquisitions != 16 || deterministicCases != 4
                    || !failedPredicates.equals(List.of(
                    "PER_CASE_HALLUCINATION_NON_INCREASE", "PER_CASE_TARGET_IMPROVEMENT"))
                    || !failedCaseIds.equals(List.of("transit-board-v3"))
                    || !"R5_PRODUCT_TRANSFORM_NOT_QUALIFIED".equals(disposition)
                    || !"LIVE_J1_REQUEST_NOT_ELIGIBLE".equals(freshJ1Disposition)
                    || externalProviderUsage == null || !externalProviderUsage.zeroUsage()
                    || apiKeyReads != 0 || authorityIdentity != null) {
                throw invalid("R5_PRODUCT_AUTHORITY_DRIFT");
            }
        }

        private Decision withAuthorityIdentity(String identity) {
            return new Decision(authorityVersion, repositoryRevision, assignmentIdentity, transformIdentity,
                    evaluationIdentity, evidenceIdentity, evidenceSha256, a2Sha256, a2Assurance, runs,
                    actualAcquisitions, deterministicCases, failedPredicates, failedCaseIds, disposition,
                    freshJ1Disposition, externalProviderUsage, apiKeyReads, identity);
        }

        public boolean allowsActionImplementation() { return false; }

        public boolean allowsFreshJ1Request() { return false; }
    }
}
