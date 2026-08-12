package cn.hbads.renderweave.inference.eval.visual;

import java.util.List;
import java.util.Map;

/** Deterministic, zero-provider replay input for the repository-owned synthetic corpus. */
public final class LayeredSyntheticReplay {
    public static final String VERSION = "renderweave-layered-synthetic-replay/1.0";

    private LayeredSyntheticReplay() { }

    public static LayeredVisualPrediction perfect(LayeredVisualCorpus.Case evaluationCase) {
        var gold = evaluationCase.annotation();
        var candidateGold = gold.candidate();
        var candidate = new LayeredVisualPrediction.Candidate(
                candidateGold.rootEntityId(), candidateGold.fields(), candidateGold.relationshipIds(),
                true, true, true, 0, gold.abstention().expectedUnresolvedOwnerIds().size(),
                "REVIEW_REQUIRED");
        return new LayeredVisualPrediction(
                evaluationCase.caseId(),
                gold.ocrLines().stream().map(item ->
                        new LayeredVisualPrediction.OcrLine(item.lineId(), item.text())).toList(),
                gold.regions().stream().map(item -> new LayeredVisualPrediction.Region(
                        item.regionId(), item.kind(), item.geometry(), 9_000)).toList(),
                gold.evidence().stream().map(item -> new LayeredVisualPrediction.Evidence(
                        item.ownerKind(), item.ownerId(), item.geometry())).toList(),
                gold.precedenceEdges(), gold.repeatGroups(), gold.entities(), gold.relationships(), gold.bindings(),
                candidate,
                gold.regions().stream().map(item ->
                        new LayeredVisualPrediction.Confidence(item.regionId(), 9_000)).toList(),
                new LayeredVisualPrediction.Runtime(
                        3, 0, 0, 0, 0,
                        Map.of(
                                LayeredVisualPrediction.Stage.ACQUISITION, 1_000L,
                                LayeredVisualPrediction.Stage.HIERARCHY, 2_000L,
                                LayeredVisualPrediction.Stage.ELEMENT_BINDING, 3_000L,
                                LayeredVisualPrediction.Stage.CANDIDATE, 4_000L),
                        LayeredVisualPrediction.RecoveryCode.NONE, 0, 0, 0, 0, 0));
    }
}
