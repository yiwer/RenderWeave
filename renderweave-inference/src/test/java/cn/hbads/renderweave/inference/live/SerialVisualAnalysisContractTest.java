package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateReference;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import cn.hbads.renderweave.inference.candidate.CandidateValidationContext;
import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialVisualAnalysisContractTest {
    private static final String IMAGE_ID = "a".repeat(64);
    private final VisualAnalysisJsonCodec codec = new VisualAnalysisJsonCodec();

    @Test
    void localMaterializerIsCanonicalAndPreservesTheStationTreeForHumanReview() throws Exception {
        var analysis = stationAnalysis();
        var runId = UUID.fromString("00000000-0000-0000-0000-000000000123");
        var materializer = new VisualPlanCandidateMaterializer();
        var candidate = materializer.materialize(
                runId, analysis.inventory(), analysis.hierarchy(), analysis.bindings(), 8_000
        );

        var reversedElements = new ArrayList<>(analysis.inventory().elements());
        java.util.Collections.reverse(reversedElements);
        var reversedEntities = new ArrayList<>(analysis.hierarchy().entities());
        java.util.Collections.reverse(reversedEntities);
        var reversedRelationships = new ArrayList<>(analysis.hierarchy().relationships());
        java.util.Collections.reverse(reversedRelationships);
        var reversedBindings = new ArrayList<>(analysis.bindings().bindings());
        java.util.Collections.reverse(reversedBindings);
        var equivalent = materializer.materialize(
                runId,
                new VisualElementInventory(VisualElementInventory.VERSION, reversedElements),
                new VisualHierarchyPlan(
                        VisualHierarchyPlan.VERSION, analysis.hierarchy().rootEntityId(),
                        reversedEntities, reversedRelationships
                ),
                new VisualElementBindingPlan(VisualElementBindingPlan.VERSION, reversedBindings),
                8_000
        );

        var json = new CandidateJsonCodec().write(candidate);
        assertEquals(json, new CandidateJsonCodec().write(equivalent));
        assertEquals("f1e6311d155d8219b431407d9f35b6551ae49c9c384918a1332107eb71c78376",
                HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))
        ));
        assertEquals("bus-stop-board", candidate.schemas().getFirst().proposedSchemaKey());
        assertEquals(
                List.of("stationName", "stationEnglishName", "warmNotice", "routes"),
                candidate.schemas().getFirst().fields().stream()
                        .map(CandidateField::proposedFieldKey).toList()
        );
        assertTrue(candidate.schemas().stream().allMatch(schema ->
                schema.source() == CandidateSource.AI
                        && schema.assessment().inferred()
                        && schema.assessment().confidenceBps() == 7_999
                        && schema.assessment().resolution() == CandidateResolution.UNRESOLVED
                        && schema.fields().stream().allMatch(field ->
                        !field.required()
                                && field.source() == CandidateSource.AI
                                && field.assessment().inferred()
                                && field.assessment().confidenceBps() == 7_999
                                && field.assessment().resolution() == CandidateResolution.UNRESOLVED
                )
        ));
        var candidateProblems = new cn.hbads.renderweave.inference.candidate.CandidateValidator()
                .validate(candidate, CandidateValidationContext.liveProviderOutput(
                        Set.of(IMAGE_ID), null, 8_000
                ));
        assertEquals(Set.of("LOW_CONFIDENCE_UNRESOLVED"), candidateProblems.stream()
                .map(problem -> problem.code()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void acceptsAStationNoticeRouteAndStopTreeAndCandidate() {
        var analysis = stationAnalysis();
        analysis.inventory().requireKnownArtifacts(Set.of(IMAGE_ID));
        analysis.hierarchy().requireConsistentWith(analysis.inventory());
        analysis.bindings().requireConsistentWith(analysis.inventory(), analysis.hierarchy());

        var candidate = stationCandidate(analysis, false);
        var problems = new VisualPlanCandidateValidator().validate(
                candidate, analysis.inventory(), analysis.hierarchy(), analysis.bindings()
        );

        assertTrue(problems.isEmpty());
        assertEquals(
                Set.of("bus-stop-board", "warm-notice", "bus-route", "bus-stop"),
                candidate.schemas().stream().map(CandidateSchema::proposedSchemaKey)
                        .collect(java.util.stream.Collectors.toSet())
        );
    }

    @Test
    void rejectsCollapsingThePlannedStopEntityIntoAnArrayOfText() {
        var analysis = stationAnalysis();
        var candidate = stationCandidate(analysis, true);

        var codes = new VisualPlanCandidateValidator().validate(
                        candidate, analysis.inventory(), analysis.hierarchy(), analysis.bindings())
                .stream().map(problem -> problem.code()).toList();

        assertTrue(codes.contains("VISUAL_PLAN_SCHEMA_MISSING"));
        assertTrue(codes.contains("VISUAL_PLAN_RELATION_SHAPE_INVALID"));
        assertFalse(codes.contains("VISUAL_PLAN_RELATION_MISSING"));
    }

    @Test
    void strictCodecsRejectUnknownDuplicateTrailingAndUnknownArtifactInputs() {
        var inventory = stationAnalysis().inventory();
        var json = codec.write(inventory);

        assertThrows(InvalidVisualAnalysisException.class, () -> codec.parseElements(
                json.replaceFirst("\\{", "{\"unexpected\":true,"), Set.of(IMAGE_ID)
        ));
        assertThrows(InvalidVisualAnalysisException.class, () -> codec.parseElements(
                json.replaceFirst("\"contractVersion\":", "\"contractVersion\":\"wrong\",\"contractVersion\":"),
                Set.of(IMAGE_ID)
        ));
        assertThrows(InvalidVisualAnalysisException.class,
                () -> codec.parseElements(json + "{}", Set.of(IMAGE_ID)));
        assertThrows(InvalidVisualAnalysisException.class,
                () -> codec.parseElements(json, Set.of("b".repeat(64))));
        assertThrows(InvalidVisualAnalysisException.class, () -> codec.parseElements(
                json.replaceFirst("\"left\":500", "\"left\":\"500\""), Set.of(IMAGE_ID)
        ));
        assertThrows(InvalidVisualAnalysisException.class, () -> codec.parseElements(
                json.replaceFirst("\"left\":500", "\"left\":500.5"), Set.of(IMAGE_ID)
        ));
        assertThrows(InvalidVisualAnalysisException.class, () -> codec.parseElements(
                json.replaceFirst("\"kind\":\"SLOT\"", "\"kind\":0"), Set.of(IMAGE_ID)
        ));
    }

    @Test
    void rejectsHierarchyCyclesAndIncompleteSlotBindings() {
        var analysis = stationAnalysis();
        assertThrows(IllegalArgumentException.class, () -> new VisualHierarchyPlan(
                VisualHierarchyPlan.VERSION, "board", analysis.hierarchy().entities(),
                List.of(
                        relation("board-notice", "board", "notice", "notice", VisualMultiplicity.ONE,
                                "notice-group"),
                        relation("notice-board", "notice", "board", "board", VisualMultiplicity.ONE,
                                "notice-group")
                )
        ));

        assertThrows(IllegalArgumentException.class, () -> new VisualElementBindingPlan(
                VisualElementBindingPlan.VERSION,
                analysis.bindings().bindings().subList(1, analysis.bindings().bindings().size())
        ).requireConsistentWith(analysis.inventory(), analysis.hierarchy()));

        var collidingHierarchy = new VisualHierarchyPlan(
                VisualHierarchyPlan.VERSION, "board", analysis.hierarchy().entities(),
                List.of(
                        relation("board-notice", "board", "notice", "stationName", VisualMultiplicity.ONE,
                                "notice-group"),
                        relation("board-routes", "board", "route", "routes", VisualMultiplicity.MANY,
                                "route-group"),
                        relation("route-stops", "route", "stop", "stops", VisualMultiplicity.MANY,
                                "stop-group")
                )
        );
        assertThrows(IllegalArgumentException.class, () -> analysis.bindings()
                .requireConsistentWith(analysis.inventory(), collidingHierarchy));
    }

    @Test
    void migratesLegacyCheckpointsInMemoryAndWritesOnlyVersionThree() {
        var legacy = """
                {
                  "checkpointVersion":"renderweave-live-checkpoint/1.0",
                  "completedStage":"CRITIQUE",
                  "structureCalls":1,
                  "repairRounds":0,
                  "outputValid":false,
                  "candidate":null,
                  "validationProblems":[]
                }
                """;
        var workflowCodec = new LiveWorkflowJsonCodec();

        var migrated = workflowCodec.parse(legacy);
        var encoded = workflowCodec.write(migrated);

        assertEquals(LiveWorkflowCheckpoint.VERSION, migrated.checkpointVersion());
        assertEquals(1, migrated.providerCalls());
        assertTrue(encoded.contains("renderweave-live-checkpoint/3.0"));
        assertTrue(encoded.contains("\"providerCalls\":1"));
        assertFalse(encoded.contains("structureCalls"));
        assertThrows(RuntimeException.class, () -> workflowCodec.parse(legacy + "{}"));
        var current = workflowCodec.write(LiveWorkflowCheckpoint.started());
        assertThrows(RuntimeException.class, () -> workflowCodec.parse(
                current.replace("\"providerCalls\":0", "\"providerCalls\":\"0\"")
        ));
        assertThrows(RuntimeException.class, () -> workflowCodec.parse(
                current.replace("\"completedStage\":\"NORMALIZE\"", "\"completedStage\":0")
        ));
        var versionTwo = """
                {
                  "checkpointVersion":"renderweave-live-checkpoint/2.0",
                  "completedStage":"OBSERVE",
                  "providerCalls":1,
                  "repairRounds":0,
                  "elementInventory":null,
                  "hierarchyPlan":null,
                  "bindingPlan":null,
                  "outputValid":false,
                  "candidate":null,
                  "validationProblems":[]
                }
                """;
        var migratedTwo = workflowCodec.parse(versionTwo);
        assertEquals(LiveWorkflowCheckpoint.VERSION, migratedTwo.checkpointVersion());
        assertEquals(1, migratedTwo.providerCalls());
        assertTrue(workflowCodec.write(migratedTwo).contains("renderweave-live-checkpoint/3.0"));
    }

    private static StationAnalysis stationAnalysis() {
        var elements = List.of(
                slot("station-name", "stationName", "站点名称", VisualValueHint.TEXT, VisualMultiplicity.ONE, 100),
                slot("station-english", "stationEnglishName", "站点英文名", VisualValueHint.TEXT, VisualMultiplicity.ONE, 400),
                group("notice-group", "warmNotice", "温馨提示", VisualMultiplicity.ONE, 900),
                slot("notice-date", "effectiveDate", "生效日期", VisualValueHint.DATE, VisualMultiplicity.ONE, 1100),
                slot("notice-content", "content", "提示内容", VisualValueHint.TEXT, VisualMultiplicity.ONE, 1400),
                group("route-group", "routes", "线路", VisualMultiplicity.MANY, 2500),
                slot("route-number", "routeNumber", "线路编号", VisualValueHint.TEXT, VisualMultiplicity.ONE, 2800),
                group("stop-group", "stops", "停靠站点", VisualMultiplicity.MANY, 3800),
                slot("stop-name", "name", "站点名称", VisualValueHint.TEXT, VisualMultiplicity.ONE, 4200)
        );
        var inventory = new VisualElementInventory(VisualElementInventory.VERSION, elements);
        var entities = List.of(
                entity("board", "bus-stop-board", "站牌", "station-name"),
                entity("notice", "warm-notice", "温馨提示", "notice-group"),
                entity("route", "bus-route", "线路", "route-group"),
                entity("stop", "bus-stop", "停靠站点", "stop-group")
        );
        var hierarchy = new VisualHierarchyPlan(
                VisualHierarchyPlan.VERSION, "board", entities,
                List.of(
                        relation("board-notice", "board", "notice", "warmNotice", VisualMultiplicity.ONE,
                                "notice-group"),
                        relation("board-routes", "board", "route", "routes", VisualMultiplicity.MANY,
                                "route-group"),
                        relation("route-stops", "route", "stop", "stops", VisualMultiplicity.MANY,
                                "stop-group")
                )
        );
        var bindings = new VisualElementBindingPlan(
                VisualElementBindingPlan.VERSION,
                List.of(
                        new VisualElementBinding("station-name", "board"),
                        new VisualElementBinding("station-english", "board"),
                        new VisualElementBinding("notice-date", "notice"),
                        new VisualElementBinding("notice-content", "notice"),
                        new VisualElementBinding("route-number", "route"),
                        new VisualElementBinding("stop-name", "stop")
                )
        );
        return new StationAnalysis(inventory, hierarchy, bindings);
    }

    private static CandidateBundle stationCandidate(StationAnalysis analysis, boolean collapseStops) {
        var ids = Map.of(
                "board", UUID.nameUUIDFromBytes("board".getBytes()),
                "notice", UUID.nameUUIDFromBytes("notice".getBytes()),
                "route", UUID.nameUUIDFromBytes("route".getBytes()),
                "stop", UUID.nameUUIDFromBytes("stop".getBytes())
        );
        var schemas = new ArrayList<CandidateSchema>();
        for (var entity : analysis.hierarchy().entities()) {
            if (collapseStops && entity.entityId().equals("stop")) continue;
            var fields = new ArrayList<CandidateField>();
            for (var binding : analysis.bindings().bindings()) {
                if (!binding.entityId().equals(entity.entityId())) continue;
                var element = analysis.inventory().requireElement(binding.elementId());
                fields.add(field(
                        entity.entityId() + ":" + element.proposedKey(), element.proposedKey(),
                        value(element), element.evidence()
                ));
            }
            for (var relationship : analysis.hierarchy().relationships()) {
                if (!relationship.parentEntityId().equals(entity.entityId())) continue;
                CandidateValue value;
                if (collapseStops && relationship.childEntityId().equals("stop")) {
                    value = CandidateValue.array(CandidateValue.scalar(CandidateValueKind.TEXT));
                } else {
                    var reference = CandidateValue.reference(CandidateReference.candidate(ids.get(
                            relationship.childEntityId()
                    )));
                    value = relationship.cardinality() == VisualMultiplicity.MANY
                            ? CandidateValue.array(reference) : reference;
                }
                var evidence = analysis.inventory().requireElement(
                        relationship.supportingElementIds().getFirst()).evidence();
                fields.add(field(
                        entity.entityId() + ":" + relationship.fieldKey(), relationship.fieldKey(), value, evidence
                ));
            }
            var evidence = analysis.inventory().requireElement(entity.supportingElementIds().getFirst()).evidence();
            schemas.add(new CandidateSchema(
                    ids.get(entity.entityId()), entity.schemaKey(), entity.displayName(), CandidateSource.AI,
                    assessment(evidence), fields
            ));
        }
        return new CandidateBundle(CandidateBundle.CONTRACT_VERSION, ids.get("board"), schemas);
    }

    private static CandidateValue value(VisualElement element) {
        var scalar = element.valueHint() == VisualValueHint.UNRESOLVED
                ? CandidateValue.unresolved("visual-type-uncertain")
                : CandidateValue.scalar(CandidateValueKind.valueOf(element.valueHint().name()));
        return element.multiplicity() == VisualMultiplicity.MANY ? CandidateValue.array(scalar) : scalar;
    }

    private static CandidateField field(
            String id,
            String key,
            CandidateValue value,
            List<CandidateEvidence> evidence
    ) {
        return new CandidateField(
                UUID.nameUUIDFromBytes(id.getBytes()), key, key, false, value,
                CandidateSource.AI, assessment(evidence)
        );
    }

    private static CandidateAssessment assessment(List<CandidateEvidence> evidence) {
        return CandidateAssessment.ai(9_000, true, CandidateResolution.NOT_REQUIRED, evidence);
    }

    private static VisualElement slot(
            String id,
            String key,
            String name,
            VisualValueHint hint,
            VisualMultiplicity multiplicity,
            int top
    ) {
        return new VisualElement(
                id, VisualElementKind.SLOT, key, name, multiplicity, hint, List.of(evidence(top))
        );
    }

    private static VisualElement group(
            String id,
            String key,
            String name,
            VisualMultiplicity multiplicity,
            int top
    ) {
        return new VisualElement(
                id, VisualElementKind.GROUP, key, name, multiplicity, null, List.of(evidence(top))
        );
    }

    private static CandidateEvidence evidence(int top) {
        return CandidateEvidence.image(IMAGE_ID, new CandidateBoundingBox(500, top, 9_500, top + 200));
    }

    private static VisualEntityPlan entity(String id, String key, String name, String support) {
        return new VisualEntityPlan(id, key, name, List.of(support));
    }

    private static VisualRelationshipPlan relation(
            String id,
            String parent,
            String child,
            String key,
            VisualMultiplicity cardinality,
            String support
    ) {
        return new VisualRelationshipPlan(
                id, parent, child, key, key, cardinality, List.of(support)
        );
    }

    private record StationAnalysis(
            VisualElementInventory inventory,
            VisualHierarchyPlan hierarchy,
            VisualElementBindingPlan bindings
    ) { }
}
