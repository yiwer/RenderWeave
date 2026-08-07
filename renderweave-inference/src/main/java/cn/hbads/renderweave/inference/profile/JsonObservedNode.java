package cn.hbads.renderweave.inference.profile;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record JsonObservedNode(
        String pointer,
        Set<String> kinds,
        Set<String> itemKinds,
        int samplesPresent,
        int occurrences,
        List<JsonEvidenceLocation> evidence
) {
    public JsonObservedNode {
        Objects.requireNonNull(pointer, "pointer");
        kinds = Set.copyOf(Objects.requireNonNull(kinds, "kinds"));
        itemKinds = Set.copyOf(Objects.requireNonNull(itemKinds, "itemKinds"));
        if (samplesPresent < 1 || occurrences < samplesPresent) {
            throw new IllegalArgumentException("observation counts are inconsistent");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }
}
