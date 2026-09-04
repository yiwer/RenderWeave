package cn.hbads.renderweave.inference.audit;

import java.util.List;
import java.util.UUID;

/** Read-only projection of one run's audit chain in storage order. */
public interface LiveAdmissionAuditReader {
    List<LiveAdmissionAuditEvent> eventsForRun(UUID runId);
}
