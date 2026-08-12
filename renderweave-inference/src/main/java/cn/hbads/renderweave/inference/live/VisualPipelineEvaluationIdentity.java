package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.profile.InferenceProfile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Payload-free identity bridge from the exact v45 runtime policies to offline evaluation. */
public final class VisualPipelineEvaluationIdentity {
    public static final String PIPELINE_VERSION = "renderweave-inference-pipeline/4.28";
    public static final String VALIDATOR_VERSION = "renderweave-product-v45-validator-set/1.0";
    public static final String MATERIALIZER_VERSION = "renderweave-product-v45-materializer/1.0";

    private VisualPipelineEvaluationIdentity() { }

    public static String validatorIdentity(InferenceProfile profile) {
        requireV45(profile);
        var material = new ArrayList<>(List.of(
                VALIDATOR_VERSION,
                profile.pipelineVersion(),
                VisualElementInventory.VERSION,
                VisualGroundingPlan.VERSION,
                VisualHierarchyPlan.VERSION_V2,
                VisualElementBindingPlan.VERSION_V2,
                VisualSemanticVerifier.VERSION));
        material.addAll(LiveInferenceWorker.evaluationPolicySnapshot(profile).validatorPolicyNames());
        return VALIDATOR_VERSION + ":" + sha256(material);
    }

    public static String materializerIdentity(InferenceProfile profile) {
        requireV45(profile);
        return MATERIALIZER_VERSION + ":" + sha256(List.of(
                MATERIALIZER_VERSION,
                profile.pipelineVersion(),
                profile.candidateContractVersion(),
                CandidateBundle.CONTRACT_VERSION,
                VisualPlanCandidateMaterializer.VERSION,
                ImageOnlyCandidateCanonicalizer.VERSION,
                LiveInferenceWorker.evaluationPolicySnapshot(profile).bindingFieldPolicyName(),
                Integer.toString(profile.lowConfidenceThresholdBps())));
    }

    private static void requireV45(InferenceProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!PIPELINE_VERSION.equals(profile.pipelineVersion())
                || !profile.profileId().endsWith("-product-v45-hybrid-generic")
                || profile.supportedModes().size() != 1
                || !"IMAGE_ONLY".equals(profile.supportedModes().getFirst().name())
                || profile.toolsAllowed()
                || profile.remoteMediaAllowed()
                || profile.maximumRepairRounds() != 0
                || !CandidateBundle.CONTRACT_VERSION.equals(profile.candidateContractVersion())) {
            throw new IllegalArgumentException("PRODUCT_V45_EVALUATION_PROFILE_INVALID");
        }
    }

    private static String sha256(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                var encoded = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(encoded);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }
}
