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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionDefinitionCapacityReservationTest {

    private static final String RESOURCE =
            "/cn/hbads/renderweave/template/canonical-kernel-v1/vectors.json";
    private static final Set<String> EXPRESSION_DEFINITION_LIMITS = Set.of(
            "expression.sourceUtf8BytesPerExpression",
            "expression.sourceUtf8BytesTotal",
            "expression.inputsPerExpression",
            "expression.inputsTotal",
            "expression.mappingCasesPerDefinition",
            "expression.mappingCasesTotal",
            "expression.definitionGraphEdges",
            "expression.definitionChainDepth"
    );

    @Test
    void derivesAllEightDefinitionObservationsFromRealAdmittedDocuments() throws Exception {
        var recording = new RecordingAuthority(null, null, FailureMode.NONE);
        var authority = new CanonicalDesignDslAuthority(recording);

        for (var vectorId : List.of(
                "admit-mapping-definition-ordered-cases",
                "admit-expression-definition-inputs-sorted",
                "admit-definitions-forward-reference")) {
            assertInstanceOf(
                    DesignDslAuthority.Admitted.class,
                    authority.admit(canonicalVector(vectorId)),
                    vectorId
            );
        }

        var observedLimits = new LinkedHashSet<String>();
        for (var observation : recording.observations) {
            if (observation.limitId().startsWith("expression.")) {
                observedLimits.add(observation.limitId());
            }
        }
        assertEquals(EXPRESSION_DEFINITION_LIMITS, observedLimits);
        assertTrue(recording.observations.contains(observation(
                "expression.sourceUtf8BytesPerExpression", "52")));
        assertTrue(recording.observations.contains(observation(
                "expression.sourceUtf8BytesTotal", "52")));
        assertTrue(recording.observations.contains(observation(
                "expression.inputsPerExpression", "2")));
        assertTrue(recording.observations.contains(observation(
                "expression.inputsTotal", "2")));
        assertTrue(recording.observations.contains(observation(
                "expression.mappingCasesPerDefinition", "3")));
        assertTrue(recording.observations.contains(observation(
                "expression.mappingCasesTotal", "3")));
        assertTrue(recording.observations.contains(observation(
                "expression.definitionGraphEdges", "1")));
        assertTrue(recording.observations.contains(observation(
                "expression.definitionChainDepth", "1")));
        assertFalse(recording.observations.contains(observation(
                "expression.sourceUtf8BytesTotal", "71")));
    }

    @Test
    void everyDefinitionReservationCanStopTheRealProductPath() throws Exception {
        var probes = Map.ofEntries(
                Map.entry("expression.sourceUtf8BytesPerExpression",
                        new Probe("admit-expression-definition-inputs-sorted", "52",
                                "/definitions/0/source")),
                Map.entry("expression.sourceUtf8BytesTotal",
                        new Probe("admit-expression-definition-inputs-sorted", "52",
                                "/definitions/0/source")),
                Map.entry("expression.inputsPerExpression",
                        new Probe("admit-expression-definition-inputs-sorted", "2",
                                "/definitions/0/inputs")),
                Map.entry("expression.inputsTotal",
                        new Probe("admit-expression-definition-inputs-sorted", "2",
                                "/definitions/0/inputs")),
                Map.entry("expression.mappingCasesPerDefinition",
                        new Probe("admit-mapping-definition-ordered-cases", "3",
                                "/definitions/0/cases")),
                Map.entry("expression.mappingCasesTotal",
                        new Probe("admit-mapping-definition-ordered-cases", "3",
                                "/definitions/0/cases")),
                Map.entry("expression.definitionGraphEdges",
                        new Probe("admit-mapping-definition-ordered-cases", "1",
                                "/definitions")),
                Map.entry("expression.definitionChainDepth",
                        new Probe("admit-definitions-forward-reference", "1",
                                "/definitions"))
        );

        for (var entry : probes.entrySet()) {
            var recording = new RecordingAuthority(
                    entry.getKey(), entry.getValue().observedValue(), FailureMode.REJECT);
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
            assertEquals(entry.getValue().pointer(), rejected.pointer(), entry.getKey());
            assertEquals(entry.getKey(), rejected.limit().orElseThrow().id(), entry.getKey());
        }
    }

    @Test
    void derivesLongestDefinitionChainAsEdgeDepth() {
        var recording = new RecordingAuthority(null, null, FailureMode.NONE);

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording).admit(twoEdgeChainDesign())
        );

        assertTrue(recording.observations.contains(observation(
                "expression.definitionGraphEdges", "2")));
        assertTrue(recording.observations.contains(observation(
                "expression.definitionChainDepth", "2")));
        assertTrue(recording.observations.contains(observation(
                "expression.sourceUtf8BytesTotal", "38")));
        assertTrue(recording.observations.contains(observation(
                "expression.inputsTotal", "2")));
        assertEquals(2, recording.observations.stream().filter(observation ->
                observation.equals(observation(
                        "expression.sourceUtf8BytesPerExpression", "19"))).count());
        assertEquals(2, recording.observations.stream().filter(observation ->
                observation.equals(observation(
                        "expression.inputsPerExpression", "1"))).count());
    }

    @Test
    void countsExpressionSourceAsExactUtf8Bytes() {
        var recording = new RecordingAuthority(null, null, FailureMode.NONE);

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording).admit(unicodeExpressionDesign())
        );

        assertTrue(recording.observations.contains(observation(
                "expression.sourceUtf8BytesPerExpression", "5")));
        assertTrue(recording.observations.contains(observation(
                "expression.sourceUtf8BytesTotal", "5")));
    }

    @Test
    void accumulatesMappingCasesAcrossDefinitionsWithoutSelection() {
        var recording = new RecordingAuthority(null, null, FailureMode.NONE);

        assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority(recording).admit(twoMappingDefinitionsDesign())
        );

        assertEquals(2, recording.observations.stream().filter(observation ->
                observation.equals(observation(
                        "expression.mappingCasesPerDefinition", "1"))).count());
        assertTrue(recording.observations.contains(observation(
                "expression.mappingCasesTotal", "2")));
    }

    @Test
    void graphEdgeRejectionStopsBeforeChainAdmission() throws Exception {
        var recording = new RecordingAuthority(
                "expression.definitionGraphEdges", "1", FailureMode.REJECT);

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                new CanonicalDesignDslAuthority(recording)
                        .admit(canonicalVector("admit-definitions-forward-reference"))
        );

        assertEquals("expression.definitionGraphEdges",
                rejected.limit().orElseThrow().id());
        assertFalse(recording.observations.stream().anyMatch(observation ->
                observation.limitId().equals("expression.definitionChainDepth")));
    }

    @Test
    void danglingReferenceKeepsItsStructuralFirstErrorBeforeGraphCapacity() {
        var recording = new RecordingAuthority(
                "expression.definitionGraphEdges", "1", FailureMode.REJECT);

        var rejected = assertInstanceOf(
                DesignDslAuthority.Rejected.class,
                new CanonicalDesignDslAuthority(recording).admit(danglingDefinitionDesign())
        );

        assertEquals(DesignDslAuthority.FailureCode.DESIGN_VALUE_INVALID, rejected.code());
        assertEquals("/definitions/0/inputs/0/source/definitionId", rejected.pointer());
        assertTrue(rejected.limit().isEmpty());
        assertFalse(recording.observations.stream().anyMatch(observation ->
                observation.limitId().equals("expression.definitionGraphEdges")));
    }

    @Test
    void invalidOrThrowingCapacityAuthorityFailsClosedAtTheExactLimit() throws Exception {
        for (var failureMode : List.of(FailureMode.INVALID, FailureMode.THROW)) {
            var recording = new RecordingAuthority(
                    "expression.inputsPerExpression", "2", failureMode);

            var rejected = assertInstanceOf(
                    DesignDslAuthority.Rejected.class,
                    new CanonicalDesignDslAuthority(recording)
                            .admit(canonicalVector("admit-expression-definition-inputs-sorted")),
                    failureMode.name()
            );

            assertEquals(DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                    rejected.code(), failureMode.name());
            assertEquals(DesignDslAuthority.FailureStage.DESIGN_SEMANTIC_VALIDATION,
                    rejected.stage(), failureMode.name());
            assertEquals("/definitions/0/inputs", rejected.pointer(), failureMode.name());
            assertEquals("expression.inputsPerExpression",
                    rejected.limit().orElseThrow().id(), failureMode.name());
        }
    }

    private byte[] canonicalVector(String id) throws IOException {
        try (var input = ExpressionDefinitionCapacityReservationTest.class
                .getResourceAsStream(RESOURCE)) {
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

    private static byte[] twoEdgeChainDesign() {
        return """
                {
                  "dslVersion":"renderweave-design/1.0",
                  "expressionProfile":"renderweave-expression/1.0",
                  "displayName":"Two-edge chain",
                  "definitions":[
                    {
                      "definitionId":"00000000-0000-4000-8000-0000000000f1",
                      "kind":"expression",
                      "displayName":"First",
                      "domain":"invocation",
                      "output":"text",
                      "inputs":[{"alias":"b","source":{"kind":"definition","definitionId":"00000000-0000-4000-8000-0000000000f2"}}],
                      "source":"concat(input.b, '')"
                    },
                    {
                      "definitionId":"00000000-0000-4000-8000-0000000000f2",
                      "kind":"expression",
                      "displayName":"Second",
                      "domain":"invocation",
                      "output":"text",
                      "inputs":[{"alias":"c","source":{"kind":"definition","definitionId":"00000000-0000-4000-8000-0000000000f3"}}],
                      "source":"concat(input.c, '')"
                    },
                    {
                      "definitionId":"00000000-0000-4000-8000-0000000000f3",
                      "kind":"custom",
                      "displayName":"Last",
                      "exposure":"PRIVATE",
                      "valueType":"text",
                      "defaultValue":"x"
                    }
                  ],
                  "designRoot":{
                    "nodeId":"00000000-0000-4000-8000-000000000001",
                    "kind":"canvas",
                    "widthMm":210,
                    "heightMm":297,
                    "bindings":[],
                    "children":[]
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] danglingDefinitionDesign() {
        return """
                {
                  "dslVersion":"renderweave-design/1.0",
                  "expressionProfile":"renderweave-expression/1.0",
                  "displayName":"Dangling definition",
                  "definitions":[
                    {
                      "definitionId":"00000000-0000-4000-8000-0000000000f1",
                      "kind":"expression",
                      "displayName":"Dangling",
                      "domain":"invocation",
                      "output":"text",
                      "inputs":[{"alias":"missing","source":{"kind":"definition","definitionId":"00000000-0000-4000-8000-0000000000e9"}}],
                      "source":"concat(input.missing, '')"
                    }
                  ],
                  "designRoot":{
                    "nodeId":"00000000-0000-4000-8000-000000000001",
                    "kind":"canvas",
                    "widthMm":210,
                    "heightMm":297,
                    "bindings":[],
                    "children":[]
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] unicodeExpressionDesign() {
        return designWithDefinitions("""
                {
                  "definitionId":"00000000-0000-4000-8000-0000000000f1",
                  "kind":"expression",
                  "displayName":"Unicode",
                  "domain":"invocation",
                  "output":"text",
                  "inputs":[],
                  "source":"'你'"
                }
                """);
    }

    private static byte[] twoMappingDefinitionsDesign() {
        return designWithDefinitions("""
                {
                  "definitionId":"00000000-0000-4000-8000-0000000000d1",
                  "kind":"mapping",
                  "displayName":"First mapping",
                  "domain":"invocation",
                  "output":"text",
                  "input":{"kind":"context","domain":"invocation","pointer":"/first"},
                  "cases":[{"operator":"IS_ABSENT","then":{"kind":"literal","valueType":"text","value":"missing"}}],
                  "otherwise":{"kind":"literal","valueType":"text","value":"present"}
                },
                {
                  "definitionId":"00000000-0000-4000-8000-0000000000d2",
                  "kind":"mapping",
                  "displayName":"Second mapping",
                  "domain":"invocation",
                  "output":"text",
                  "input":{"kind":"context","domain":"invocation","pointer":"/second"},
                  "cases":[{"operator":"IS_ABSENT","then":{"kind":"literal","valueType":"text","value":"missing"}}],
                  "otherwise":{"kind":"literal","valueType":"text","value":"present"}
                }
                """);
    }

    private static byte[] designWithDefinitions(String definitions) {
        return ("""
                {
                  "dslVersion":"renderweave-design/1.0",
                  "expressionProfile":"renderweave-expression/1.0",
                  "displayName":"Capacity fixture",
                  "definitions":[%s],
                  "designRoot":{
                    "nodeId":"00000000-0000-4000-8000-000000000001",
                    "kind":"canvas",
                    "widthMm":210,
                    "heightMm":297,
                    "bindings":[],
                    "children":[]
                  }
                }
                """.formatted(definitions)).getBytes(StandardCharsets.UTF_8);
    }

    private static DesignInputExpressionCapacityAuthority.Observation observation(
            String limitId,
            String observedValue
    ) {
        return new DesignInputExpressionCapacityAuthority.Observation(limitId, observedValue);
    }

    private record Probe(String vectorId, String observedValue, String pointer) {
    }

    private enum FailureMode {
        NONE,
        REJECT,
        INVALID,
        THROW
    }

    private static final class RecordingAuthority
            implements DesignInputExpressionCapacityAuthority {
        private final String failedLimitId;
        private final String failedObservedValue;
        private final FailureMode failureMode;
        private final List<Observation> observations = new ArrayList<>();

        private RecordingAuthority(
                String failedLimitId,
                String failedObservedValue,
                FailureMode failureMode
        ) {
            this.failedLimitId = failedLimitId;
            this.failedObservedValue = failedObservedValue;
            this.failureMode = failureMode;
        }

        @Override
        public Decision evaluate(Observation observation) {
            observations.add(observation);
            if (!observation.limitId().equals(failedLimitId)
                    || !observation.observedValue().equals(failedObservedValue)) {
                return new Accepted();
            }
            return switch (failureMode) {
                case NONE -> new Accepted();
                case REJECT -> new Rejected(new Terminal(
                        "DESIGN_DSL_LIMIT_EXCEEDED",
                        "EXPRESSION_PARSE_OR_ANALYZE",
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
                case INVALID -> new Invalid(InvalidReason.UNKNOWN_LIMIT);
                case THROW -> throw new IllegalStateException("capacity authority unavailable");
            };
        }
    }
}
