package cn.hbads.renderweave.inference.eval.visual.quality;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Canonical, payload-safe result of the R2-R5 offline repair decision seam. */
public record R2R5TriggerDecision(
        String decisionVersion,
        String evidencePackIdentity,
        List<RouteDecision> routes,
        OverallDisposition overallDisposition,
        FrozenQualityEvidencePack.ExternalProviderUsage externalProviderUsage
) {
    public static final String VERSION = "R2R5TriggerDecision/1.0";

    public R2R5TriggerDecision {
        if (!VERSION.equals(decisionVersion)) {
            throw invalid("QUALITY_REPAIR_DECISION_VERSION_INVALID");
        }
        if (evidencePackIdentity == null || !evidencePackIdentity.matches(
                "renderweave-frozen-quality-evidence-pack/1\\.0:[0-9a-f]{64}")) {
            throw invalid("QUALITY_REPAIR_EVIDENCE_PACK_IDENTITY_INVALID");
        }
        var canonical = new ArrayList<>(List.copyOf(Objects.requireNonNull(routes, "routes")));
        if (canonical.size() != FrozenQualityEvidencePack.Route.values().length
                || canonical.stream().anyMatch(Objects::isNull)
                || !EnumSet.copyOf(canonical.stream().map(RouteDecision::route).toList())
                .equals(EnumSet.allOf(FrozenQualityEvidencePack.Route.class))) {
            throw invalid("QUALITY_REPAIR_DECISION_ROUTE_SET_INVALID");
        }
        canonical.sort(Comparator.comparing(RouteDecision::route));
        routes = List.copyOf(canonical);
        Objects.requireNonNull(overallDisposition, "overallDisposition");
        externalProviderUsage = Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
        if (!externalProviderUsage.zeroUsage()) {
            throw invalid("QUALITY_REPAIR_DECISION_PROVIDER_USAGE_NONZERO");
        }
    }

    public RouteDecision requireRoute(FrozenQualityEvidencePack.Route route) {
        return routes.stream().filter(item -> item.route() == route).findFirst()
                .orElseThrow(() -> invalid("QUALITY_REPAIR_DECISION_ROUTE_MISSING"));
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public enum RouteDisposition {
        TRIGGERED,
        EVIDENCE_REQUIRED,
        REJECTED_BY_CURRENT_EVIDENCE
    }

    public enum OverallDisposition {
        OFFLINE_EVIDENCE_REQUIRED,
        R2_SHADOW_ALLOWED,
        OFFLINE_REPAIR_QUALIFIED,
        LIVE_J1_REQUEST_ELIGIBLE,
        STOP_TO_SPEC_R3,
        STOP_TO_SPEC_R5,
        STOP_TO_SPEC_MULTIPLE,
        NO_REPAIR_ROUTE
    }

    public record RouteDecision(
            FrozenQualityEvidencePack.Route route,
            boolean triggerSatisfied,
            RouteDisposition disposition,
            List<FrozenQualityEvidencePack.PredicateEvidence> predicates
    ) {
        public RouteDecision {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(disposition, "disposition");
            predicates = new FrozenQualityEvidencePack.RouteEvidence(route, predicates).predicates();
            var allPass = predicates.stream().allMatch(item ->
                    item.result() == FrozenQualityEvidencePack.PredicateResult.PASS);
            var anyFail = predicates.stream().anyMatch(item ->
                    item.result() == FrozenQualityEvidencePack.PredicateResult.FAIL);
            var anyMissing = predicates.stream().anyMatch(item ->
                    item.result() == FrozenQualityEvidencePack.PredicateResult.MISSING);
            if (triggerSatisfied != allPass
                    || (disposition == RouteDisposition.TRIGGERED && !allPass)
                    || (disposition == RouteDisposition.REJECTED_BY_CURRENT_EVIDENCE && !anyFail)
                    || (disposition == RouteDisposition.EVIDENCE_REQUIRED && (!anyMissing || anyFail))) {
                throw invalid("QUALITY_REPAIR_ROUTE_DECISION_INCONSISTENT");
            }
        }
    }
}
