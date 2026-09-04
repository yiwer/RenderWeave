---
status: accepted
---

# Bind the normalized manifest and transfer confirmation in one transaction

IMAGE_ONLY live creation enters through one `ImageOnlyProductionAdmission` module. The server resolves the immutable notice, policy, Provider contract and Profile; validates `USER_PROVIDED + ORDINARY_DESIGN`; normalizes 1–10 bounded PNG/JPEG inputs; and commits the run, ordered payload-free `LiveInputManifest` and first `ExternalTransferConfirmation` in one PostgreSQL transaction. The idempotency fingerprint binds the actor and all semantic transfer facts, but deliberately excludes the fresh gateway requestId/jti, generated IDs and confirmation time, so a response-loss retry with a fresh `GatewayAssertion` can return the original confirmation without creating or dispatching another run. Source filenames are never part of the manifest or durable facts.

Normalized content bytes are produced before the database transaction. On an unknown persistence outcome the module does not delete their content-addressed locator, because the transaction may already have committed and a cleanup would corrupt the admitted run; P2-03/P2-04 must instead make these bytes encrypted and reconcile unreferenced ciphertext. This chooses safe response-loss recovery over eager cleanup of an ambiguous orphan and does not authorize Provider dispatch, Candidate apply, StaticSchema publication or production deployment.
