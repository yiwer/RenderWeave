package cn.hbads.renderweave.asset.spi;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetApplication;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Transaction-sized persistence seam for the Asset aggregate; not a generic repository. */
public interface AssetPersistence {

    LocateOutcome locate(AssetApplication.AssetId assetId);

    LoadCurrentOutcome loadCurrent(AssetApplication.AssetId assetId);

    CreateOutcome create(CreateCommit commit);

    UpdateMetadataOutcome updateMetadata(UpdateMetadataCommit commit);

    AppendContentOutcome appendContent(AppendContentCommit commit);

    ContentVersionOutcome loadContentVersion(
            AssetApplication.AssetId assetId,
            long contentVersion
    );

    CatalogOutcome catalog(CatalogQuery query);

    VersionsOutcome listContentVersions(AssetApplication.AssetId assetId);

    IdempotencyOutcome resolveIdempotency(IdempotencyQuery query);

    CapacityOutcome capacity();

    /** Bounded audit operations recorded for every effective Asset mutation. */
    enum AuditOperation {
        CREATE,
        METADATA_UPDATE,
        CONTENT_REPLACE,
        CONTENT_RESTORE,
        DELETE,
        RESTORE
    }

    record AssetMetadata(
            AssetApplication.AssetId assetId,
            AssetApplication.OwnerScope ownerScope,
            AssetAcceptanceAuthority.AssetKind kind,
            AssetApplication.Lifecycle lifecycle,
            long assetRevision,
            long currentContentVersion,
            String displayName,
            List<String> tags,
            String sourceFileName,
            java.time.Instant createdAt,
            java.time.Instant updatedAt
    ) {
        public AssetMetadata {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(ownerScope, "ownerScope");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(lifecycle, "lifecycle");
            if (assetRevision < 0 || currentContentVersion < 0) {
                throw new IllegalArgumentException("revisions must not be negative");
            }
            Objects.requireNonNull(displayName, "displayName");
            tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    record StoredContent(
            long contentVersion,
            String sha256,
            String mediaType,
            long byteLength,
            String sourceFileName,
            AssetAcceptanceAuthority.TechnicalDescriptor descriptor,
            java.time.Instant createdAt
    ) {
        public StoredContent {
            if (contentVersion < 0 || byteLength <= 0) {
                throw new IllegalArgumentException("invalid stored content");
            }
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    final class StoredCurrent {
        private final AssetMetadata metadata;
        private final StoredContent content;

        public StoredCurrent(AssetMetadata metadata, StoredContent content) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            this.content = Objects.requireNonNull(content, "content");
        }

        public AssetMetadata metadata() {
            return metadata;
        }

        public StoredContent content() {
            return content;
        }
    }

    record CatalogEntry(
            AssetApplication.AssetId assetId,
            AssetAcceptanceAuthority.AssetKind kind,
            AssetApplication.Lifecycle lifecycle,
            String displayName,
            List<String> tags,
            String sourceFileName,
            java.time.Instant updatedAt
    ) {
        public CatalogEntry {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(displayName, "displayName");
            tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    record ContentVersionEntry(
            long contentVersion,
            String sha256,
            String mediaType,
            long byteLength,
            String sourceFileName,
            java.time.Instant createdAt
    ) {
        public ContentVersionEntry {
            if (contentVersion < 0 || byteLength <= 0) {
                throw new IllegalArgumentException("invalid content version entry");
            }
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    final class CatalogQuery {
        private final AssetApplication.OwnerScope ownerScope;
        private final AssetAcceptanceAuthority.AssetKind kind;
        private final List<String> tagsAll;
        private final List<String> tagsAny;
        private final String displayNameContains;
        private final String sourceFileNameContains;
        private final boolean includeDeleted;
        private final String cursor;
        private final int limit;

        public CatalogQuery(
                AssetApplication.OwnerScope ownerScope,
                AssetAcceptanceAuthority.AssetKind kind,
                List<String> tagsAll,
                List<String> tagsAny,
                String displayNameContains,
                String sourceFileNameContains,
                boolean includeDeleted,
                String cursor,
                int limit
        ) {
            this.ownerScope = Objects.requireNonNull(ownerScope, "ownerScope");
            this.kind = kind;
            this.tagsAll = List.copyOf(Objects.requireNonNull(tagsAll, "tagsAll"));
            this.tagsAny = List.copyOf(Objects.requireNonNull(tagsAny, "tagsAny"));
            this.displayNameContains = displayNameContains;
            this.sourceFileNameContains = sourceFileNameContains;
            this.includeDeleted = includeDeleted;
            this.cursor = cursor;
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            this.limit = limit;
        }

        public AssetApplication.OwnerScope ownerScope() {
            return ownerScope;
        }

        public AssetAcceptanceAuthority.AssetKind kind() {
            return kind;
        }

        public List<String> tagsAll() {
            return tagsAll;
        }

        public List<String> tagsAny() {
            return tagsAny;
        }

        public String displayNameContains() {
            return displayNameContains;
        }

        public String sourceFileNameContains() {
            return sourceFileNameContains;
        }

        public boolean includeDeleted() {
            return includeDeleted;
        }

        public String cursor() {
            return cursor;
        }

        public int limit() {
            return limit;
        }
    }

    interface CreateCommit {
        AssetApplication.AssetId assetId();

        AssetApplication.OwnerScope ownerScope();

        AssetAcceptanceAuthority.AssetKind kind();

        String displayName();

        List<String> tags();

        String sourceFileName();

        long assetRevision();

        long contentVersion();

        String sha256();

        String mediaType();

        long byteLength();

        AssetAcceptanceAuthority.TechnicalDescriptor descriptor();

        String idempotencyKey();

        String idempotencyFingerprint();

        boolean blobCreated();

        String actorId();
    }

    interface UpdateMetadataCommit {
        AssetApplication.AssetId assetId();

        AssetApplication.OwnerScope ownerScope();

        long expectedAssetRevision();

        String displayName();

        List<String> tags();

        String actorId();
    }

    interface AppendContentCommit {
        AssetApplication.AssetId assetId();

        AssetApplication.OwnerScope ownerScope();

        long expectedAssetRevision();

        long contentVersion();

        String sha256();

        String mediaType();

        long byteLength();

        String sourceFileName();

        AssetAcceptanceAuthority.TechnicalDescriptor descriptor();

        boolean blobCreated();

        AuditOperation operation();

        String actorId();
    }

    interface IdempotencyQuery {
        AssetApplication.OwnerScope ownerScope();

        String idempotencyKey();

        String fingerprint();
    }

    sealed interface LocateOutcome permits Located, LocateNotFound, LocateUnavailable {
    }

    record Located(AssetMetadata metadata) implements LocateOutcome {
        public Located {
            Objects.requireNonNull(metadata, "metadata");
        }
    }

    record LocateNotFound() implements LocateOutcome {
    }

    record LocateUnavailable() implements LocateOutcome {
    }

    sealed interface LoadCurrentOutcome permits
            CurrentLoaded,
            CurrentNotFound,
            CurrentLoadUnavailable {
    }

    record CurrentLoaded(StoredCurrent current) implements LoadCurrentOutcome {
        public CurrentLoaded {
            Objects.requireNonNull(current, "current");
        }
    }

    record CurrentNotFound() implements LoadCurrentOutcome {
    }

    record CurrentLoadUnavailable() implements LoadCurrentOutcome {
    }

    sealed interface CreateOutcome permits
            Created,
            AssetIdCollision,
            StorageCapacityExceeded,
            CreateUnavailable {
    }

    record Created() implements CreateOutcome {
    }

    record AssetIdCollision() implements CreateOutcome {
    }

    record StorageCapacityExceeded() implements CreateOutcome {
    }

    record CreateUnavailable() implements CreateOutcome {
    }

    sealed interface UpdateMetadataOutcome permits
            MetadataUpdated,
            UpdateNotFound,
            UpdateDeleted,
            UpdateRevisionConflict,
            UpdateUnavailable {
    }

    record MetadataUpdated(boolean revisionAdvanced) implements UpdateMetadataOutcome {
    }

    record UpdateNotFound() implements UpdateMetadataOutcome {
    }

    record UpdateDeleted() implements UpdateMetadataOutcome {
    }

    record UpdateRevisionConflict(long currentAssetRevision) implements UpdateMetadataOutcome {
        public UpdateRevisionConflict {
            if (currentAssetRevision < 0) {
                throw new IllegalArgumentException("currentAssetRevision must not be negative");
            }
        }
    }

    record UpdateUnavailable() implements UpdateMetadataOutcome {
    }

    sealed interface AppendContentOutcome permits
            ContentAppended,
            AppendNotFound,
            AppendDeleted,
            AppendRevisionConflict,
            AppendStorageCapacityExceeded,
            AppendUnavailable {
    }

    record ContentAppended() implements AppendContentOutcome {
    }

    record AppendNotFound() implements AppendContentOutcome {
    }

    record AppendDeleted() implements AppendContentOutcome {
    }

    record AppendRevisionConflict(long currentAssetRevision) implements AppendContentOutcome {
        public AppendRevisionConflict {
            if (currentAssetRevision < 0) {
                throw new IllegalArgumentException("currentAssetRevision must not be negative");
            }
        }
    }

    record AppendStorageCapacityExceeded() implements AppendContentOutcome {
    }

    record AppendUnavailable() implements AppendContentOutcome {
    }

    sealed interface ContentVersionOutcome permits
            ContentVersionLoaded,
            ContentVersionNotFound,
            ContentVersionUnavailable {
    }

    record ContentVersionLoaded(StoredContent content) implements ContentVersionOutcome {
        public ContentVersionLoaded {
            Objects.requireNonNull(content, "content");
        }
    }

    record ContentVersionNotFound() implements ContentVersionOutcome {
    }

    record ContentVersionUnavailable() implements ContentVersionOutcome {
    }

    sealed interface CatalogOutcome permits
            CatalogPage,
            CatalogUnavailable {
    }

    record CatalogPage(List<CatalogEntry> entries, Optional<String> nextCursor)
            implements CatalogOutcome {
        public CatalogPage {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }

    record CatalogUnavailable() implements CatalogOutcome {
    }

    sealed interface VersionsOutcome permits
            VersionsListed,
            VersionsNotFound,
            VersionsUnavailable {
    }

    record VersionsListed(List<ContentVersionEntry> entries) implements VersionsOutcome {
        public VersionsListed {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    record VersionsNotFound() implements VersionsOutcome {
    }

    record VersionsUnavailable() implements VersionsOutcome {
    }

    sealed interface IdempotencyOutcome permits
            IdempotencyReplay,
            IdempotencyConflict,
            IdempotencyMiss,
            IdempotencyUnavailable {
    }

    record IdempotencyReplay(AssetApplication.AssetId assetId) implements IdempotencyOutcome {
        public IdempotencyReplay {
            Objects.requireNonNull(assetId, "assetId");
        }
    }

    record IdempotencyConflict() implements IdempotencyOutcome {
    }

    record IdempotencyMiss() implements IdempotencyOutcome {
    }

    record IdempotencyUnavailable() implements IdempotencyOutcome {
    }

    sealed interface CapacityOutcome permits Capacity, CapacityUnavailable {
    }

    record Capacity(long hardLimitBytes, long usedBytes) implements CapacityOutcome {
        public Capacity {
            if (hardLimitBytes < 0 || usedBytes < 0) {
                throw new IllegalArgumentException("capacity values must not be negative");
            }
        }
    }

    record CapacityUnavailable() implements CapacityOutcome {
    }
}
