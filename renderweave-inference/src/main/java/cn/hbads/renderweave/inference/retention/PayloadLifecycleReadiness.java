package cn.hbads.renderweave.inference.retention;

import java.time.Instant;

public interface PayloadLifecycleReadiness {
    Snapshot snapshot();

    static PayloadLifecycleReadiness healthy() {
        return () -> new Snapshot(true, null, null);
    }

    record Snapshot(boolean healthy, String reasonCode, Instant oldestOverdueAt) {
        public Snapshot {
            if (healthy && (reasonCode != null || oldestOverdueAt != null)) {
                throw new IllegalArgumentException("Healthy payload deletion has no failure projection");
            }
            if (!healthy && (reasonCode == null || oldestOverdueAt == null)) {
                throw new IllegalArgumentException("Unhealthy payload deletion requires a reason and boundary");
            }
        }
    }
}
