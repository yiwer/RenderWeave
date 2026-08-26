package cn.hbads.renderweave.template.api;

import java.util.List;
import java.util.Objects;

/**
 * Template-owned authority for the closed Design/Input/Expression capacity profile.
 *
 * <p>The caller supplies an already measured scalar observation. Product adapters remain
 * responsible for taking that measurement at the exact reservation point.</p>
 */
public interface DesignInputExpressionCapacityAuthority {

    Decision evaluate(Observation observation);

    record Observation(String limitId, String observedValue) {
        public Observation {
            Objects.requireNonNull(limitId, "limitId");
            Objects.requireNonNull(observedValue, "observedValue");
        }
    }

    sealed interface Decision permits Accepted, Rejected, Invalid {
    }

    record Accepted() implements Decision {
    }

    record Rejected(Terminal terminal) implements Decision {
        public Rejected {
            Objects.requireNonNull(terminal, "terminal");
        }
    }

    record Invalid(InvalidReason reason) implements Decision {
        public Invalid {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Terminal(
            String code,
            String contractStage,
            String publicRenderStage,
            String zeroBoundary,
            List<String> downstreamEffects
    ) {
        public Terminal {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(contractStage, "contractStage");
            Objects.requireNonNull(publicRenderStage, "publicRenderStage");
            Objects.requireNonNull(zeroBoundary, "zeroBoundary");
            downstreamEffects = List.copyOf(
                    Objects.requireNonNull(downstreamEffects, "downstreamEffects")
            );
        }
    }

    enum InvalidReason {
        UNKNOWN_LIMIT,
        INVALID_OBSERVED_VALUE
    }
}
