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

/** Immutable fail-closed authority produced by post-run review of the closed R5 experiment. */
public final class R5ProductTransformAuthority {
    public static final String VERSION = "renderweave-r5-product-transform-authority/1.1";
    private static final String RESOURCE = "visual-eval/r5/product-transform-authority-v2.json";
    private static final String RESOURCE_SHA256 =
            "a6ef7ee0820ea906cb371371d66a8eaef3ba77ac569ae24d6e4935e144ef4475";
    private static final String RUN_REVISION = "a31d54125764254c2814ecc5c7114137a3a11b29";
    private static final String ASSIGNMENT_IDENTITY =
            "renderweave-r5-product-transform-assignment/1.0:46c8e4c9c28b8628bac6532deeeb1a9ee311dda58b1a76f23a1b1d70abe7b540";
    private static final String EVALUATION_IDENTITY =
            "renderweave-r5-product-transform-evaluation/1.0:e25bb4531545a399ee2e83082cc7b3dbceda0cbaa8e2e7965a570e3484925d46";
    private static final String PRODUCER_EVIDENCE_IDENTITY =
            "renderweave-r5-product-transform-evidence/1.0:3041df28a17167c9b9eb322d0f60cf2508b2cff0ac8da344449c544908211528";
    private static final String PRODUCER_EVIDENCE_SHA256 =
            "73788b54ef9f277c5eb9b927fc6a1882a82ab997145080ad1075b3e1e27c528a";
    private static final String VERIFICATION_SUMMARY_SHA256 =
            "5aceab1ff9edf073753a1aa9796c27cb5773c60472859cc33cb4019bf13cfe10";
    private static final List<String> REJECTION_REASONS = List.of(
            "NORMALIZED_RASTER_INPUT_NOT_PROVEN",
            "PRODUCT_STATIC_ACQUISITION_NOT_PROVEN",
            "INDEPENDENT_LAYERED_METRICS_NOT_REPLAYED",
            "PROVIDER_ZERO_NOT_INDEPENDENTLY_GROUNDED",
            "PER_CASE_HALLUCINATION_NON_INCREASE",
            "PER_CASE_TARGET_IMPROVEMENT");
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
            var digest = sha256(bytes);
            if (!RESOURCE_SHA256.equals(digest)) throw invalid("R5_PRODUCT_AUTHORITY_RESOURCE_DRIFT");
            var value = JSON.readValue(bytes, Decision.class);
            value.validate();
            return value.withAuthorityIdentity(VERSION + ":" + digest);
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
            String producerEvidenceIdentity,
            String producerEvidenceSha256,
            String verificationSummarySha256,
            String verificationSummaryClaimedAssurance,
            String acceptedAssurance,
            String a2Disposition,
            int runs,
            int actualAcquisitions,
            int deterministicCases,
            List<String> rejectionReasonCodes,
            List<String> failedCaseIds,
            String disposition,
            String freshJ1Disposition,
            R5ProductTransformEvidence.ExternalProviderUsage reportedExternalProviderUsage,
            int reportedApiKeyReads,
            String authorityIdentity
    ) {
        private Decision(
                String authorityVersion,
                String repositoryRevision,
                String assignmentIdentity,
                String transformIdentity,
                String evaluationIdentity,
                String producerEvidenceIdentity,
                String producerEvidenceSha256,
                String verificationSummarySha256,
                String verificationSummaryClaimedAssurance,
                String acceptedAssurance,
                String a2Disposition,
                int runs,
                int actualAcquisitions,
                int deterministicCases,
                List<String> rejectionReasonCodes,
                List<String> failedCaseIds,
                String disposition,
                String freshJ1Disposition,
                R5ProductTransformEvidence.ExternalProviderUsage reportedExternalProviderUsage,
                int reportedApiKeyReads
        ) {
            this(authorityVersion, repositoryRevision, assignmentIdentity, transformIdentity, evaluationIdentity,
                    producerEvidenceIdentity, producerEvidenceSha256, verificationSummarySha256,
                    verificationSummaryClaimedAssurance, acceptedAssurance, a2Disposition, runs,
                    actualAcquisitions, deterministicCases, rejectionReasonCodes, failedCaseIds, disposition,
                    freshJ1Disposition, reportedExternalProviderUsage, reportedApiKeyReads, null);
        }

        public Decision {
            rejectionReasonCodes = List.copyOf(Objects.requireNonNull(rejectionReasonCodes, "rejectionReasonCodes"));
            failedCaseIds = List.copyOf(Objects.requireNonNull(failedCaseIds, "failedCaseIds"));
        }

        private void validate() {
            if (!VERSION.equals(authorityVersion)
                    || !RUN_REVISION.equals(repositoryRevision)
                    || !ASSIGNMENT_IDENTITY.equals(assignmentIdentity)
                    || !R5ProductTransformEvidence.TRANSFORM_IDENTITY.equals(transformIdentity)
                    || !EVALUATION_IDENTITY.equals(evaluationIdentity)
                    || !PRODUCER_EVIDENCE_IDENTITY.equals(producerEvidenceIdentity)
                    || !PRODUCER_EVIDENCE_SHA256.equals(producerEvidenceSha256)
                    || !VERIFICATION_SUMMARY_SHA256.equals(verificationSummarySha256)
                    || !"A2_CROSS_IMPLEMENTATION_RECOMPUTE".equals(verificationSummaryClaimedAssurance)
                    || !"A1_PRODUCER_REPORT_CONSISTENCY_ONLY".equals(acceptedAssurance)
                    || !"NOT_ESTABLISHED".equals(a2Disposition)
                    || runs != 2 || actualAcquisitions != 16 || deterministicCases != 4
                    || !REJECTION_REASONS.equals(rejectionReasonCodes)
                    || !failedCaseIds.equals(List.of("transit-board-v3"))
                    || !"R5_PRODUCT_TRANSFORM_NOT_QUALIFIED".equals(disposition)
                    || !"LIVE_J1_REQUEST_NOT_ELIGIBLE".equals(freshJ1Disposition)
                    || reportedExternalProviderUsage == null || !reportedExternalProviderUsage.zeroUsage()
                    || reportedApiKeyReads != 0 || authorityIdentity != null) {
                throw invalid("R5_PRODUCT_AUTHORITY_DRIFT");
            }
        }

        private Decision withAuthorityIdentity(String identity) {
            return new Decision(authorityVersion, repositoryRevision, assignmentIdentity, transformIdentity,
                    evaluationIdentity, producerEvidenceIdentity, producerEvidenceSha256,
                    verificationSummarySha256, verificationSummaryClaimedAssurance, acceptedAssurance,
                    a2Disposition, runs, actualAcquisitions, deterministicCases, rejectionReasonCodes,
                    failedCaseIds, disposition, freshJ1Disposition, reportedExternalProviderUsage,
                    reportedApiKeyReads, identity);
        }

        public boolean a2Established() { return false; }

        public boolean allowsTransformRerun() { return false; }

        public boolean allowsActionImplementation() { return false; }

        public boolean allowsFreshJ1Request() { return false; }
    }
}
