package cn.hbads.renderweave.inference.eval;

import java.util.List;

public record LiveCertificationDecision(
        LiveCertificationStatus status,
        List<String> violations
) {
    public LiveCertificationDecision {
        violations = List.copyOf(violations);
    }
}
