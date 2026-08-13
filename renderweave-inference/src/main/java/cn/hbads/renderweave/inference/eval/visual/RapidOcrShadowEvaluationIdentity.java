package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Exact identity for the zero-Provider corpus-v2 RapidOCR re-anchor. */
public record RapidOcrShadowEvaluationIdentity(String identity, Map<String, String> components) {
    public static final String VERSION = "renderweave-rapidocr-shadow-evaluation/1.0";

    public RapidOcrShadowEvaluationIdentity {
        components = Map.copyOf(Objects.requireNonNull(components, "components"));
        if (components.isEmpty() || components.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || !entry.getKey().matches("[a-z][A-Za-z0-9]{0,63}")
                        || entry.getValue() == null || entry.getValue().isBlank()
                        || entry.getValue().chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("RAPIDOCR_SHADOW_IDENTITY_COMPONENT_INVALID");
        }
        var expected = VERSION + ":" + sha256(new TreeMap<>(components));
        if (!expected.equals(identity)) {
            throw new IllegalArgumentException("RAPIDOCR_SHADOW_EVALUATION_IDENTITY_DRIFT");
        }
    }

    public static RapidOcrShadowEvaluationIdentity exact(
            LayeredVisualCorpus corpus,
            AcquisitionPolicy policy
    ) {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(policy, "policy");
        var values = new LinkedHashMap<String, String>();
        values.put("inputSetIdentity", corpus.corpusIdentity());
        values.put("annotationSetIdentity", corpus.annotationSetIdentity());
        values.put("annotationVersion", LayeredVisualAnnotation.VERSION);
        values.put("normalizationRenderIdentity", corpus.renderContractIdentity());
        values.put("observationContractIdentity", DocumentObservationIR.VERSION);
        values.put("acquisitionPolicyIdentity", AcquisitionPolicy.VERSION + ":" + policy.identity());
        values.put("capabilityIdentity", policy.capabilityIdentity());
        values.put("adapterIdentity", policy.adapterIdentity());
        values.put("engineIdentity", policy.engine() + ":" + policy.engineVersion());
        values.put("weightIdentity", "weight-sha256:" + policy.modelManifestSha256());
        values.put("preprocessingIdentity", policy.preprocessingIdentity());
        values.put("postprocessingIdentity", policy.postprocessingIdentity());
        values.put("coordinateIdentity", policy.coordinateSpaceIdentity() + ":" + policy.boxSemanticsIdentity());
        values.put("projectionIdentity", policy.projectionIdentity());
        values.put("orderIdentity", policy.readingOrderDerivationIdentity());
        values.put("confidenceIdentity", policy.confidenceScaleIdentity() + ":"
                + policy.confidenceBucketProjectionIdentity());
        values.put("canonicalizationIdentity", policy.canonicalizationIdentity());
        values.put("caseEvaluatorIdentity", RapidOcrShadowCaseEvaluator.VERSION);
        values.put("reporterIdentity", RapidOcrShadowReporter.VERSION);
        values.put("runProtocolIdentity", "two-isolated-complete-runs/1.0");
        values.put("smallTextSliceIdentity", "slot-height-at-most-1800-bps/1.0");
        values.put("authorityIdentity", "corpus-v2-shadow-diagnostic-only/1.0");
        values.put("budgetIdentity", "zero-provider-attempts-reservations-cost/1.0");
        var immutable = Map.copyOf(values);
        return new RapidOcrShadowEvaluationIdentity(VERSION + ":" + sha256(new TreeMap<>(immutable)), immutable);
    }

    public static RapidOcrShadowEvaluationIdentity fromComponents(
            Map<String, String> components,
            String expectedIdentity
    ) {
        return new RapidOcrShadowEvaluationIdentity(expectedIdentity, components);
    }

    private static String sha256(Map<String, String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var entry : values.entrySet()) {
                update(digest, entry.getKey());
                update(digest, entry.getValue());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }
}
