package cn.hbads.renderweave.inference.candidate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Candidate-only value descriptor. UNRESOLVED and CONFLICT never exist in the formal Schema DSL. */
public record CandidateValue(
        CandidateValueKind kind,
        CandidateValue items,
        CandidateReference reference,
        List<String> observedKinds,
        Map<String, String> constraints
) {
    public CandidateValue {
        Objects.requireNonNull(kind, "kind");
        observedKinds = List.copyOf(Objects.requireNonNull(observedKinds, "observedKinds"));
        constraints = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(constraints, "constraints")));
    }

    public static CandidateValue scalar(CandidateValueKind kind) {
        if (kind == CandidateValueKind.ARRAY || kind == CandidateValueKind.REFERENCE
                || kind == CandidateValueKind.UNRESOLVED || kind == CandidateValueKind.CONFLICT) {
            throw new IllegalArgumentException("scalar factory requires a supported scalar kind");
        }
        return new CandidateValue(kind, null, null, List.of(), Map.of());
    }

    public static CandidateValue array(CandidateValue items) {
        return new CandidateValue(CandidateValueKind.ARRAY, Objects.requireNonNull(items, "items"),
                null, List.of(), Map.of());
    }

    public static CandidateValue reference(CandidateReference reference) {
        return new CandidateValue(CandidateValueKind.REFERENCE, null,
                Objects.requireNonNull(reference, "reference"), List.of(), Map.of());
    }

    public static CandidateValue unresolved(String... observedKinds) {
        return new CandidateValue(CandidateValueKind.UNRESOLVED, null, null,
                List.of(observedKinds), Map.of());
    }

    public static CandidateValue conflict(String... observedKinds) {
        return new CandidateValue(CandidateValueKind.CONFLICT, null, null,
                List.of(observedKinds), Map.of());
    }
}
