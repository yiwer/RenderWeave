package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateIds;
import cn.hbads.renderweave.inference.candidate.CandidateReference;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Compiles an already validated visual plan into a reviewable Candidate without another Provider call.
 * Provider-local IDs influence only plan joins; emitted opaque IDs are derived from semantic keys.
 */
final class VisualPlanCandidateMaterializer {
    static final String VERSION = "renderweave-visual-plan-candidate-materializer/1.0";
    private static final int MAX_MATERIALIZED_EVIDENCE = 8;
    private static final Comparator<CandidateEvidence> EVIDENCE_ORDER = Comparator
            .comparing(CandidateEvidence::artifactId)
            .thenComparingInt(value -> value.boundingBox().top())
            .thenComparingInt(value -> value.boundingBox().left())
            .thenComparingInt(value -> value.boundingBox().bottom())
            .thenComparingInt(value -> value.boundingBox().right());

    CandidateBundle materialize(
            UUID runId,
            VisualElementInventory inventory,
            VisualHierarchyPlan hierarchy,
            VisualElementBindingPlan bindings,
            int lowConfidenceThresholdBps
    ) {
        return materialize(
                runId, inventory, hierarchy, bindings, lowConfidenceThresholdBps,
                VisualBindingFieldPolicy.UNIQUE_FIELD_KEYS
        );
    }

