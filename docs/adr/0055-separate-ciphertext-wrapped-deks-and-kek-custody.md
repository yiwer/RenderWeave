---
status: accepted
---

# Separate ciphertext, wrapped DEKs and KEK custody

Every persisted normalized inference artifact is sealed with a random per-artifact 256-bit DEK using AES-256-GCM and domain-separated associated data. Blob storage contains only opaque ciphertext; PostgreSQL is authoritative for the immutable payload nonce, tag and ciphertext digest plus the wrapped DEK. The KEK ring is a separate orchestrator-mounted, read-only secret domain and is forbidden from PostgreSQL, Blob storage, backups, logs, evidence, the gateway and the OCR sidecar. Production encryption stays fail-closed and is not enabled merely by this implementation checkpoint.

KEK rotation unwraps and re-wraps only the DEK; payload ciphertext and its AEAD metadata never change, and an old KEK may be destroyed only after its PostgreSQL reference count reaches zero. A missing or corrupt database envelope, ciphertext or required KEK is unreadable rather than guessed or repaired; KEK loss is accepted as crypto-erasure. Blob-first persistence can leave only encrypted crash orphans, which an artifact-scoped PostgreSQL lock makes safe to reconcile on retry. Payload deletion removes both wrapped DEK metadata and ciphertext; restore must treat PostgreSQL as authority, remove orphans and replay tombstone/no-resurrection rules before traffic is admitted.
