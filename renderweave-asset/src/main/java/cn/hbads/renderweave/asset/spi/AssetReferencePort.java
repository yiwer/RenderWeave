package cn.hbads.renderweave.asset.spi;

import cn.hbads.renderweave.asset.api.AssetApplication;

import java.util.List;
import java.util.Objects;

/**
 * Asset-owned outbound seam for the current-only Template reference proof used by the
 * Asset delete orchestration (ADR-0043 §7 T12b, CONTEXT "AssetReferenceAuthority").
 * The app Adapter bridges this port to the Template-owned
 * {@code AssetReferenceAuthority} and applies caller-scope redaction: the impact report
 * carries the full reference count, the Template details the caller may read, the
 * {@code redactedCount}, and a fingerprint over the complete (unredacted) reference set
 * that the confirmation token binds. This port never shares Template aggregates, tables
 * or transactions with Asset persistence.
 */
public interface AssetReferencePort {

    ReferenceOutcome references(
            AssetApplication.InvocationRef invocation,
            AssetApplication.AssetId assetId
    );

    sealed interface ReferenceOutcome permits
            ReferencesReadable,
            ReferencesUnavailable {
    }

    record ReferencesReadable(ReferenceProof proof) implements ReferenceOutcome {
        public ReferencesReadable {
            Objects.requireNonNull(proof, "proof");
        }
    }

    record ReferencesUnavailable() implements ReferenceOutcome {
    }

    /**
     * Caller-scoped impact report. {@code totalCount} is the complete current-only
     * reference count; {@code readableTemplateIds} are the referencing Templates the
     * caller may read; {@code redactedCount} counts the remainder. The fingerprint is
     * over the complete sorted reference set (never the redacted subset).
     */
    record ReferenceProof(
            int totalCount,
            List<String> readableTemplateIds,
            int redactedCount,
            String referenceFingerprint
    ) {
        public ReferenceProof {
            if (totalCount < 0 || redactedCount < 0
                    || totalCount < readableTemplateIds.size()
                    || totalCount < redactedCount
                    || totalCount != readableTemplateIds.size() + redactedCount) {
                throw new IllegalArgumentException("inconsistent reference proof counts");
            }
            readableTemplateIds = List.copyOf(
                    Objects.requireNonNull(readableTemplateIds, "readableTemplateIds"));
            Objects.requireNonNull(referenceFingerprint, "referenceFingerprint");
            if (referenceFingerprint.length() != 64) {
                throw new IllegalArgumentException(
                        "referenceFingerprint must be a 64-hex SHA-256 fingerprint");
            }
        }
    }
}