    CandidateBundle materialize(
            UUID runId,
            VisualElementInventory inventory,
            VisualHierarchyPlan hierarchy,
            VisualElementBindingPlan bindings,
            int lowConfidenceThresholdBps,
            VisualBindingFieldPolicy fieldPolicy
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(fieldPolicy, "fieldPolicy");
        if (lowConfidenceThresholdBps < 0 || lowConfidenceThresholdBps > 10_000) {
            throw new IllegalArgumentException("lowConfidenceThresholdBps must be 0..10000");
        }
        hierarchy.requireConsistentWith(inventory);
        bindings.requireConsistentWith(inventory, hierarchy, fieldPolicy);

        var schemaIds = new HashMap<String, UUID>();
        for (var entity : hierarchy.entities()) {
            schemaIds.put(entity.schemaKey(), CandidateIds.schema(
                    runId, "visual-schema/" + entity.schemaKey()
            ));
        }
        var entities = canonicalEntities(hierarchy);
        var schemas = entities.stream().map(entity -> new CandidateSchema(
                schemaIds.get(entity.schemaKey()),
                entity.schemaKey(),
                entity.displayName(),
                CandidateSource.AI,
                assessment(
                        evidence(inventory, entity.supportingElementIds()),
                        lowConfidenceThresholdBps
                ),
                fields(
                        runId, entity, inventory, hierarchy, bindings,
                        schemaIds, lowConfidenceThresholdBps
                )
        )).toList();
        var root = hierarchy.requireEntity(hierarchy.rootEntityId());
        var candidate = new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION, schemaIds.get(root.schemaKey()), schemas
        );
        var planProblems = new VisualPlanCandidateValidator().validate(
                candidate, inventory, hierarchy, bindings, fieldPolicy
        );
        if (!planProblems.isEmpty()) {
            throw new IllegalStateException("Local visual materializer violated its validated plan");
        }
        return candidate;
    }

    private static List<CandidateField> fields(
            UUID runId,
            VisualEntityPlan entity,
            VisualElementInventory inventory,
            VisualHierarchyPlan hierarchy,
            VisualElementBindingPlan bindings,
            Map<String, UUID> schemaIds,
            int lowConfidenceThresholdBps
    ) {
        var fields = new ArrayList<CandidateField>();
        var boundElements = new HashMap<String, List<VisualElement>>();
        for (var binding : bindings.bindings()) {
            if (!binding.entityId().equals(entity.entityId())) continue;
            var element = inventory.requireElement(binding.elementId());
            boundElements.computeIfAbsent(element.proposedKey(), ignored -> new ArrayList<>())
                    .add(element);
        }
        for (var entry : boundElements.entrySet()) {
            var elements = entry.getValue().stream().sorted(Comparator
                    .comparing((VisualElement value) -> value.evidence().getFirst(), EVIDENCE_ORDER)
                    .thenComparing(VisualElement::elementId)).toList();
            var element = elements.getFirst();
            fields.add(new CandidateField(
                    CandidateIds.field(
                            runId, "visual-schema/" + entity.schemaKey(), element.proposedKey()
                    ),
                    element.proposedKey(),
                    element.displayName(),
                    false,
                    slotValue(element),
                    CandidateSource.AI,
                    assessment(
                            canonicalEvidence(elements.stream()
                                    .flatMap(value -> value.evidence().stream()).toList()),
                            lowConfidenceThresholdBps
                    )
            ));
        }
        for (var relationship : hierarchy.relationships()) {
            if (!relationship.parentEntityId().equals(entity.entityId())) continue;
            var child = hierarchy.requireEntity(relationship.childEntityId());
            var reference = CandidateValue.reference(CandidateReference.candidate(
                    schemaIds.get(child.schemaKey())
            ));
            fields.add(new CandidateField(
                    CandidateIds.field(
                            runId, "visual-schema/" + entity.schemaKey(), relationship.fieldKey()
                    ),
                    relationship.fieldKey(),
                    relationship.displayName(),
                    false,
                    relationship.cardinality() == VisualMultiplicity.MANY
                            ? CandidateValue.array(reference) : reference,
                    CandidateSource.AI,
                    assessment(
                            evidence(inventory, relationship.supportingElementIds()),
                            lowConfidenceThresholdBps
                    )
            ));
        }
        fields.sort(Comparator
                .comparing(VisualPlanCandidateMaterializer::firstEvidence, EVIDENCE_ORDER)
                .thenComparing(CandidateField::proposedFieldKey));
        return List.copyOf(fields);
    }

    private static CandidateEvidence firstEvidence(CandidateField field) {
        return field.assessment().evidence().getFirst();
    }

    private static CandidateValue slotValue(VisualElement element) {
        var scalar = element.valueHint() == VisualValueHint.UNRESOLVED
                ? CandidateValue.unresolved("VISUAL_TYPE_UNRESOLVED")
                : CandidateValue.scalar(CandidateValueKind.valueOf(element.valueHint().name()));
        return element.multiplicity() == VisualMultiplicity.MANY
                ? CandidateValue.array(scalar) : scalar;
    }

    private static CandidateAssessment assessment(
            List<CandidateEvidence> evidence,
            int lowConfidenceThresholdBps
    ) {
        var conservativeConfidence = Math.max(0, lowConfidenceThresholdBps - 1);
        return CandidateAssessment.ai(
                conservativeConfidence, true, CandidateResolution.UNRESOLVED, evidence
        );
    }

    private static List<CandidateEvidence> evidence(
            VisualElementInventory inventory,
            List<String> supportingElementIds
    ) {
        var values = new ArrayList<CandidateEvidence>();
        for (var elementId : supportingElementIds) {
            values.addAll(inventory.requireElement(elementId).evidence());
        }
        return canonicalEvidence(values);
    }

    private static List<CandidateEvidence> canonicalEvidence(List<CandidateEvidence> values) {
        var unique = new LinkedHashSet<>(values);
        return unique.stream().sorted(EVIDENCE_ORDER).limit(MAX_MATERIALIZED_EVIDENCE).toList();
    }

    private static List<VisualEntityPlan> canonicalEntities(VisualHierarchyPlan hierarchy) {
        var outgoing = new HashMap<String, List<VisualRelationshipPlan>>();
        for (var relationship : hierarchy.relationships()) {
            outgoing.computeIfAbsent(relationship.parentEntityId(), ignored -> new ArrayList<>())
                    .add(relationship);
        }
        outgoing.values().forEach(items -> items.sort(Comparator
                .comparing(VisualRelationshipPlan::fieldKey)
                .thenComparing(item -> hierarchy.requireEntity(item.childEntityId()).schemaKey())));

        var result = new ArrayList<VisualEntityPlan>();
        var queue = new ArrayDeque<String>();
        queue.add(hierarchy.rootEntityId());
        while (!queue.isEmpty()) {
            var entityId = queue.removeFirst();
            result.add(hierarchy.requireEntity(entityId));
            for (var relationship : outgoing.getOrDefault(entityId, List.of())) {
                queue.addLast(relationship.childEntityId());
            }
        }
        return List.copyOf(result);
    }
}
