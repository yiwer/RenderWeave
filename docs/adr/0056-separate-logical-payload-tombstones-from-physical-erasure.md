---
status: accepted
---

# Separate logical payload tombstones from physical erasure

Each confirmed live run receives immutable, per-artifact retention facts whose seven-day maximum begins at first upload. A shared content-addressed artifact inherits the earliest active origin and expiry; reference reuse and retry never extend it, and less than 24 hours remaining requires a fresh upload. Normalization creates a bounded ingest lease so deletion cannot race the gap before the run and confirmation transaction establishes retention.

Payload deletion is a two-boundary operation. An immutable per-run tombstone is committed first and immediately blocks reads, retries, Provider calls and Candidate apply while terminating a non-APPLYING run. A payload-free, retryable artifact task then removes the encrypted Blob and its PostgreSQL wrapped-DEK envelope only after no active retained or unmanaged reference remains. COMPLETED schedules deletion immediately, FAILED/CANCELLED after at most 24 hours, and a REVIEW_REQUIRED run reaching day seven fails with `LIVE_REVIEW_EXPIRED`. Existing Candidate/Draft state and payload-free audit or usage facts remain intact.

Physical erasure has a 24-hour hard SLO. Failed tasks use bounded leases and backoff; any overdue pending task projects `PAYLOAD_DELETION_UNHEALTHY` and closes new live admission until recovery. Tombstones remain authoritative across worker crashes, deletion failures and restore, so physical absence cannot resurrect access. The scheduler is implemented but disabled by default; enabling it or admitting production traffic remains a later release decision.
