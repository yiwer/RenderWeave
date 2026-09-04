package cn.hbads.renderweave.inference.admission;

import java.time.Instant;

/** Append-only authority for the IMAGE_ONLY admission switch. */
public interface ImageOnlyAdmissionPolicyStore {
    /** The newest policy version; version 1 is always the default-closed bootstrap. */
    ImageOnlyAdmissionPolicy.Snapshot current();

    /**
     * Appends the next policy version atomically. Implementations must serialize concurrent
     * appends so versions remain gapless and monotonic.
     */
    ImageOnlyAdmissionPolicy.Snapshot append(boolean enabled, String opsIdentity, String reason, Instant at);
}
