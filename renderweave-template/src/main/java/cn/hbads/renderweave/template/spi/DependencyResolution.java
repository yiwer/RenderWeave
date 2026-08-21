package cn.hbads.renderweave.template.spi;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.template.api.TemplateApplication;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Outbound seam for freezing exact Template dependency facts. It is a system-level
 * aggregate probe, never a user-facing read authorization shortcut.
 */
public interface DependencyResolution {

    AssetResolution resolveAsset(String assetId);

    TemplateResolution resolveTemplate(String targetTemplateId);

    sealed interface AssetResolution permits AssetResolved, AssetMissing, AssetUnavailable {
    }

    record AssetResolved(AssetState state) implements AssetResolution {
        public AssetResolved {
            Objects.requireNonNull(state, "state");
        }
    }

    record AssetMissing() implements AssetResolution {
    }

    record AssetUnavailable() implements AssetResolution {
    }

    sealed interface TemplateResolution permits
            TemplateResolved,
            TemplateMissing,
            TemplateUnavailable {
    }

    record TemplateResolved(TemplateState state) implements TemplateResolution {
        public TemplateResolved {
            Objects.requireNonNull(state, "state");
        }
    }

    record TemplateMissing() implements TemplateResolution {
    }

    record TemplateUnavailable() implements TemplateResolution {
    }

    record AssetState(
            OwnerScopeAuthority.OwnerScope ownerScope,
            String kind,
            Lifecycle lifecycle,
            long assetRevision,
            long currentContentVersion
    ) {
        public AssetState {
            Objects.requireNonNull(ownerScope, "ownerScope");
            if (kind == null || kind.isBlank() || kind.length() > 64) {
                throw new IllegalArgumentException("asset kind must be non-blank and bounded");
            }
            Objects.requireNonNull(lifecycle, "lifecycle");
            if (assetRevision < 0 || currentContentVersion < 0) {
                throw new IllegalArgumentException("asset revisions must not be negative");
            }
        }
    }

    record TemplateState(
            String templateId,
            OwnerScopeAuthority.OwnerScope ownerScope,
            long currentRevision,
            Lifecycle lifecycle,
            TemplateApplication.Readiness readiness,
            StaticSchemaRef staticSchema,
            String contentHash,
            List<TemplateUseEdge> uses,
            String canonicalDesignDsl
    ) {
        public TemplateState {
            if (templateId == null || templateId.isBlank() || templateId.length() > 128) {
                throw new IllegalArgumentException("templateId must be non-blank and bounded");
            }
            Objects.requireNonNull(ownerScope, "ownerScope");
            if (currentRevision < 0) {
                throw new IllegalArgumentException("currentRevision must not be negative");
            }
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(readiness, "readiness");
            Objects.requireNonNull(staticSchema, "staticSchema");
            if (contentHash == null || !contentHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "contentHash must use the sha256 wire format"
                );
            }
            uses = List.copyOf(Objects.requireNonNull(uses, "uses")).stream()
                    .sorted(Comparator.comparing(TemplateUseEdge::canonicalPointer)
                            .thenComparing(TemplateUseEdge::targetTemplateId))
                    .toList();
            if (canonicalDesignDsl == null || canonicalDesignDsl.isEmpty()
                    || canonicalDesignDsl.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > 16_777_216) {
                throw new IllegalArgumentException(
                        "canonicalDesignDsl must be present and bounded"
                );
            }
        }
    }

    record TemplateUseEdge(String targetTemplateId, String canonicalPointer) {
        public TemplateUseEdge {
            if (targetTemplateId == null || targetTemplateId.isBlank()
                    || targetTemplateId.length() > 128) {
                throw new IllegalArgumentException(
                        "targetTemplateId must be non-blank and bounded"
                );
            }
            if (canonicalPointer == null || canonicalPointer.length() > 2048) {
                throw new IllegalArgumentException(
                        "canonicalPointer must be present and bounded"
                );
            }
        }
    }

    enum Lifecycle {
        ACTIVE,
        DELETED
    }
}
