package cn.hbads.renderweave.inference.replay;

import cn.hbads.renderweave.inference.candidate.CandidateValueKind;

import java.util.List;

public record ReplayVisualField(
        String fieldKey,
        String displayName,
        CandidateValueKind type,
        boolean array,
        String targetSchemaKey,
        int confidenceBps,
        boolean required,
        int imageOrdinal,
        List<Integer> boundingBox
) {
    public ReplayVisualField {
        boundingBox = boundingBox == null ? List.of() : List.copyOf(boundingBox);
    }
}
