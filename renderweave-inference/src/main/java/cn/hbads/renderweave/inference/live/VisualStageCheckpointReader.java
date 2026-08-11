package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateEvidenceKind;
import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.eval.visual.VisualStageSnapshot;

import java.util.List;
import java.util.Objects;

/** Public adapter that keeps package-private provider contracts out of the evaluation API. */
public final class VisualStageCheckpointReader {
    private final LiveWorkflowJsonCodec codec = new LiveWorkflowJsonCodec();

    public VisualStageSnapshot read(String checkpointJson) {
        return read(checkpointJson, null);
    }

    /** Uses durable attempt telemetry when supplied so failed Provider calls remain measurable. */
    public VisualStageSnapshot read(String checkpointJson, Integer observedProviderAttempts) {
        var checkpoint = codec.parse(Objects.requireNonNull(checkpointJson, "checkpointJson"));
        if (observedProviderAttempts != null
                && (observedProviderAttempts < checkpoint.providerCalls() || observedProviderAttempts > 8)) {
            throw new IllegalArgumentException("Observed Provider attempt count is invalid");
        }
        var inventory = checkpoint.elementInventory();
        var hierarchy = checkpoint.hierarchyPlan();
        var bindings = checkpoint.bindingPlan();
        return new VisualStageSnapshot(
                checkpoint.completedStage(), observedProviderAttempts == null
                        ? checkpoint.providerCalls() : observedProviderAttempts,
                checkpoint.repairRounds(),
                inventory == null ? List.of() : inventory.elements().stream().map(element ->
                        new VisualStageSnapshot.ObservedElement(
                                element.elementId(),
                                VisualStageCorpus.ElementKind.valueOf(element.kind().name()),
                                element.proposedKey(), element.displayName(),
                                VisualStageCorpus.Multiplicity.valueOf(element.multiplicity().name()),
                                element.valueHint() == null ? null
                                        : VisualStageCorpus.ValueHint.valueOf(element.valueHint().name()),
                                element.evidence().stream()
                                        .filter(item -> item.kind() == CandidateEvidenceKind.IMAGE
                                                && item.boundingBox() != null)
                                        .map(item -> new VisualStageSnapshot.ObservedBox(
                                                item.boundingBox().left(), item.boundingBox().top(),
                                                item.boundingBox().right(), item.boundingBox().bottom()
                                        )).toList()
                        )).toList(),
                hierarchy == null ? null : hierarchy.rootEntityId(),
                hierarchy == null ? List.of() : hierarchy.entities().stream().map(entity ->
                        new VisualStageSnapshot.ObservedEntity(
                                entity.entityId(), entity.schemaKey(), entity.displayName(),
                                entity.supportingElementIds()
                        )).toList(),
                hierarchy == null ? List.of() : hierarchy.relationships().stream().map(edge ->
                        new VisualStageSnapshot.ObservedRelationship(
                                edge.relationshipId(), edge.parentEntityId(), edge.childEntityId(),
                                edge.fieldKey(), edge.displayName(),
                                VisualStageCorpus.Multiplicity.valueOf(edge.cardinality().name()),
                                edge.supportingElementIds()
                        )).toList(),
                bindings == null ? List.of() : bindings.bindings().stream().map(binding ->
                        new VisualStageSnapshot.ObservedBinding(binding.elementId(), binding.entityId())
                ).toList(),
                checkpoint.candidate(), checkpoint.validationProblems()
        );
    }
}
