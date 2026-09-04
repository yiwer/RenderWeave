package cn.hbads.renderweave.inference.admission;

import java.util.Objects;

/**
 * Read-only port for the orchestrator/firewall authority that independently decides whether
 * RenderWeave may open new calls toward the approved Provider route. The permit lives outside
 * the application; credential existence or admission policy state never derives a permit.
 */
public interface ProviderEgressPermit {
    String DISABLED_REASON_CODE = "EGRESS_DISABLED";

    Snapshot snapshot();

    static ProviderEgressPermit disabled() {
        return () -> Snapshot.DISABLED;
    }

    record Snapshot(boolean enabled, String identity) {
        public static final Snapshot DISABLED = new Snapshot(false, "absent");

        public Snapshot {
            Objects.requireNonNull(identity, "identity");
            if (identity.isBlank()) {
                throw new IllegalArgumentException("Egress permit identity is required");
            }
            if (!identity.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,191}")) {
                throw new IllegalArgumentException("Egress permit identity must be opaque");
            }
            if (!enabled && !"absent".equals(identity)) {
                throw new IllegalArgumentException("A disabled permit carries no material identity");
            }
        }
    }
}
