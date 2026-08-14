package cn.hbads.renderweave.inference.eval.visual.quality;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R2R5TriggerDecisionEngineTest {
    private final R2R5TriggerDecisionEngine engine = new R2R5TriggerDecisionEngine();
    private final R2R5TriggerDecisionJsonCodec codec = new R2R5TriggerDecisionJsonCodec();

    @Test
    void exactFailedAuthorityProducesCanonicalInitialDecision() {
        var decision = engine.decide(FrozenQualityEvidencePack.initial());

        assertEquals(R2R5TriggerDecision.OverallDisposition.OFFLINE_EVIDENCE_REQUIRED,
                decision.overallDisposition());
        assertEquals(R2R5TriggerDecision.RouteDisposition.EVIDENCE_REQUIRED,
                decision.requireRoute(FrozenQualityEvidencePack.Route.R2).disposition());
        assertEquals(R2R5TriggerDecision.RouteDisposition.EVIDENCE_REQUIRED,
                decision.requireRoute(FrozenQualityEvidencePack.Route.R3).disposition());
        assertEquals(R2R5TriggerDecision.RouteDisposition.REJECTED_BY_CURRENT_EVIDENCE,
                decision.requireRoute(FrozenQualityEvidencePack.Route.R4).disposition());
        assertEquals(R2R5TriggerDecision.RouteDisposition.EVIDENCE_REQUIRED,
                decision.requireRoute(FrozenQualityEvidencePack.Route.R5).disposition());
        assertTrue(decision.routes().stream().noneMatch(R2R5TriggerDecision.RouteDecision::triggerSatisfied));
        assertEquals(new FrozenQualityEvidencePack.ExternalProviderUsage(0, 0, 0),
                decision.externalProviderUsage());

        var bytes = codec.write(decision);
        var identity = codec.decisionIdentity(decision);
        assertEquals(decision, codec.read(bytes, identity));
        assertArrayEquals(bytes, codec.write(engine.decide(FrozenQualityEvidencePack.initial())));
    }

    @Test
    void inputOrderingCannotChangePackIdentityOrDecisionBytes() {
        var original = FrozenQualityEvidencePack.initial();
        var reorderedRoutes = new ArrayList<>(original.routes());
        Collections.reverse(reorderedRoutes);
        var reorderedPredicates = new ArrayList<FrozenQualityEvidencePack.RouteEvidence>();
        for (var route : reorderedRoutes) {
            var predicates = new ArrayList<>(route.predicates());
            Collections.reverse(predicates);
            reorderedPredicates.add(new FrozenQualityEvidencePack.RouteEvidence(route.route(), predicates));
        }
        var reordered = copy(original, original.baseRevision(), reorderedPredicates, List.of());

        assertEquals(codec.evidencePackIdentity(original), codec.evidencePackIdentity(reordered));
        assertArrayEquals(codec.write(engine.decide(original)), codec.write(engine.decide(reordered)));
    }

    @Test
    void missingPredicateNeverBecomesTriggered() {
        var decision = engine.decide(FrozenQualityEvidencePack.initial());

        var r2 = decision.requireRoute(FrozenQualityEvidencePack.Route.R2);
        assertEquals(R2R5TriggerDecision.RouteDisposition.EVIDENCE_REQUIRED, r2.disposition());
        assertTrue(r2.predicates().stream().anyMatch(predicate ->
                predicate.result() == FrozenQualityEvidencePack.PredicateResult.MISSING));
    }

    @Test
    void callersCannotSetAnOverallDispositionThatDisagreesWithTheRoutes() {
        var derived = engine.decide(FrozenQualityEvidencePack.initial());

        assertEquals("QUALITY_REPAIR_OVERALL_DISPOSITION_INCONSISTENT",
                assertThrows(IllegalArgumentException.class, () -> new R2R5TriggerDecision(
                        derived.decisionVersion(),
                        derived.evidencePackIdentity(),
                        derived.routes(),
                        R2R5TriggerDecision.OverallDisposition.STOP_TO_SPEC_R5,
                        derived.externalProviderUsage())).getMessage());
    }

    @Test
    void authorityDriftAndHistoricalSuccessorIdentityReuseFailClosed() {
        var original = FrozenQualityEvidencePack.initial();
        assertEquals("QUALITY_REPAIR_BASE_REVISION_DRIFT", assertThrows(IllegalArgumentException.class,
                () -> copy(original, "704849e9b400abf98bca9c12951a50b1488f043b",
                        original.routes(), List.of())).getMessage());

        var reused = new FrozenQualityEvidencePack.SuccessorIdentity(
                FrozenQualityEvidencePack.SuccessorIdentityKind.EVALUATION,
                FrozenQualityEvidencePack.N7_04_EVALUATION_IDENTITY);
        assertEquals("QUALITY_REPAIR_HISTORICAL_IDENTITY_REUSED", assertThrows(IllegalArgumentException.class,
                () -> copy(original, original.baseRevision(), original.routes(), List.of(reused))).getMessage());
    }

    @Test
    void digestAndLifecycleTamperingFailClosed() {
        var source = FrozenQualityEvidencePack.initial();
        assertEquals("QUALITY_REPAIR_N7_AUTHORITY_DIGEST_DRIFT", assertThrows(IllegalArgumentException.class,
                () -> new FrozenQualityEvidencePack(
                        source.contractVersion(), source.baseRevision(), "0".repeat(64), source.n704AuditSha256(),
                        source.n704Decision(), source.n704AuthorizationStatus(), source.n705DependencyStatus(),
                        source.componentVerifications(),
                        source.routes(), List.of(), source.externalProviderUsage())).getMessage());
        assertEquals("QUALITY_REPAIR_N7_AUTHORITY_STATE_DRIFT", assertThrows(IllegalArgumentException.class,
                () -> new FrozenQualityEvidencePack(
                        source.contractVersion(), source.baseRevision(), source.n704EvidenceAuthoritySha256(),
                        source.n704AuditSha256(), FrozenQualityEvidencePack.N7Decision.PASS,
                        FrozenQualityEvidencePack.AuthorizationStatus.OPEN,
                        FrozenQualityEvidencePack.N7DependencyStatus.READY,
                        source.componentVerifications(),
                        source.routes(), List.of(), source.externalProviderUsage())).getMessage());
    }

    private static FrozenQualityEvidencePack copy(
            FrozenQualityEvidencePack source,
            String baseRevision,
            List<FrozenQualityEvidencePack.RouteEvidence> routes,
            List<FrozenQualityEvidencePack.SuccessorIdentity> successorIdentities
    ) {
        return new FrozenQualityEvidencePack(
                source.contractVersion(),
                baseRevision,
                source.n704EvidenceAuthoritySha256(),
                source.n704AuditSha256(),
                source.n704Decision(),
                source.n704AuthorizationStatus(),
                source.n705DependencyStatus(),
                source.componentVerifications(),
                routes,
                successorIdentities,
                source.externalProviderUsage());
    }
}
