package cn.hbads.renderweave.template.api;

import java.util.Objects;

/**
 * System-level Template readiness recheck (CONTEXT TemplateReadiness): recomputes the
 * READY/INVALID projection of a Template current against the current dependency facts
 * (AssetRef atoms, TemplateUse logical refs, DAG), persisting the result. The app's
 * STALE consumer and every Render-bound recheck use this authority; authoring reads
 * never mutate readiness through this path.
 */
public interface TemplateReadinessAuthority {

    RecheckOutcome recheck(TemplateApplication.TemplateId templateId);

    sealed interface RecheckOutcome permits
            Rechecked,
            RecheckNotFound,
            RecheckDeleted,
            RecheckUnavailable {
    }

    record Rechecked(TemplateApplication.Readiness readiness, long revision)
            implements RecheckOutcome {
        public Rechecked {
            Objects.requireNonNull(readiness, "readiness");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
        }
    }

    record RecheckNotFound() implements RecheckOutcome {
    }

    record RecheckDeleted() implements RecheckOutcome {
    }

    record RecheckUnavailable() implements RecheckOutcome {
    }
}
