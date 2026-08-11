package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.run.InferenceStage;

import java.util.List;
import java.util.Objects;

/** In-memory, typed view of serial visual checkpoints used by the stage evaluator. */
public record VisualStageSnapshot(
        InferenceStage completedStage,
        int providerCalls,
        int repairRounds,
        List<ObservedElement> elements,
        String rootEntityId,
        List<ObservedEntity> entities,
        List<ObservedRelationship> relationships,
        List<ObservedBinding> bindings,
        CandidateBundle candidate,
        List<CandidateProblem> candidateProblems
) {
    public VisualStageSnapshot {
        Objects.requireNonNull(completedStage, "completedStage");
        if (providerCalls < 0 || providerCalls > 8 || repairRounds < 0 || repairRounds > 2) {
            throw new IllegalArgumentException("Visual stage snapshot call counts are invalid");
        }
        elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        relationships = List.copyOf(Objects.requireNonNull(relationships, "relationships"));
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        candidateProblems = List.copyOf(Objects.requireNonNull(candidateProblems, "candidateProblems"));
        if (rootEntityId == null && (!entities.isEmpty() || !relationships.isEmpty() || !bindings.isEmpty())) {
            throw new IllegalArgumentException("Visual hierarchy snapshot requires a root");
        }
    }

    public static VisualStageSnapshot empty(InferenceStage stage) {
        return new VisualStageSnapshot(stage, 0, 0, List.of(), null, List.of(), List.of(), List.of(),
                null, List.of());
    }

    public record ObservedBox(int left, int top, int right, int bottom) {
        public ObservedBox {
            if (left < 0 || top < 0 || right > 10_000 || bottom > 10_000
                    || left >= right || top >= bottom) {
                throw new IllegalArgumentException("Observed box is invalid");
            }
        }
    }

    public record ObservedElement(
            String elementId,
            VisualStageCorpus.ElementKind kind,
            String proposedKey,
            String displayName,
            VisualStageCorpus.Multiplicity multiplicity,
            VisualStageCorpus.ValueHint valueHint,
            List<ObservedBox> evidenceBoxes
    ) {
        public ObservedElement {
            Objects.requireNonNull(elementId, "elementId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(proposedKey, "proposedKey");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(multiplicity, "multiplicity");
            evidenceBoxes = List.copyOf(Objects.requireNonNull(evidenceBoxes, "evidenceBoxes"));
            if (evidenceBoxes.isEmpty()) throw new IllegalArgumentException("Observed element evidence is required");
        }
    }

    public record ObservedEntity(
            String entityId,
            String schemaKey,
            String displayName,
            List<String> supportingElementIds
    ) {
        public ObservedEntity {
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(schemaKey, "schemaKey");
            Objects.requireNonNull(displayName, "displayName");
            supportingElementIds = List.copyOf(Objects.requireNonNull(supportingElementIds,
                    "supportingElementIds"));
        }
    }

    public record ObservedRelationship(
            String relationshipId,
            String parentEntityId,
            String childEntityId,
            String fieldKey,
            String displayName,
            VisualStageCorpus.Multiplicity cardinality,
            List<String> supportingElementIds
    ) {
        public ObservedRelationship {
            Objects.requireNonNull(relationshipId, "relationshipId");
            Objects.requireNonNull(parentEntityId, "parentEntityId");
            Objects.requireNonNull(childEntityId, "childEntityId");
            Objects.requireNonNull(fieldKey, "fieldKey");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(cardinality, "cardinality");
            supportingElementIds = List.copyOf(Objects.requireNonNull(supportingElementIds,
                    "supportingElementIds"));
        }
    }

    public record ObservedBinding(String elementId, String entityId) {
        public ObservedBinding {
            Objects.requireNonNull(elementId, "elementId");
            Objects.requireNonNull(entityId, "entityId");
        }
    }
}
