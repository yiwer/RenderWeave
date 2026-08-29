package cn.hbads.renderweave.template.internal;

import java.util.Arrays;
import java.util.Objects;

/** Single frozen capacity catalog used by the render-only Template closure freeze. */
final class TemplateClosureCapacityGuard {

    enum Comparison {
        MAX_INCLUSIVE,
        EXACT
    }

    enum Limit {
        UNIQUE_TEMPLATE_SNAPSHOTS(
                "uniqueTemplateSnapshots", 64, Comparison.MAX_INCLUSIVE,
                "TEMPLATE_CLOSURE_LIMIT_EXCEEDED"),
        AUTHORED_TEMPLATE_REF_EDGES(
                "authoredTemplateRefEdges", 256, Comparison.MAX_INCLUSIVE,
                "TEMPLATE_CLOSURE_LIMIT_EXCEEDED"),
        CLOSURE_DEPTH(
                "closureDepth", 16, Comparison.MAX_INCLUSIVE,
                "TEMPLATE_CLOSURE_LIMIT_EXCEEDED"),
        CLOSURE_FREEZE_ATTEMPTS(
                "closureFreezeAttempts", 3, Comparison.EXACT,
                "TEMPLATE_CLOSURE_UNSTABLE"),
        CLOSURE_CANONICAL_DESIGN_BYTES(
                "closureCanonicalDesignBytes", 32L * 1024 * 1024,
                Comparison.MAX_INCLUSIVE, "TEMPLATE_CLOSURE_LIMIT_EXCEEDED");

        private final String localId;
        private final long frozenValue;
        private final Comparison comparison;
        private final String terminalCode;

        Limit(
                String localId,
                long frozenValue,
                Comparison comparison,
                String terminalCode
        ) {
            this.localId = localId;
            this.frozenValue = frozenValue;
            this.comparison = comparison;
            this.terminalCode = terminalCode;
        }

        String localId() {
            return localId;
        }

        String fullId() {
            return "closureAndExpansion." + localId;
        }
    }

    record Decision(
            boolean accepted,
            String limitId,
            long observedValue,
            String terminalCode
    ) {
        Decision {
            Objects.requireNonNull(limitId, "limitId");
            if (observedValue < 0) {
                throw new IllegalArgumentException("observedValue must be non-negative");
            }
            if (accepted != (terminalCode == null)) {
                throw new IllegalArgumentException(
                        "accepted decision and terminalCode must be complementary");
            }
        }
    }

    Limit require(String fullId) {
        Objects.requireNonNull(fullId, "fullId");
        return Arrays.stream(Limit.values())
                .filter(limit -> limit.fullId().equals(fullId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown Template closure capacity limit: " + fullId));
    }

    Decision evaluate(String fullId, long observedValue) {
        return evaluate(require(fullId), observedValue);
    }

    Decision evaluate(Limit limit, long observedValue) {
        Objects.requireNonNull(limit, "limit");
        if (observedValue < 0) {
            throw new IllegalArgumentException("observedValue must be non-negative");
        }
        boolean accepted = switch (limit.comparison) {
            case MAX_INCLUSIVE -> observedValue <= limit.frozenValue;
            case EXACT -> observedValue == limit.frozenValue;
        };
        return new Decision(
                accepted,
                limit.fullId(),
                observedValue,
                accepted ? null : limit.terminalCode);
    }

    long maximumInclusive(Limit limit) {
        Objects.requireNonNull(limit, "limit");
        if (limit.comparison != Comparison.MAX_INCLUSIVE) {
            throw new IllegalArgumentException("limit must be MAX_INCLUSIVE");
        }
        return limit.frozenValue;
    }

    long exactValue(Limit limit) {
        Objects.requireNonNull(limit, "limit");
        if (limit.comparison != Comparison.EXACT) {
            throw new IllegalArgumentException("limit must be EXACT");
        }
        return limit.frozenValue;
    }
}
