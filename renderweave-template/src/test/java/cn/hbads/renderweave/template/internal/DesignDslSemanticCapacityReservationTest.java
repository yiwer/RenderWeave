package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignDslSemanticCapacityReservationTest {

    private static final String RESOURCE =
            "/cn/hbads/renderweave/template/canonical-kernel-v1/vectors.json";
    private static final Set<String> SEMANTIC_LIMITS = Set.of(
            "designDslSemantics.authoredNodes",
            "designDslSemantics.authoredTreeDepth",
            "designDslSemantics.childrenPerContainer",
            "designDslSemantics.definitions",
            "designDslSemantics.bindingsTotal",
            "designDslSemantics.bindingsPerNode",
            "designDslSemantics.runsPerTextNode",
            "designDslSemantics.runsTotal",
            "designDslSemantics.gridTracksPerAxis",
            "designDslSemantics.vectorEntriesPerNode",
            "designDslSemantics.vectorEntriesTotal",
            "designDslSemantics.fillsPerTemplateUse",
            "designDslSemantics.literalListItemsPerList",
            "designDslSemantics.literalListItemsTotal",
            "designDslSemantics.authoredRunTextScalars"
    );

    @Test
    void derivesAllFifteenSemanticObservationsFromRealAdmittedDocuments() throws Exception {
        var recording = new RecordingAuthority(null, null);
        var authority = new CanonicalDesignDslAuthority(recording);

        for (var vectorId : List.of(
                "admit-baseline-member-order",
                "admit-grid-with-tracks",
                "admit-text-with-runs",
                "admit-path",
                "admit-template-use-fills-sorted",
                "admit-custom-defaults-all-base-types",
                "admit-bindings-sorted")) {
            assertInstanceOf(
                    DesignDslAuthority.Admitted.class,
                    authority.admit(canonicalVector(vectorId)),
                    vectorId
            );
        }

        var observedLimits = new LinkedHashSet<String>();
        for (var observation : recording.observations) {
            if (observation.limitId().startsWith("designDslSemantics.")) {
                observedLimits.add(observation.limitId());
            }
        }
        assertEquals(SEMANTIC_LIMITS, observedLimits);
        assertTrue(recording.observations.contains(observation(
                "designDslSemantics.authoredNodes", "1")));
        assertTrue(recording.observations.contains(observation(
                "designDslSemantics.authoredTreeDepth", "1")));
        assertTrue(recording.observations.contains(observation(
                "designDslSemantics.childrenPerContainer", "0")));
        assertTrue(recording.observations.contains(observation(
                "designDslSemantics.definitions", "0")));
        assertTrue(recording.observations.contains(observation(
                "designDslSemantics.gridTracksPerAxis", "2")));
        assertTrue(recording.observations.contains(observation(
                "designDslSemantics.runsPerTextNode", "2")));
        assertTrue(recording.observations.contains(observation(
                "designDslSemantics.authoredRunTextScalars", "10")));
        assertTrue(recording.observations.contains(observation(
                "designDslSemantics.vectorEntriesPerNode", "5")));
        assertTrue(recording.observations.contains(observation(
                "designDslSemantics.fillsPerTemplateUse", "2")));
        assertTrue(recording.observations.contains(observation(
                "designDslSemantics.literalListItemsPerList", "2")));
        assertTrue(recording.observations.contains(observation(
                "designDslSemantics.bindingsPerNode", "2")));
    }

    @Test
    void mapsSharedSemanticRejectionToTheExactClosedPublicLimit() throws Exception {
        var recording = new RecordingAuthority("designDslSemantics.definitions", "0");

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(canonicalVector("admit-baseline-member-order"))
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED, rejected.code());
        assertEquals(DesignDslAuthority.FailureStage.DESIGN_SEMANTIC_VALIDATION, rejected.stage());
        assertEquals("/definitions", rejected.pointer());
        assertEquals("designDslSemantics.definitions", rejected.limit().orElseThrow().id());
    }

    @Test
    void everySemanticReservationCanStopTheRealProductPath() throws Exception {
        var probes = Map.ofEntries(
                Map.entry("designDslSemantics.authoredNodes",
                        new Probe("admit-baseline-member-order", "1")),
                Map.entry("designDslSemantics.authoredTreeDepth",
                        new Probe("admit-baseline-member-order", "1")),
                Map.entry("designDslSemantics.childrenPerContainer",
                        new Probe("admit-baseline-member-order", "0")),
                Map.entry("designDslSemantics.definitions",
                        new Probe("admit-baseline-member-order", "0")),
                Map.entry("designDslSemantics.bindingsTotal",
                        new Probe("admit-baseline-member-order", "0")),
                Map.entry("designDslSemantics.bindingsPerNode",
                        new Probe("admit-baseline-member-order", "0")),
                Map.entry("designDslSemantics.runsPerTextNode",
                        new Probe("admit-text-with-runs", "2")),
                Map.entry("designDslSemantics.runsTotal",
                        new Probe("admit-text-with-runs", "2")),
                Map.entry("designDslSemantics.gridTracksPerAxis",
                        new Probe("admit-grid-with-tracks", "2")),
                Map.entry("designDslSemantics.vectorEntriesPerNode",
                        new Probe("admit-path", "5")),
                Map.entry("designDslSemantics.vectorEntriesTotal",
                        new Probe("admit-path", "5")),
                Map.entry("designDslSemantics.fillsPerTemplateUse",
                        new Probe("admit-template-use-fills-sorted", "2")),
                Map.entry("designDslSemantics.literalListItemsPerList",
                        new Probe("admit-custom-defaults-all-base-types", "2")),
                Map.entry("designDslSemantics.literalListItemsTotal",
                        new Probe("admit-custom-defaults-all-base-types", "2")),
                Map.entry("designDslSemantics.authoredRunTextScalars",
                        new Probe("admit-text-with-runs", "10"))
        );

        for (var entry : probes.entrySet()) {
            var recording = new RecordingAuthority(entry.getKey(), entry.getValue().observedValue());
            var rejected = assertInstanceOf(
                    DesignDslAuthority.Rejected.class,
                    new CanonicalDesignDslAuthority(recording)
                            .admit(canonicalVector(entry.getValue().vectorId())),
                    entry.getKey()
            );
            assertEquals(DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                    rejected.code(), entry.getKey());
            assertEquals(DesignDslAuthority.FailureStage.DESIGN_SEMANTIC_VALIDATION,
                    rejected.stage(), entry.getKey());
            assertEquals(entry.getKey(), rejected.limit().orElseThrow().id(), entry.getKey());
        }
    }

    private byte[] canonicalVector(String id) throws IOException {
        try (var input = DesignDslSemanticCapacityReservationTest.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing vector resource " + RESOURCE);
            }
            var manifest = new ObjectMapper().readTree(input);
            for (var vector : manifest.required("cases")) {
                if (id.equals(vector.required("id").asString())) {
                    return vector.required("expected").required("canonicalUtf8")
                            .asString().getBytes(StandardCharsets.UTF_8);
                }
            }
            throw new IOException("Missing canonical vector " + id);
        }
    }

    private static DesignInputExpressionCapacityAuthority.Observation observation(
            String limitId,
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(limitId, observedValue);
    }

    private record Probe(String vectorId, String observedValue) {
    }

    private static final class RecordingAuthority
            implements DesignInputExpressionCapacityAuthority {
        private final String rejectedLimitId;
        private final String rejectedObservedValue;
        private final List<Observation> observations = new ArrayList<>();

        private RecordingAuthority(String rejectedLimitId, String rejectedObservedValue) {
            this.rejectedLimitId = rejectedLimitId;
            this.rejectedObservedValue = rejectedObservedValue;
        }

        @Override
        public Decision evaluate(Observation observation) {
            observations.add(observation);
            if (observation.limitId().equals(rejectedLimitId)
                    && observation.observedValue().equals(rejectedObservedValue)) {
                return new Rejected(new Terminal(
                        "DESIGN_DSL_LIMIT_EXCEEDED",
                        "DESIGN_SEMANTIC_VALIDATION",
                        "TEMPLATE_CLOSURE",
                        "ZERO_WRITE_AND_DOWNSTREAM",
                        List.of(
                                "templateWrites=0",
                                "assetWrites=0",
                                "evaluationStarts=0",
                                "renderDocuments=0",
                                "renderOutputs=0"
                        )
                ));
            }
            return new Accepted();
        }
    }
}
