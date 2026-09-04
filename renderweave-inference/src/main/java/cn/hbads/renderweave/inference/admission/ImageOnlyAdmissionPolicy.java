package cn.hbads.renderweave.inference.admission;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Persisted, versioned application policy deciding whether IMAGE_ONLY live Provider work may
 * currently be admitted or continued. Default closed; only an ops identity may append changes;
 * credential presence never opens this switch.
 */
public final class ImageOnlyAdmissionPolicy {
    public static final String POLICY_VERSION = "renderweave-image-only-admission-policy/1.0";
    public static final String DISABLED_REASON_CODE = "LIVE_POLICY_DISABLED";

    public static final Set<String> CHANGE_REASONS = Set.of(
            "DEFAULT_CLOSED",
            "OPS_ENABLED",
            "OPS_DISABLED",
            "MISCLASSIFICATION_SHUTDOWN",
            "AUTOMATIC_COST_STOP"
    );

    private ImageOnlyAdmissionPolicy() { }

    /** Immutable in-memory policy authority for tests and offline tools. */
    public static ImageOnlyAdmissionPolicyStore fixed(boolean enabled) {
        var snapshot = new Snapshot(1, enabled, "renderweave-test-fixture",
                enabled ? "OPS_ENABLED" : "DEFAULT_CLOSED", Instant.ofEpochSecond(0));
        return new ImageOnlyAdmissionPolicyStore() {
            @Override
            public Snapshot current() {
                return snapshot;
            }

            @Override
            public Snapshot append(boolean nextEnabled, String opsIdentity, String reason, Instant at) {
                throw new UnsupportedOperationException("Fixed test policy is immutable");
            }
        };
    }

    public record Snapshot(
            int version,
            boolean enabled,
            String changedBy,
            String changeReason,
            Instant changedAt
    ) {
        public Snapshot {
            if (version < 1) {
                throw new IllegalArgumentException("Policy version starts at 1");
            }
            Objects.requireNonNull(changedBy, "changedBy");
            Objects.requireNonNull(changeReason, "changeReason");
            Objects.requireNonNull(changedAt, "changedAt");
            if (!changedBy.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,191}")) {
                throw new IllegalArgumentException("Policy change identity must be opaque");
            }
            if (!CHANGE_REASONS.contains(changeReason)) {
                throw new IllegalArgumentException("Policy change reason is not in the closed set");
            }
        }
    }
}
