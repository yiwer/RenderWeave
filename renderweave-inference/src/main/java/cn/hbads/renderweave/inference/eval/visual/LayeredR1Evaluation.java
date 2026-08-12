package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.live.VisualPipelineEvaluationIdentity;
import cn.hbads.renderweave.inference.profile.InferenceProfile;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.profile.InferencePromptRegistry;
import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Complete, deterministic and zero-provider R1 evaluation assembly. */
public final class LayeredR1Evaluation {
    public static final String VERSION = "renderweave-layered-r1-evaluation/1.0";
    private static final String PROFILE_REPLAY_VERSION = "renderweave-product-v45-layered-replay/1.0";
    private static final String PROMPT_SET_VERSION = "renderweave-product-v45-prompt-set/1.0";
    private static final String ZERO_BUDGET_VERSION = "renderweave-zero-provider-budget/1.0";
    private static final String DECODING_MODE_VERSION = "renderweave-layered-decoding-mode/1.0";
    private static final Set<String> PRODUCT_V45_PROFILE_IDS = Set.of(
            "dashscope-qwen37-flash-product-v45-hybrid-generic",
            "dashscope-qwen37-plus-product-v45-hybrid-generic",
            "dashscope-qwen38-max-product-v45-hybrid-generic");

    public Result evaluate() {
        var corpus = new LayeredVisualCorpus();
        var profiles = productV45Profiles();
        var identity = identity(corpus, profiles);
        var evaluator = new LayeredVisualEvaluator();
        var records = corpus.cases().stream()
                .map(item -> evaluator.evaluate(item, LayeredSyntheticReplay.perfect(item)))
                .toList();
        var report = new LayeredEvaluationReporter().report(corpus, identity, records);
        requireZeroProvider(report.global().runtime());
        var codec = new LayeredEvaluationReportJsonCodec();
        var encoded = codec.write(report);
        var reportIdentity = codec.reportIdentity(report);
        codec.read(encoded, reportIdentity);
        return new Result(report, reportIdentity, encoded);
    }

    private static LayeredEvaluationIdentity identity(
            LayeredVisualCorpus corpus,
            List<InferenceProfileRegistry.ProfileResource> profiles
    ) {
        var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
        var successor = new DocumentObservationSuccessorIdentity(policy);
        var shapes = new StageResponseShapeCatalog();
        var reference = profiles.getFirst().profile();
        var validatorIdentity = VisualPipelineEvaluationIdentity.validatorIdentity(reference);
        var materializerIdentity = VisualPipelineEvaluationIdentity.materializerIdentity(reference);
        for (var profile : profiles) {
            if (!validatorIdentity.equals(VisualPipelineEvaluationIdentity.validatorIdentity(profile.profile()))
                    || !materializerIdentity.equals(
                    VisualPipelineEvaluationIdentity.materializerIdentity(profile.profile()))) {
                throw new IllegalStateException("PRODUCT_V45_RUNTIME_IDENTITY_DIVERGED");
            }
        }
        return new LayeredEvaluationIdentity(
                corpus.corpusIdentity(),
                LayeredVisualAnnotation.VERSION,
                corpus.annotationSetIdentity(),
                corpus.renderContractIdentity(),
                successor.identity(),
                DocumentObservationIR.VERSION,
                AcquisitionPolicy.VERSION + ":" + policy.identity(),
                policy.adapterIdentity(),
                "weight-sha256:" + policy.modelManifestSha256(),
                policy.projectionIdentity(),
                policy.readingOrderDerivationIdentity(),
                StageResponseShapeCatalog.VERSION + ":" + shapes.identity(),
                profileReplayIdentity(profiles),
                promptIdentity(reference),
                validatorIdentity,
                materializerIdentity,
                evaluatorIdentity(),
                ZERO_BUDGET_VERSION + ":" + sha256(List.of(
                        ZERO_BUDGET_VERSION,
                        "providerAttempts=0",
                        "providerReservations=0",
                        "externalProviderCostMicrosCny=0",
                        "estimatedCostMicrosCny=0",
                        "settledCostMicrosCny=0")),
                DECODING_MODE_VERSION + ":" + sha256(List.of(
                        DECODING_MODE_VERSION,
                        LayeredSyntheticReplay.VERSION,
                        "replay=deterministic-gold-projection",
                        "productResponseFormat=JSON_OBJECT",
                        "thinkingEnabled=false",
                        "toolsAllowed=false",
                        "remoteMediaAllowed=false")));
    }

