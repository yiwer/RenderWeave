package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
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
