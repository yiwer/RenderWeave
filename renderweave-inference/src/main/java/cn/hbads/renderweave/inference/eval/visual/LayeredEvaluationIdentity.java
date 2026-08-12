package cn.hbads.renderweave.inference.eval.visual;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Complete R1 identity. Changing any evaluation input creates a different immutable report lineage. */
public final class LayeredEvaluationIdentity {
    public static final String VERSION = "renderweave-layered-evaluation/1.0";

    private static final List<String> KEYS = List.of(
            "inputSetIdentity", "annotationVersion", "annotationSetIdentity", "normalizationRenderIdentity",
            "observationSuccessorIdentity", "observationContractIdentity", "acquisitionPolicyIdentity",
            "adapterIdentity", "weightIdentity", "projectionIdentity", "orderIdentity",
            "shapeCatalogIdentity", "providerProfileReplayIdentity", "promptIdentity", "validatorIdentity",
            "materializerIdentity", "evaluatorIdentity", "budgetIdentity", "decodingModeIdentity");

    private final Map<String, String> components;
    private final String identity;

    public LayeredEvaluationIdentity(
            String inputSetIdentity,
            String annotationVersion,
            String annotationSetIdentity,
            String normalizationRenderIdentity,
            String observationSuccessorIdentity,
            String observationContractIdentity,
            String acquisitionPolicyIdentity,
            String adapterIdentity,
            String weightIdentity,
            String projectionIdentity,
            String orderIdentity,
            String shapeCatalogIdentity,
            String providerProfileReplayIdentity,
            String promptIdentity,
            String validatorIdentity,
            String materializerIdentity,
            String evaluatorIdentity,
            String budgetIdentity,
            String decodingModeIdentity
    ) {
        var values = List.of(inputSetIdentity, annotationVersion, annotationSetIdentity,
                normalizationRenderIdentity, observationSuccessorIdentity, observationContractIdentity,
                acquisitionPolicyIdentity, adapterIdentity, weightIdentity, projectionIdentity, orderIdentity,
                shapeCatalogIdentity, providerProfileReplayIdentity, promptIdentity, validatorIdentity,
                materializerIdentity, evaluatorIdentity, budgetIdentity, decodingModeIdentity);
        var result = new LinkedHashMap<String, String>();
        for (var index = 0; index < KEYS.size(); index++) {
            result.put(KEYS.get(index), requireComponent(values.get(index), KEYS.get(index)));
        }
        components = java.util.Collections.unmodifiableMap(result);
        identity = VERSION + ":" + sha256(values);
    }

    public String identity() { return identity; }

    public Map<String, String> components() { return components; }

    public String observationSuccessorIdentity() { return components.get("observationSuccessorIdentity"); }

    public static LayeredEvaluationIdentity fromComponents(Map<String, String> values, String expectedIdentity) {
        Objects.requireNonNull(values, "values");
        if (!values.keySet().equals(Set.copyOf(KEYS))) {
            throw new IllegalArgumentException("EVALUATION_IDENTITY_COMPONENTS_INVALID");
        }
        var result = new LayeredEvaluationIdentity(
                values.get(KEYS.get(0)), values.get(KEYS.get(1)), values.get(KEYS.get(2)),
                values.get(KEYS.get(3)), values.get(KEYS.get(4)), values.get(KEYS.get(5)),
                values.get(KEYS.get(6)), values.get(KEYS.get(7)), values.get(KEYS.get(8)),
                values.get(KEYS.get(9)), values.get(KEYS.get(10)), values.get(KEYS.get(11)),
                values.get(KEYS.get(12)), values.get(KEYS.get(13)), values.get(KEYS.get(14)),
                values.get(KEYS.get(15)), values.get(KEYS.get(16)), values.get(KEYS.get(17)),
                values.get(KEYS.get(18)));
        if (!result.identity.equals(expectedIdentity)) {
            throw new IllegalArgumentException("EVALUATION_IDENTITY_DRIFT");
        }
        return result;
    }

    @Override
    public String toString() {
        return "LayeredEvaluationIdentity[version=" + VERSION + ", identity=" + identity + "]";
    }

    private static String requireComponent(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256
                || !value.matches("[A-Za-z0-9][A-Za-z0-9._+/:=-]{0,255}")) {
            throw new IllegalArgumentException("EVALUATION_IDENTITY_COMPONENT_INVALID:" + name);
        }
        return value;
    }

    private static String sha256(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
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
