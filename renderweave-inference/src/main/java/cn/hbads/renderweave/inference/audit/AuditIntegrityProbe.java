package cn.hbads.renderweave.inference.audit;

/**
 * Projects whether the Live Admission Audit is currently writable and internally consistent.
 * An unhealthy probe must fail closed as {@code AUDIT_INTEGRITY_UNAVAILABLE}.
 */
public interface AuditIntegrityProbe {
    Snapshot snapshot();

    static AuditIntegrityProbe healthy() {
        return () -> new Snapshot(true, null, 0);
    }

    record Snapshot(boolean healthy, String reasonCode, int verifiedRunCount) {
        public Snapshot {
            if (healthy && reasonCode != null) {
                throw new IllegalArgumentException("Healthy audit has no failure projection");
            }
            if (!healthy && reasonCode == null) {
                throw new IllegalArgumentException("Unhealthy audit requires a reason code");
            }
            if (verifiedRunCount < 0) {
                throw new IllegalArgumentException("verifiedRunCount must be non-negative");
            }
        }
    }
}