    private static List<InferenceProfileRegistry.ProfileResource> productV45Profiles() {
        var profiles = new InferenceProfileRegistry().productLiveProfiles().stream()
                .sorted(Comparator.comparing(item -> item.profile().profileId()))
                .toList();
        if (!profiles.stream().map(item -> item.profile().profileId())
                .collect(java.util.stream.Collectors.toSet()).equals(PRODUCT_V45_PROFILE_IDS)
                || profiles.stream().anyMatch(item ->
                !"EXPERIMENTAL".equals(item.profile().certification())
                        || !VisualPipelineEvaluationIdentity.PIPELINE_VERSION.equals(
                        item.profile().pipelineVersion()))) {
            throw new IllegalStateException("PRODUCT_V45_LIFECYCLE_INVALID");
        }
        return profiles;
    }

    private static String profileReplayIdentity(List<InferenceProfileRegistry.ProfileResource> profiles) {
        var material = new ArrayList<String>();
        material.add(PROFILE_REPLAY_VERSION);
        material.add(LayeredSyntheticReplay.VERSION);
        material.add("externalProvider=false");
        material.add("scriptedCallsPerCase=3");
        for (var profile : profiles) {
            material.add(profile.profile().profileId());
            material.add(profile.snapshotJson());
        }
        return PROFILE_REPLAY_VERSION + ":" + sha256(material);
    }

    private static String promptIdentity(InferenceProfile profile) {
        var prompts = new InferencePromptRegistry();
        var resources = List.of(
                prompts.require(profile.promptVersion()),
                prompts.requireHybridVisualStage(profile.elementPromptVersion(), profile.visualHintPackVersion(),
                        profile.documentVisionPromptVersion()),
                prompts.requireHybridVisualStage(profile.hierarchyPromptVersion(), profile.visualHintPackVersion(),
                        profile.documentVisionPromptVersion()),
                prompts.requireHybridVisualStage(profile.bindingPromptVersion(), profile.visualHintPackVersion(),
                        profile.documentVisionPromptVersion()));
        var material = new ArrayList<String>();
        material.add(PROMPT_SET_VERSION);
        resources.forEach(item -> {
            material.add(item.promptVersion());
            material.add(item.text());
        });
        return PROMPT_SET_VERSION + ":" + sha256(material);
    }

    private static String evaluatorIdentity() {
        return LayeredVisualEvaluator.VERSION + ":" + sha256(List.of(
                LayeredVisualEvaluator.VERSION,
                LayeredMetricMath.VERSION,
                LayeredEvaluationRecord.VERSION,
                LayeredEvaluationReporter.VERSION,
                LayeredEvaluationReport.VERSION,
                "integer-basis-points-floor/1.0"));
    }

    private static void requireZeroProvider(LayeredEvaluationReport.RuntimeAggregate runtime) {
        if (runtime.inputTokens() != 0 || runtime.outputTokens() != 0
                || runtime.estimatedCostMicrosCny() != 0 || runtime.settledCostMicrosCny() != 0
                || runtime.providerAttempts() != 0 || runtime.providerReservations() != 0
                || runtime.externalProviderCostMicrosCny() != 0) {
            throw new IllegalStateException("LAYERED_R1_EXTERNAL_PROVIDER_USAGE");
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

    public record Result(
            LayeredEvaluationReport report,
            String reportIdentity,
            byte[] encodedReport
    ) {
        public Result {
            Objects.requireNonNull(report, "report");
            reportIdentity = LayeredVisualAnnotation.requireIdentity(reportIdentity,
                    "LAYERED_R1_REPORT_IDENTITY_INVALID");
            encodedReport = Objects.requireNonNull(encodedReport, "encodedReport").clone();
            if (encodedReport.length == 0) throw new IllegalArgumentException("LAYERED_R1_REPORT_EMPTY");
        }

        @Override
        public byte[] encodedReport() {
            return encodedReport.clone();
        }

        @Override
        public String toString() {
            return "Result[version=" + VERSION + ", reportIdentity=" + reportIdentity
                    + ", caseCount=" + report.observedCaseCount() + ", payload=<redacted>]";
        }
    }
}
