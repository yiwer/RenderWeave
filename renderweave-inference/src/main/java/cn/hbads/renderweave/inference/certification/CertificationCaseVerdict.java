package cn.hbads.renderweave.inference.certification;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record CertificationCaseVerdict(
        String caseId,
        CertificationTerminalState terminalState,
        boolean manuallyAccepted,
        int confidenceBps,
        List<String> proposedKeys
) {
    public CertificationCaseVerdict {
        CertificationCanaryCase.requireCaseId(caseId);
        Objects.requireNonNull(terminalState, "terminalState");
        if (confidenceBps < 0 || confidenceBps > 10_000) {
            throw new IllegalArgumentException("CERTIFICATION_CONFIDENCE_INVALID");
        }
        proposedKeys = List.copyOf(Objects.requireNonNull(proposedKeys, "proposedKeys"));
        if (proposedKeys.isEmpty() || proposedKeys.size() > 256
                || new HashSet<>(proposedKeys).size() != proposedKeys.size()
                || proposedKeys.stream().anyMatch(key -> key == null || key.length() > 96)) {
            throw new IllegalArgumentException("CERTIFICATION_PROPOSED_KEYS_INVALID");
        }
    }
}
