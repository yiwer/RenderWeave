package cn.hbads.renderweave.template.api;

import java.util.List;
import java.util.Objects;

/**
 * Template-owned current-only proof for Asset delete prechecks (CONTEXT:
 * "AssetReferenceAuthority 向 Asset 删除流程提供 current-only proof/reservation").
 * Returns every ACTIVE Template whose current dependency projection contains the asset.
 * Caller-scope redaction is the app adapter's concern (T12b delete orchestration);
 * this authority never aggregates occurrences or touches historical revisions.
 */
public interface AssetReferenceAuthority {

    ReferencesOutcome references(String assetId);

    record AssetReferences(java.util.List<TemplateApplication.TemplateId> templateIds) {
        public AssetReferences {
            Objects.requireNonNull(templateIds, "templateIds");
        }
    }

    sealed interface ReferencesOutcome permits
            ReferencesReadable,
            ReferencesUnavailable {
    }

    record ReferencesReadable(AssetReferences references) implements ReferencesOutcome {
        public ReferencesReadable {
            Objects.requireNonNull(references, "references");
        }
    }

    record ReferencesUnavailable() implements ReferencesOutcome {
    }
}
