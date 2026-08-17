package cn.hbads.renderweave.inference.certification;

import java.util.List;

public record ImageOnlyCertificationP0Report(
        String reportVersion,
        ProfileProof profile,
        AuthorityProof authority,
        ManifestProof manifest,
        LayeredR1Proof layeredR1,
        DryRunProof dryRun,
        AuthorizationProof authorization,
        ExternalProviderProof externalProvider
) {
    public static final String VERSION = "renderweave-image-only-certification-p0-report/1.0";

    public record ProfileProof(
            String profileId,
            String canonicalSha256,
            int maximumTotalCalls,
            long maximumRunCostMicrosCny,
            boolean hiddenFromProductCatalog,
            List<String> semanticDiffFields
    ) { }

    public record AuthorityProof(
            String inventorySha256,
            String baselineLifecycle,
            List<String> reusableReferences,
            List<String> prohibitedReferences
    ) { }

    public record ManifestProof(
            String version,
            String manifestIdentity,
            String profileId,
            String profileSha256,
            String corpusIdentity,
            String r1InfrastructureIdentity,
            String evaluatorIdentity,
            String assignmentSeed,
            List<ThresholdProof> thresholds,
            List<CertificationCanaryCase> canaries,
            List<CertificationCaseAssignment> assignments
    ) { }

    public record ThresholdProof(String stage, int caseCount, int acceptanceThreshold) { }

    public record LayeredR1Proof(
            String evaluationIdentity,
            String corpusIdentity,
            int caseCount,
            int metricCount
    ) { }

    public record DryRunProof(
            StageProof canary,
            StageProof dev,
            StageProof finalStage,
            StageProof negativeCanary
    ) { }

    public record StageProof(
            String stage,
            int acceptedCases,
            int totalCases,
            boolean passed,
            String evidenceIdentity
    ) { }

    public record AuthorizationProof(
            int openAuthorizationCount,
            int maximumWindowHours,
            long maximumModelTokens,
            String schemaPath,
            String nonExecutableTemplatePath
    ) { }

    public record ExternalProviderProof(
            long attempts,
            long reservations,
            long costMicrosCny,
            int apiKeyReads
    ) { }
}
