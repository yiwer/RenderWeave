package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateReference;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualStageEvaluatorTest {
    private static final String ARTIFACT_ID = "a".repeat(64);
    private final VisualStageCorpus corpus = new VisualStageCorpus();
    private final VisualStageEvaluator evaluator = new VisualStageEvaluator();

    @Test
    void perfectThreeLevelTransitSnapshotScoresEveryStage() {
        var gold = corpus.require("transit-board-v1");
        var result = evaluator.evaluate(gold, perfectSnapshot(gold));

        assertEquals(10_000, result.elementF1Bps());
        assertEquals(10_000, result.grounding().recallAtIou50Bps());
        assertEquals(10_000, result.grounding().meanMatchedIouBps());
        assertEquals(10_000, result.entities().f1Bps());
        assertEquals(10_000, result.relationships().f1Bps());
        assertEquals(10_000, result.bindings().f1Bps());
        assertEquals(10_000, result.survival().candidateSurvivalBps());
        assertEquals(0, result.treeEditDistance());
        assertEquals(10_000, result.normalizedTreeSimilarityBps());
        assertEquals(1_000, result.expectedCalibrationErrorBps());
        assertEquals(100, result.brierScoreBps());
        assertEquals(10_000, result.finalCandidate().entities().f1Bps());
        assertEquals(10_000, result.finalCandidate().fields().f1Bps());
        assertEquals(10_000, result.finalCandidate().relationships().f1Bps());
        assertTrue(result.finalCandidate().passed());
    }

    @Test
    void missingCheckpointPreservesAllGoldDenominators() {
        var gold = corpus.require("hospital-schedule-v5");
        var result = evaluator.evaluate(gold, VisualStageSnapshot.empty(InferenceStage.NORMALIZE));

        assertEquals(0, result.elementRecallBps());
        assertEquals(0, result.grounding().recallAtIou50Bps());
        assertEquals(0, result.entities().recallBps());
        assertEquals(0, result.relationships().recallBps());
        assertEquals(0, result.bindings().recallBps());
        assertEquals(0, result.survival().candidateSurvivalBps());
        assertEquals("VISUAL_STAGE_CANDIDATE_MISSING", result.outcomeCode());
        assertFalse(result.finalCandidate().passed());
        assertTrue(result.treeEditDistance() > 0);
    }

    @Test
    void wrongGroundingAndBindingCannotBeHiddenByExactFinalCandidate() {
        var gold = corpus.require("transit-board-v3");
        var perfect = perfectSnapshot(gold);
        var elements = perfect.elements().stream().map(item -> item.elementId().equals("stop-name")
                ? new VisualStageSnapshot.ObservedElement(
                        item.elementId(), item.kind(), item.proposedKey(), item.displayName(),
                        item.multiplicity(), item.valueHint(),
                        List.of(new VisualStageSnapshot.ObservedBox(0, 0, 500, 500))
                ) : item).toList();
        var bindings = perfect.bindings().stream().map(item -> item.elementId().equals("stop-name")
                ? new VisualStageSnapshot.ObservedBinding(item.elementId(), "board") : item).toList();
        var actual = new VisualStageSnapshot(
                perfect.completedStage(), perfect.providerCalls(), perfect.repairRounds(), elements,
                perfect.rootEntityId(), perfect.entities(), perfect.relationships(), bindings,
                perfect.candidate(), perfect.candidateProblems()
        );

        var result = evaluator.evaluate(gold, actual);
        assertTrue(result.grounding().recallAtIou50Bps() < 10_000);
        assertTrue(result.bindings().recallBps() < 10_000);
        assertTrue(result.survival().bindingSurvivalBps() < 10_000);
        assertEquals(10_000, result.finalCandidate().fields().f1Bps());
    }

    static VisualStageSnapshot perfectSnapshot(VisualStageCorpus.EvaluationCase value) {
        var scene = value.scene();
        var elements = scene.elements().stream().map(item -> new VisualStageSnapshot.ObservedElement(
                item.elementId(), item.kind(), item.proposedKey(), item.displayName(), item.multiplicity(),
                item.valueHint(), List.of(new VisualStageSnapshot.ObservedBox(
                        item.box().left(), item.box().top(), item.box().right(), item.box().bottom()
                ))
        )).toList();
        var entities = scene.entities().stream().map(item -> new VisualStageSnapshot.ObservedEntity(
                item.entityId(), item.schemaKey(), item.displayName(), item.supportingElementIds()
        )).toList();
        var relationships = scene.relationships().stream().map(item ->
                new VisualStageSnapshot.ObservedRelationship(
                        item.relationshipId(), item.parentEntityId(), item.childEntityId(), item.fieldKey(),
                        item.displayName(), item.cardinality(), item.supportingElementIds()
                )).toList();
        var bindings = scene.bindings().stream().map(item ->
                new VisualStageSnapshot.ObservedBinding(item.elementId(), item.entityId())).toList();
        return new VisualStageSnapshot(
                InferenceStage.DETERMINISTIC_VALIDATE, 4, 0, elements, scene.rootEntityId(), entities,
                relationships, bindings, candidate(scene), List.of()
        );
    }

    private static CandidateBundle candidate(VisualStageCorpus.Scene scene) {
        var schemaIds = new HashMap<String, UUID>();
        scene.entities().forEach(entity -> schemaIds.put(entity.entityId(), uuid("schema:" + entity.entityId())));
        var elements = scene.elements().stream().collect(java.util.stream.Collectors.toMap(
                VisualStageCorpus.Element::elementId, item -> item
        ));
        var bindingsByEntity = new HashMap<String, List<VisualStageCorpus.Binding>>();
        scene.bindings().forEach(binding -> bindingsByEntity
                .computeIfAbsent(binding.entityId(), ignored -> new ArrayList<>()).add(binding));
        var relationshipsByParent = new HashMap<String, List<VisualStageCorpus.Relationship>>();
        scene.relationships().forEach(edge -> relationshipsByParent
                .computeIfAbsent(edge.parentEntityId(), ignored -> new ArrayList<>()).add(edge));
        var schemas = new ArrayList<CandidateSchema>();
        for (var entity : scene.entities()) {
            var fields = new ArrayList<CandidateField>();
            for (var binding : bindingsByEntity.getOrDefault(entity.entityId(), List.of())) {
                var element = elements.get(binding.elementId());
                fields.add(new CandidateField(
                        uuid("field:" + entity.entityId() + ":" + element.proposedKey()),
                        element.proposedKey(), element.displayName(), false, scalarValue(element),
                        CandidateSource.AI, assessment(element, 9_000)
                ));
            }
            for (var edge : relationshipsByParent.getOrDefault(entity.entityId(), List.of())) {
                var group = elements.get(edge.supportingElementIds().getFirst());
                var reference = CandidateValue.reference(CandidateReference.candidate(
                        schemaIds.get(edge.childEntityId())
                ));
                var value = edge.cardinality() == VisualStageCorpus.Multiplicity.MANY
                        ? CandidateValue.array(reference) : reference;
                fields.add(new CandidateField(
                        uuid("field:" + entity.entityId() + ":" + edge.fieldKey()),
                        edge.fieldKey(), edge.displayName(), false, value,
                        CandidateSource.AI, assessment(group, 9_000)
                ));
            }
            var support = elements.get(entity.supportingElementIds().getFirst());
            schemas.add(new CandidateSchema(
                    schemaIds.get(entity.entityId()), entity.schemaKey(), entity.displayName(),
                    CandidateSource.AI, assessment(support, 9_000), fields
            ));
        }
        return new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaIds.get(scene.rootEntityId()), schemas
        );
    }

    private static CandidateValue scalarValue(VisualStageCorpus.Element element) {
        var scalar = switch (element.valueHint()) {
            case TEXT -> CandidateValue.scalar(CandidateValueKind.TEXT);
            case DECIMAL -> CandidateValue.scalar(CandidateValueKind.DECIMAL);
            case DATE -> CandidateValue.scalar(CandidateValueKind.DATE);
            case TIME -> CandidateValue.scalar(CandidateValueKind.TIME);
            case BOOLEAN -> CandidateValue.scalar(CandidateValueKind.BOOLEAN);
            case UNRESOLVED -> CandidateValue.unresolved("VISUAL_UNKNOWN");
        };
        return element.multiplicity() == VisualStageCorpus.Multiplicity.MANY
                ? CandidateValue.array(scalar) : scalar;
    }

    private static CandidateAssessment assessment(VisualStageCorpus.Element element, int confidence) {
        var box = element.box();
        return CandidateAssessment.ai(
                confidence, true, CandidateResolution.NOT_REQUIRED,
                List.of(CandidateEvidence.image(ARTIFACT_ID, new CandidateBoundingBox(
                        box.left(), box.top(), box.right(), box.bottom()
                )))
        );
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
