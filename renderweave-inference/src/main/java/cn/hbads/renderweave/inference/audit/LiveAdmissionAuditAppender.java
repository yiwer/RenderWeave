package cn.hbads.renderweave.inference.audit;

/** Appends one payload-free audit event inside the caller's transaction. */
@FunctionalInterface
public interface LiveAdmissionAuditAppender {
    LiveAdmissionAuditEvent append(LiveAdmissionAuditEvent unsigned);
}
