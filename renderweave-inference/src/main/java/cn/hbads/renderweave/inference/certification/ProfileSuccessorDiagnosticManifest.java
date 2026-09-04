package cn.hbads.renderweave.inference.certification;

import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Frozen, one-case regression identity. It is intentionally not a certification cycle. */
public final class ProfileSuccessorDiagnosticManifest {
    public static final String VERSION =
            "renderweave-image-only-profile-successor-diagnostic/1.0";
    public static final String NORMALIZATION_VERSION =
            "renderweave-image-only-fresh-normalization/1.0";
    public static final String EVALUATOR_VERSION =
            "renderweave-image-only-profile-successor-diagnostic-evaluator/1.0";
    public static final String V46_FAILED_ARTIFACT_SHA256 =
            "51942b84ac65efcb28d02fff359222f60b8550fe5b6d5e87389582fc5a48cfc8";

    private final UUID cycleId;
    private final String profileId;
    private final String profileSha256;
    private final String manifestIdentity;
    private final String evaluatorIdentity;
    private final String normalizationIdentity;
    private final AuthorizedCertificationCase diagnosticCase;
    private final Instant createdAt;

    private ProfileSuccessorDiagnosticManifest(
            UUID cycleId,
            String profileId,
            String profileSha256,
            String manifestIdentity,
            String evaluatorIdentity,
            String normalizationIdentity,
            AuthorizedCertificationCase diagnosticCase,
            Instant createdAt
    ) {
        this.cycleId = Objects.requireNonNull(cycleId, "cycleId");
        this.profileId = requireSuccessorProfileId(profileId);
        CertificationCanaryCase.requireSha(profileSha256);
        this.profileSha256 = profileSha256;
        this.manifestIdentity = requireIdentity(manifestIdentity, VERSION);
        this.evaluatorIdentity = requireIdentity(evaluatorIdentity, EVALUATOR_VERSION);
        this.normalizationIdentity = requireIdentity(normalizationIdentity, NORMALIZATION_VERSION);
        this.diagnosticCase = Objects.requireNonNull(diagnosticCase, "diagnosticCase");
        if (!V46_FAILED_ARTIFACT_SHA256.equals(diagnosticCase.artifactSha256())) {
            throw new IllegalArgumentException("PROFILE_SUCCESSOR_DIAGNOSTIC_ARTIFACT_MISMATCH");
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ProfileSuccessorDiagnosticManifest create(
            UUID cycleId,
            String profileSha256,
            String normalizationIdentity,
            AuthorizedCertificationCase diagnosticCase,
            Instant createdAt
    ) {
        return createForProfile(
                ProfileRunBudgetPolicy.IMAGE_ONLY_V47_PROFILE_ID, cycleId, profileSha256,
                normalizationIdentity, diagnosticCase, createdAt
        );
    }

    public static ProfileSuccessorDiagnosticManifest createForProfile(
            String profileId,
            UUID cycleId,
            String profileSha256,
            String normalizationIdentity,
            AuthorizedCertificationCase diagnosticCase,
            Instant createdAt
    ) {
        profileId = requireSuccessorProfileId(profileId);
        var profile = new InferenceProfileRegistry().require(profileId);
        if (!profile.canonicalSha256().equals(profileSha256)) {
            throw new IllegalArgumentException("PROFILE_SUCCESSOR_DIAGNOSTIC_PROFILE_SHA_DRIFT");
        }
        Objects.requireNonNull(cycleId, "cycleId");
        Objects.requireNonNull(createdAt, "createdAt");
        requireIdentity(normalizationIdentity, NORMALIZATION_VERSION);
        Objects.requireNonNull(diagnosticCase, "diagnosticCase");
        if (!V46_FAILED_ARTIFACT_SHA256.equals(diagnosticCase.artifactSha256())) {
            throw new IllegalArgumentException("PROFILE_SUCCESSOR_DIAGNOSTIC_ARTIFACT_MISMATCH");
        }
        var evaluatorIdentity = EVALUATOR_VERSION + ":" + CertificationIdentity.sha256(List.of(
                EVALUATOR_VERSION,
                "terminal=REVIEW_REQUIRED",
                "manual-acceptance=required",
                "certification-credit=forbidden",
                "grant=forbidden"
        ));
        var manifestIdentity = VERSION + ":" + CertificationIdentity.sha256(List.of(
                VERSION,
                cycleId.toString(),
                profileId,
                profileSha256,
                normalizationIdentity,
                diagnosticCase.caseId(),
                diagnosticCase.artifactSha256(),
                "USER_PROVIDED",
                "ORDINARY_DESIGN",
                evaluatorIdentity,
                createdAt.toString()
        ));
        return new ProfileSuccessorDiagnosticManifest(
                cycleId, profileId, profileSha256, manifestIdentity, evaluatorIdentity,
                normalizationIdentity, diagnosticCase, createdAt
        );
    }

    public UUID cycleId() { return cycleId; }
    public String profileId() { return profileId; }
    public String profileSha256() { return profileSha256; }
    public String manifestIdentity() { return manifestIdentity; }
    public String evaluatorIdentity() { return evaluatorIdentity; }
    public String normalizationIdentity() { return normalizationIdentity; }
    public AuthorizedCertificationCase diagnosticCase() { return diagnosticCase; }
    public String inputProvenance() { return "USER_PROVIDED"; }
    public String dataClassification() { return "ORDINARY_DESIGN"; }
    public Instant createdAt() { return createdAt; }

    private static String requireIdentity(String value, String version) {
        if (value == null || !value.matches(java.util.regex.Pattern.quote(version)
                + ":[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "PROFILE_SUCCESSOR_DIAGNOSTIC_IDENTITY_INVALID");
        }
        return value;
    }

    private static String requireSuccessorProfileId(String profileId) {
        if (!ProfileRunBudgetPolicy.IMAGE_ONLY_V47_PROFILE_ID.equals(profileId)
                && !ProfileRunBudgetPolicy.IMAGE_ONLY_V48_PROFILE_ID.equals(profileId)
                && !ProfileRunBudgetPolicy.IMAGE_ONLY_V49_PROFILE_ID.equals(profileId)
                && !ProfileRunBudgetPolicy.IMAGE_ONLY_V50_PROFILE_ID.equals(profileId)
                && !ProfileRunBudgetPolicy.IMAGE_ONLY_V51_PROFILE_ID.equals(profileId)
                && !ProfileRunBudgetPolicy.IMAGE_ONLY_V52_PROFILE_ID.equals(profileId)) {
            throw new IllegalArgumentException(
                    "PROFILE_SUCCESSOR_DIAGNOSTIC_PROFILE_ID_INVALID");
        }
        return profileId;
    }
}
