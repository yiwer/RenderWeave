package cn.hbads.renderweave.asset.api;

import cn.hbads.renderweave.asset.spi.AssetPersistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Authoring/product interface for the Asset aggregate. */
public interface AssetApplication {

    CreateOutcome create(InvocationRef invocation, CreateCommand command);

    CurrentOutcome getCurrent(InvocationRef invocation, AssetId assetId);

    UpdateOutcome updateMetadata(InvocationRef invocation, UpdateMetadataCommand command);

    CatalogOutcome catalog(InvocationRef invocation, CatalogCommand command);

    VersionsOutcome listContentVersions(InvocationRef invocation, AssetId assetId);

    DownloadOutcome downloadExact(InvocationRef invocation, AssetId assetId, long contentVersion);

    record OwnerScope(String value) {
        public OwnerScope {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(
                        "ownerScope must be non-blank and at most 256 characters"
                );
            }
        }
    }

    enum Lifecycle {
        ACTIVE,
        DELETED
    }

    record InvocationRef(String value) {
        public InvocationRef {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(
                        "invocation must be non-blank and at most 256 characters"
                );
            }
        }

        public static InvocationRef serverCreated(String value) {
            return new InvocationRef(value);
        }
    }

    record AssetId(String value) {
        private static final java.util.regex.Pattern CANONICAL_V4 =
                java.util.regex.Pattern.compile(
                        "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
                );

        public AssetId {
            if (value == null || !CANONICAL_V4.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        "assetId must be a canonical lowercase UUID v4"
                );
            }
        }

        public static AssetId of(String value) {
            return new AssetId(value);
        }
    }

    final class CreateCommand {
        private final String idempotencyKey;
        private final AssetAcceptanceAuthority.AssetKind kind;
        private final String displayName;
        private final List<String> tags;
        private final String sourceFileName;
        private final byte[] rawContent;

        public CreateCommand(
                String idempotencyKey,
                AssetAcceptanceAuthority.AssetKind kind,
                String displayName,
                List<String> tags,
                String sourceFileName,
                byte[] rawContent
        ) {
            this.idempotencyKey = requireNonBlank(idempotencyKey, 128, "idempotencyKey");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.displayName = requireNonBlank(displayName, 200, "displayName");
            this.tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
            this.sourceFileName = sourceFileName == null ? null
                    : requireNonBlank(sourceFileName, 255, "sourceFileName");
            this.rawContent = Objects.requireNonNull(rawContent, "rawContent").clone();
        }

        public String idempotencyKey() {
            return idempotencyKey;
        }

        public AssetAcceptanceAuthority.AssetKind kind() {
            return kind;
        }

        public String displayName() {
            return displayName;
        }

        public List<String> tags() {
            return tags;
        }

        public String sourceFileName() {
            return sourceFileName;
        }

        public byte[] rawContent() {
            return rawContent.clone();
        }
    }

    final class UpdateMetadataCommand {
        private final AssetId assetId;
        private final long expectedAssetRevision;
        private final String displayName;
        private final List<String> tags;

        public UpdateMetadataCommand(
                AssetId assetId,
                long expectedAssetRevision,
                String displayName,
                List<String> tags
        ) {
            this.assetId = Objects.requireNonNull(assetId, "assetId");
            if (expectedAssetRevision < 0) {
                throw new IllegalArgumentException("expectedAssetRevision must not be negative");
            }
            this.expectedAssetRevision = expectedAssetRevision;
            this.displayName = requireNonBlank(displayName, 200, "displayName");
            this.tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        }

        public AssetId assetId() {
            return assetId;
        }

        public long expectedAssetRevision() {
            return expectedAssetRevision;
        }

        public String displayName() {
            return displayName;
        }

        public List<String> tags() {
            return tags;
        }
    }

    final class CatalogCommand {
        private final AssetAcceptanceAuthority.AssetKind kind;
        private final List<String> tagsAll;
        private final List<String> tagsAny;
        private final String displayNameContains;
        private final String sourceFileNameContains;
        private final boolean includeDeleted;
        private final String cursor;
        private final int limit;

        public CatalogCommand(
                AssetAcceptanceAuthority.AssetKind kind,
                List<String> tagsAll,
                List<String> tagsAny,
                String displayNameContains,
                String sourceFileNameContains,
                boolean includeDeleted,
                String cursor,
                int limit
        ) {
            this.kind = kind;
            this.tagsAll = List.copyOf(tagsAll == null ? List.of() : tagsAll);
            this.tagsAny = List.copyOf(tagsAny == null ? List.of() : tagsAny);
            this.displayNameContains = displayNameContains == null ? null
                    : requireNonBlank(displayNameContains, 200, "displayNameContains");
            this.sourceFileNameContains = sourceFileNameContains == null ? null
                    : requireNonBlank(sourceFileNameContains, 255, "sourceFileNameContains");
            this.includeDeleted = includeDeleted;
            this.cursor = cursor == null ? null
                    : requireNonBlank(cursor, 512, "cursor");
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            this.limit = limit;
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

    record AssetDetail(
            AssetId assetId,
            OwnerScope ownerScope,
            AssetAcceptanceAuthority.AssetKind kind,
            Lifecycle lifecycle,
            long assetRevision,
            long currentContentVersion,
            String displayName,
            List<String> tags,
            String sourceFileName,
            String mediaType,
            long byteLength,
            String sha256,
            AssetAcceptanceAuthority.TechnicalDescriptor descriptor,
            java.time.Instant createdAt,
            java.time.Instant updatedAt
    ) {
        public AssetDetail {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(ownerScope, "ownerScope");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(lifecycle, "lifecycle");
            if (assetRevision < 0 || currentContentVersion < 0) {
                throw new IllegalArgumentException("revisions must not be negative");
            }
            Objects.requireNonNull(displayName, "displayName");
            tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
            Objects.requireNonNull(mediaType, "mediaType");
            if (byteLength <= 0) {
                throw new IllegalArgumentException("byteLength must be positive");
            }
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    record CatalogEntry(
            AssetId assetId,
            AssetAcceptanceAuthority.AssetKind kind,
            Lifecycle lifecycle,
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

    record DownloadedContent(
            String mediaType,
            String sourceFileName,
            String sha256,
            long byteLength,
            byte[] bytes
    ) {
        public DownloadedContent {
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(sha256, "sha256");
            if (byteLength <= 0) {
                throw new IllegalArgumentException("byteLength must be positive");
            }
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        public byte[] bytes() {
            return bytes.clone();
        }
    }

    sealed interface CreateOutcome permits
            CreatedReadable,
            CreatedOpaque,
            CreateContentRejected,
            CreateForbidden,
            CreateIdempotencyConflict,
            CreateStorageCapacityExceeded,
            CreateAuthorityUnavailable,
            CreatePersistenceUnavailable {
    }

    record CreatedReadable(AssetDetail detail) implements CreateOutcome {
        public CreatedReadable {
            Objects.requireNonNull(detail, "detail");
        }
    }

    record CreatedOpaque(AssetId assetId) implements CreateOutcome {
        public CreatedOpaque {
            Objects.requireNonNull(assetId, "assetId");
        }
    }

    record CreateContentRejected(AssetAcceptanceAuthority.Rejected rejection)
            implements CreateOutcome {
        public CreateContentRejected {
            Objects.requireNonNull(rejection, "rejection");
        }
    }

    record CreateForbidden() implements CreateOutcome {
    }

    record CreateIdempotencyConflict() implements CreateOutcome {
    }

    record CreateStorageCapacityExceeded() implements CreateOutcome {
    }

    record CreateAuthorityUnavailable() implements CreateOutcome {
    }

    record CreatePersistenceUnavailable() implements CreateOutcome {
    }

    sealed interface CurrentOutcome permits
            CurrentReadable,
            CurrentNotFound,
            CurrentDeleted,
            CurrentForbidden,
            CurrentAuthorityUnavailable,
            CurrentPersistenceUnavailable {
    }

    record CurrentReadable(AssetDetail detail) implements CurrentOutcome {
        public CurrentReadable {
            Objects.requireNonNull(detail, "detail");
        }
    }

    record CurrentNotFound() implements CurrentOutcome {
    }

    record CurrentDeleted() implements CurrentOutcome {
    }

    record CurrentForbidden() implements CurrentOutcome {
    }

    record CurrentAuthorityUnavailable() implements CurrentOutcome {
    }

    record CurrentPersistenceUnavailable() implements CurrentOutcome {
    }

    sealed interface UpdateOutcome permits
            UpdatedReadable,
            UpdateNotFound,
            UpdateDeleted,
            UpdateForbidden,
            UpdateRevisionConflict,
            UpdateAuthorityUnavailable,
            UpdatePersistenceUnavailable {
    }

    record UpdatedReadable(AssetDetail detail) implements UpdateOutcome {
        public UpdatedReadable {
            Objects.requireNonNull(detail, "detail");
        }
    }

    record UpdateNotFound() implements UpdateOutcome {
    }

    record UpdateDeleted() implements UpdateOutcome {
    }

    record UpdateForbidden() implements UpdateOutcome {
    }

    record UpdateRevisionConflict(long currentAssetRevision) implements UpdateOutcome {
        public UpdateRevisionConflict {
            if (currentAssetRevision < 0) {
                throw new IllegalArgumentException("currentAssetRevision must not be negative");
            }
        }
    }

    record UpdateAuthorityUnavailable() implements UpdateOutcome {
    }

    record UpdatePersistenceUnavailable() implements UpdateOutcome {
    }

    sealed interface CatalogOutcome permits
            CatalogPage,
            CatalogForbidden,
            CatalogAuthorityUnavailable,
            CatalogPersistenceUnavailable {
    }

    record CatalogPage(List<CatalogEntry> entries, Optional<String> nextCursor)
            implements CatalogOutcome {
        public CatalogPage {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }

    record CatalogForbidden() implements CatalogOutcome {
    }

    record CatalogAuthorityUnavailable() implements CatalogOutcome {
    }

    record CatalogPersistenceUnavailable() implements CatalogOutcome {
    }

    sealed interface VersionsOutcome permits
            VersionsReadable,
            VersionsNotFound,
            VersionsDeleted,
            VersionsForbidden,
            VersionsAuthorityUnavailable,
            VersionsPersistenceUnavailable {
    }

    record VersionsReadable(List<ContentVersionEntry> entries) implements VersionsOutcome {
        public VersionsReadable {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    record VersionsNotFound() implements VersionsOutcome {
    }

    record VersionsDeleted() implements VersionsOutcome {
    }

    record VersionsForbidden() implements VersionsOutcome {
    }

    record VersionsAuthorityUnavailable() implements VersionsOutcome {
    }

    record VersionsPersistenceUnavailable() implements VersionsOutcome {
    }

    sealed interface DownloadOutcome permits
            DownloadReadable,
            DownloadNotFound,
            DownloadDeleted,
            DownloadForbidden,
            DownloadVersionNotFound,
            DownloadBlobUnavailable,
            DownloadAuthorityUnavailable,
            DownloadPersistenceUnavailable {
    }

    record DownloadReadable(DownloadedContent content) implements DownloadOutcome {
        public DownloadReadable {
            Objects.requireNonNull(content, "content");
        }
    }

    record DownloadNotFound() implements DownloadOutcome {
    }

    record DownloadDeleted() implements DownloadOutcome {
    }

    record DownloadForbidden() implements DownloadOutcome {
    }

    record DownloadVersionNotFound() implements DownloadOutcome {
    }

    record DownloadBlobUnavailable() implements DownloadOutcome {
    }

    record DownloadAuthorityUnavailable() implements DownloadOutcome {
    }

    record DownloadPersistenceUnavailable() implements DownloadOutcome {
    }

    private static String requireNonBlank(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(
                    name + " must be non-blank and at most " + maximum + " characters"
            );
        }
        return value;
    }
}
