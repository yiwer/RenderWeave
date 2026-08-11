package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VisualSemanticVerifierTest {
    private static final String IMAGE_ID = "b".repeat(64);
    private final VisualSemanticVerifier verifier = new VisualSemanticVerifier();

    @Test
    void leafEvidencePolicyAcceptsAllSixtyStageGoldCases() {
        for (var evaluationCase : new VisualStageCorpus().cases()) {
            assertEquals(
                    List.of(),
                    verifier.verifyElementEvidenceTopology(
                            inventory(evaluationCase.scene()),
                            VisualObservationSemanticPolicy.SLOT_LEAF_EVIDENCE_REQUIRED
                    ),
                    evaluationCase.caseId()
            );
        }
    }

    @Test
    void minimalEntityRegionPolicyRejectsRootLeakageAndRedundantAncestors() {
        var grounding = grounding();
        var hierarchy = hierarchy();
        var invalid = new VisualEntityRegionPlan(
                VisualEntityRegionPlan.VERSION,
                List.of(
                        new VisualEntityRegionOwnership("root", List.of("root-region")),
                        new VisualEntityRegionOwnership(
                                "child-a", List.of("root-region", "group-region")
                        ),
                        new VisualEntityRegionOwnership("child-b", List.of("inner-region"))
                ),
                List.of()
        );

        assertEquals(
                List.of(
                        VisualSemanticIssue.HIERARCHY_ENTITY_REGION_REDUNDANT,
                        VisualSemanticIssue.HIERARCHY_NON_ROOT_OWNS_ROOT_REGION
                ),
                verifier.verifyEntityRegionTopology(
                        grounding, hierarchy, invalid,
                        VisualHierarchySemanticPolicy.MINIMAL_ENTITY_REGION_OWNERSHIP
                )
        );
        assertEquals(
                List.of(),
                verifier.verifyEntityRegionTopology(
                        grounding, hierarchy, invalid, VisualHierarchySemanticPolicy.LEGACY
                )
        );
        var valid = new VisualEntityRegionPlan(
                VisualEntityRegionPlan.VERSION,
                List.of(
                        new VisualEntityRegionOwnership("root", List.of("root-region")),
                        new VisualEntityRegionOwnership("child-a", List.of("group-region")),
                        new VisualEntityRegionOwnership("child-b", List.of("inner-region"))
                ),
                List.of()
        );
        assertEquals(
                List.of(),
                verifier.verifyEntityRegionTopology(
                        grounding, hierarchy, valid,
                        VisualHierarchySemanticPolicy.MINIMAL_ENTITY_REGION_OWNERSHIP
                )
        );
    }

    @Test
    void uniqueMinimalBindingPolicyRejectsTiesWithoutChoosingForTheModel() {
        var grounding = grounding();
        var inventory = new VisualElementInventory(
                VisualElementInventory.VERSION,
                List.of(new VisualElement(
                        "slot", VisualElementKind.SLOT, "value", "Value",
                        VisualMultiplicity.ONE, VisualValueHint.TEXT,
                        List.of(evidence(3_000, 3_000, 4_000, 4_000))
                ))
        );
        var hierarchy = hierarchy();
        var ambiguousRegions = new VisualEntityRegionPlan(
                VisualEntityRegionPlan.VERSION,
                List.of(
                        new VisualEntityRegionOwnership("root", List.of("root-region")),
                        new VisualEntityRegionOwnership("child-a", List.of("group-region")),
                        new VisualEntityRegionOwnership("child-b", List.of("group-region"))
                ),
                List.of()
        );
        var binding = new VisualElementBindingPlan(
                VisualElementBindingPlan.VERSION_V2,
                List.of(new VisualElementBinding("slot", "child-a"))
        );

        assertEquals(
                List.of(VisualSemanticIssue.HIERARCHY_BINDING_OWNER_AMBIGUOUS),
                verifier.verifyBindings(
                        inventory, grounding, hierarchy, ambiguousRegions, binding,
                        VisualBindingSemanticPolicy.UNIQUE_MINIMAL_ENTITY_OWNER
                )
        );
        assertEquals(
                InferenceStage.HIERARCHY,
                VisualSemanticIssue.HIERARCHY_BINDING_OWNER_AMBIGUOUS.earliestStage()
        );
        assertEquals(
                List.of(),
                verifier.verifyBindings(
                        inventory, grounding, hierarchy, ambiguousRegions, binding,
                        VisualBindingSemanticPolicy.NEAREST_ENTITY
                )
        );

        var uniqueRegions = new VisualEntityRegionPlan(
                VisualEntityRegionPlan.VERSION,
                List.of(
                        new VisualEntityRegionOwnership("root", List.of("root-region")),
                        new VisualEntityRegionOwnership("child-a", List.of("group-region")),
                        new VisualEntityRegionOwnership("child-b", List.of("inner-region"))
                ),
                List.of()
        );
        var tooBroad = new VisualElementBindingPlan(
                VisualElementBindingPlan.VERSION_V2,
                List.of(new VisualElementBinding("slot", "root"))
        );
        var unique = new VisualElementBindingPlan(
                VisualElementBindingPlan.VERSION_V2,
                List.of(new VisualElementBinding("slot", "child-b"))
        );
        assertEquals(
                List.of(VisualSemanticIssue.BINDING_NOT_NEAREST_ENTITY),
                verifier.verifyBindings(
                        inventory, grounding, hierarchy, uniqueRegions, tooBroad,
                        VisualBindingSemanticPolicy.UNIQUE_MINIMAL_ENTITY_OWNER
                )
        );
        assertEquals(
                List.of(),
                verifier.verifyBindings(
                        inventory, grounding, hierarchy, uniqueRegions, unique,
                        VisualBindingSemanticPolicy.UNIQUE_MINIMAL_ENTITY_OWNER
                )
        );
    }

    private static VisualGroundingPlan grounding() {
        return new VisualGroundingPlan(
                VisualGroundingPlan.VERSION,
                List.of(
                        new VisualRegion(
                                "root-region", null, VisualRegionKind.ROOT,
                                VisualMultiplicity.ONE, 0, null,
                                List.of(evidence(0, 0, 10_000, 10_000))
                        ),
                        new VisualRegion(
                                "group-region", "root-region", VisualRegionKind.GROUP,
                                VisualMultiplicity.ONE, 0, null,
                                List.of(evidence(1_000, 1_000, 9_000, 9_000))
                        ),
                        new VisualRegion(
                                "inner-region", "group-region", VisualRegionKind.GROUP,
                                VisualMultiplicity.ONE, 0, null,
                                List.of(evidence(2_000, 2_000, 8_000, 8_000))
                        )
                ),
                List.of(new VisualElementRegionOwnership("slot", List.of("inner-region")))
        );
    }

    private static VisualHierarchyPlan hierarchy() {
        return new VisualHierarchyPlan(
                VisualHierarchyPlan.VERSION_V2,
                "root",
                List.of(
                        new VisualEntityPlan("root", "root", "Root", List.of("group-a")),
                        new VisualEntityPlan("child-a", "child-a", "Child A", List.of("group-a")),
                        new VisualEntityPlan("child-b", "child-b", "Child B", List.of("group-b"))
                ),
                List.of(
                        new VisualRelationshipPlan(
                                "edge-a", "root", "child-a", "childrena", "Children A",
                                VisualMultiplicity.ONE, List.of("group-a")
                        ),
                        new VisualRelationshipPlan(
                                "edge-b", "root", "child-b", "childrenb", "Children B",
                                VisualMultiplicity.ONE, List.of("group-b")
                        )
                )
        );
    }

    private static CandidateEvidence evidence(int left, int top, int right, int bottom) {
        return CandidateEvidence.image(
                IMAGE_ID, new CandidateBoundingBox(left, top, right, bottom)
        );
    }

    private static VisualElementInventory inventory(VisualStageCorpus.Scene scene) {
        return new VisualElementInventory(
                VisualElementInventory.VERSION,
                scene.elements().stream().map(element -> new VisualElement(
                        element.elementId(),
                        VisualElementKind.valueOf(element.kind().name()),
                        element.proposedKey(),
                        element.displayName(),
                        VisualMultiplicity.valueOf(element.multiplicity().name()),
                        element.valueHint() == null
                                ? null : VisualValueHint.valueOf(element.valueHint().name()),
                        List.of(CandidateEvidence.image(IMAGE_ID, new CandidateBoundingBox(
                                element.box().left(), element.box().top(),
                                element.box().right(), element.box().bottom()
                        )))
                )).toList()
        );
    }
}
