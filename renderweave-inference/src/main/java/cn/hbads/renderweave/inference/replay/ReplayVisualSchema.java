package cn.hbads.renderweave.inference.replay;

import java.util.List;

public record ReplayVisualSchema(
        String schemaKey,
        String displayName,
        int confidenceBps,
        int imageOrdinal,
        List<Integer> boundingBox,
        List<ReplayVisualField> fields
) {
    public ReplayVisualSchema {
        boundingBox = boundingBox == null ? List.of() : List.copyOf(boundingBox);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
