package cn.hbads.renderweave.inference.profile;

import java.util.List;
import java.util.Objects;

public record JsonStructuralProfile(int sampleCount, List<JsonObservedNode> nodes) {
    public JsonStructuralProfile {
        if (sampleCount < 1 || sampleCount > 20) {
            throw new IllegalArgumentException("sampleCount must be 1..20");
        }
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
    }
}
