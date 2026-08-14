package cn.hbads.renderweave.inference.eval.visual.quality;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic policy engine for the single offline repair decision seam. */
final class R2R5TriggerDecisionEngine {
    private static final Map<FrozenQualityEvidencePack.Route, Set<String>> REQUIRED_PREDICATES = Map.of(
            FrozenQualityEvidencePack.Route.R2, Set.of(
                    "R2_STABLE_PERCEPTION_GAP", "R2_CAPABILITY_ADMITTED", "R2_SHADOW_NET_BENEFIT"),
            FrozenQualityEvidencePack.Route.R3, Set.of("R3_CAUSAL_ORDER_REPEAT_DEFECT"),
            FrozenQualityEvidencePack.Route.R4, Set.of("R4_SHAPE_CODEC_BOTTLENECK"),
            FrozenQualityEvidencePack.Route.R5, Set.of("R5_STATIC_VIEW_CAUSAL_GAIN")
    );

    private final R2R5TriggerDecisionJsonCodec codec = new R2R5TriggerDecisionJsonCodec();

    public R2R5TriggerDecision decide(FrozenQualityEvidencePack evidencePack) {
        Objects.requireNonNull(evidencePack, "evidencePack");
        var decisions = new ArrayList<R2R5TriggerDecision.RouteDecision>();
        for (var route : evidencePack.routes()) {
            var expected = REQUIRED_PREDICATES.get(route.route());
            var actual = route.predicates().stream()
                    .map(FrozenQualityEvidencePack.PredicateEvidence::predicateId).collect(java.util.stream.Collectors.toSet());
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException("QUALITY_REPAIR_REQUIRED_PREDICATE_SET_INVALID");
            }
            var allPass = route.predicates().stream().allMatch(item ->
                    item.result() == FrozenQualityEvidencePack.PredicateResult.PASS);
            var anyFail = route.predicates().stream().anyMatch(item ->
                    item.result() == FrozenQualityEvidencePack.PredicateResult.FAIL);
            var disposition = allPass
                    ? R2R5TriggerDecision.RouteDisposition.TRIGGERED
                    : anyFail
                    ? R2R5TriggerDecision.RouteDisposition.REJECTED_BY_CURRENT_EVIDENCE
                    : R2R5TriggerDecision.RouteDisposition.EVIDENCE_REQUIRED;
            if (route.route() == FrozenQualityEvidencePack.Route.R4
                    && disposition != R2R5TriggerDecision.RouteDisposition.REJECTED_BY_CURRENT_EVIDENCE) {
                throw new IllegalArgumentException("QUALITY_REPAIR_R4_CURRENT_EVIDENCE_CONFLICT");
            }
            decisions.add(new R2R5TriggerDecision.RouteDecision(
                    route.route(), allPass, disposition, route.predicates()));
        }
        return new R2R5TriggerDecision(
                R2R5TriggerDecision.VERSION,
                codec.evidencePackIdentity(evidencePack),
                decisions,
                R2R5TriggerDecision.deriveOverall(decisions),
                evidencePack.externalProviderUsage());
    }
}
