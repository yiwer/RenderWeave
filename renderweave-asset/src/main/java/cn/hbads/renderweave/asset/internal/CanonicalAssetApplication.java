package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetBlobPersistence;
import cn.hbads.renderweave.asset.spi.AssetOwnerScopeAuthority;
import cn.hbads.renderweave.asset.spi.AssetPersistence;
import cn.hbads.renderweave.asset.spi.AssetReferencePort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

final class CanonicalAssetApplication implements AssetApplication {

    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5);

    private final AssetOwnerScopeAuthority ownerScopeAuthority;
    private final AssetPersistence persistence;
    private final AssetBlobPersistence blobs;
    private final AssetReferencePort referencePort;
    private final AssetAcceptanceAuthority acceptance = new CanonicalAssetAcceptanceAuthority();
    private final SecureRandom secureRandom = new SecureRandom();

    CanonicalAssetApplication(
            AssetOwnerScopeAuthority ownerScopeAuthority,
            AssetPersistence persistence,
            AssetBlobPersistence blobs,
            AssetReferencePort referencePort
    ) {
        this.ownerScopeAuthority = Objects.requireNonNull(ownerScopeAuthority, "ownerScopeAuthority");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.referencePort = Objects.requireNonNull(referencePort, "referencePort");
    }

    @Override
    public CreateOutcome create(InvocationRef invocation, CreateCommand command) {
        var createDecision = ownerScopeAuthority.authorizeCreate(invocation);
        if (createDecision instanceof AssetOwnerScopeAuthority.CreateDenied) {
            return new CreateForbidden();
        }
        if (createDecision instanceof AssetOwnerScopeAuthority.CreateUnavailable) {
            return new CreateAuthorityUnavailable();
        }
        var granted = (AssetOwnerScopeAuthority.CreateGranted) createDecision;

        var admission = acceptance.admit(command.rawContent(), command.kind());
        if (admission instanceof AssetAcceptanceAuthority.Rejected rejected) {
            return new CreateContentRejected(rejected);
        }
        var admitted = (AssetAcceptanceAuthority.Admitted) admission;

        String displayName = normalizeDisplayName(command.displayName());
        List<String> tags = normalizeTags(command.tags());
        String sourceFileName = normalizeSourceFileName(command.sourceFileName());

        String fingerprint = fingerprint(
                granted.ownerScope(),
                command.idempotencyKey(),
                command.kind(),
                displayName,
                tags,
                sourceFileName,
                command.rawContent()
        );
        var idempotency = persistence.resolveIdempotency(new IdempotencyQueryImpl(
                granted.ownerScope(),
                command.idempotencyKey(),
                fingerprint
        ));
        if (idempotency instanceof AssetPersistence.IdempotencyReplay replay) {
            return replayResult(invocation, granted.ownerScope(), replay.assetId());
        }
        if (idempotency instanceof AssetPersistence.IdempotencyConflict) {
            return new CreateIdempotencyConflict();
        }
        if (idempotency instanceof AssetPersistence.IdempotencyUnavailable) {
            return new CreatePersistenceUnavailable();
        }

        var stored = blobs.store(granted.ownerScope().value(), admitted.sha256(), command.rawContent());
        if (stored instanceof AssetBlobPersistence.StoreUnavailable) {
            return new CreatePersistenceUnavailable();
        }
        boolean blobCreated = ((AssetBlobPersistence.Stored) stored).created();

        if (blobCreated) {
            var capacity = persistence.capacity();
            if (capacity instanceof AssetPersistence.CapacityUnavailable) {
                return new CreatePersistenceUnavailable();
            }
            var capacityValue = (AssetPersistence.Capacity) capacity;
            if (capacityValue.usedBytes() + admitted.byteLength() > capacityValue.hardLimitBytes()) {
                return new CreateStorageCapacityExceeded();
            }
        }

        var recheck = ownerScopeAuthority.recheck(granted.recheckIdentity());
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckDenied) {
            return new CreateForbidden();
        }
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckUnavailable) {
            return new CreateAuthorityUnavailable();
        }

        var assetId = AssetId.of(UUID.randomUUID().toString());
        var commit = new AdmittedAssetCreateCommit(
                assetId,
                granted.ownerScope(),
                command.kind(),
                displayName,
                tags,
                sourceFileName,
                0,
                0,
                admitted.sha256(),
                mediaTypeOf(command.rawContent(), command.kind()),
                admitted.byteLength(),
                admitted.descriptor(),
                command.idempotencyKey(),
                fingerprint,
                blobCreated,
                invocation.value()
        );
        var created = persistence.create(commit);
        if (created instanceof AssetPersistence.StorageCapacityExceeded) {
            return new CreateStorageCapacityExceeded();
        }
        if (created instanceof AssetPersistence.CreateUnavailable
                || created instanceof AssetPersistence.AssetIdCollision) {
            return new CreatePersistenceUnavailable();
        }

        if (granted.disclosure() == AssetOwnerScopeAuthority.Disclosure.READABLE) {
            return new CreatedReadable(detail(commit, java.time.Instant.now()));
        }
        return new CreatedOpaque(assetId);
    }

    @Override
    public CurrentOutcome getCurrent(InvocationRef invocation, AssetId assetId) {
        var located = persistence.locate(assetId);
        if (located instanceof AssetPersistence.LocateNotFound) {
            return new CurrentNotFound();
        }
        if (located instanceof AssetPersistence.LocateUnavailable) {
            return new CurrentPersistenceUnavailable();
        }
        var metadata = ((AssetPersistence.Located) located).metadata();
        if (metadata.lifecycle() == AssetApplication.Lifecycle.DELETED) {
            return new CurrentDeleted();
        }
        var decision = ownerScopeAuthority.authorizeExisting(
                invocation,
                metadata.ownerScope(),
                AssetOwnerScopeAuthority.AssetOperation.READ
        );
        if (decision instanceof AssetOwnerScopeAuthority.ExistingHidden) {
            return new CurrentNotFound();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingForbidden) {
            return new CurrentForbidden();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingUnavailable) {
            return new CurrentAuthorityUnavailable();
        }
        return loadCurrentDetail(metadata.assetId());
    }

    @Override
    public UpdateOutcome updateMetadata(InvocationRef invocation, UpdateMetadataCommand command) {
        var located = persistence.locate(command.assetId());
        if (located instanceof AssetPersistence.LocateNotFound) {
            return new UpdateNotFound();
        }
        if (located instanceof AssetPersistence.LocateUnavailable) {
            return new UpdatePersistenceUnavailable();
        }
        var metadata = ((AssetPersistence.Located) located).metadata();
        if (metadata.lifecycle() == AssetApplication.Lifecycle.DELETED) {
            return new UpdateDeleted();
        }
        var decision = ownerScopeAuthority.authorizeExisting(
                invocation,
                metadata.ownerScope(),
                AssetOwnerScopeAuthority.AssetOperation.UPDATE
        );
        if (decision instanceof AssetOwnerScopeAuthority.ExistingHidden) {
            return new UpdateNotFound();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingForbidden) {
            return new UpdateForbidden();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingUnavailable) {
            return new UpdateAuthorityUnavailable();
        }

        String displayName = normalizeDisplayName(command.displayName());
        List<String> tags = normalizeTags(command.tags());
        if (displayName.equals(metadata.displayName()) && tags.equals(metadata.tags())) {
            return new UpdatedReadable(detailFrom(metadata));
        }

        var recheck = ownerScopeAuthority.recheck(
                ((AssetOwnerScopeAuthority.ExistingGranted) decision).recheckIdentity()
        );
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckDenied) {
            return new UpdateForbidden();
        }
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckUnavailable) {
            return new UpdateAuthorityUnavailable();
        }

        var updated = persistence.updateMetadata(new MetadataUpdateCommitImpl(
                command.assetId(),
                metadata.ownerScope(),
                command.expectedAssetRevision(),
                displayName,
                tags,
                invocation.value()
        ));
        if (updated instanceof AssetPersistence.UpdateNotFound) {
            return new UpdateNotFound();
        }
        if (updated instanceof AssetPersistence.UpdateDeleted) {
            return new UpdateDeleted();
        }
        if (updated instanceof AssetPersistence.UpdateRevisionConflict conflict) {
            return new UpdateRevisionConflict(conflict.currentAssetRevision());
        }
        if (updated instanceof AssetPersistence.UpdateUnavailable) {
            return new UpdatePersistenceUnavailable();
        }
        var refreshed = loadCurrentDetail(command.assetId());
        if (refreshed instanceof CurrentReadable readable) {
            return new UpdatedReadable(readable.detail());
        }
        return new UpdatePersistenceUnavailable();
    }

    @Override
    public CatalogOutcome catalog(InvocationRef invocation, CatalogCommand command) {
        var decision = ownerScopeAuthority.authorizeCatalog(invocation);
        if (decision instanceof AssetOwnerScopeAuthority.CatalogForbidden) {
            return new CatalogForbidden();
        }
        if (decision instanceof AssetOwnerScopeAuthority.CatalogUnavailable) {
            return new CatalogAuthorityUnavailable();
        }
        var granted = (AssetOwnerScopeAuthority.CatalogGranted) decision;
        var query = new AssetPersistence.CatalogQuery(
                granted.ownerScope(),
                command.kind(),
                command.tagsAll(),
                command.tagsAny(),
                command.displayNameContains(),
                command.sourceFileNameContains(),
                command.includeDeleted(),
                command.cursor(),
                command.limit()
        );
        var outcome = persistence.catalog(query);
        if (outcome instanceof AssetPersistence.CatalogUnavailable) {
            return new CatalogPersistenceUnavailable();
        }
        var page = (AssetPersistence.CatalogPage) outcome;
        List<CatalogEntry> entries = new ArrayList<>();
        for (AssetPersistence.CatalogEntry entry : page.entries()) {
            entries.add(new CatalogEntry(
                    entry.assetId(),
                    entry.kind(),
                    entry.lifecycle(),
                    entry.displayName(),
                    entry.tags(),
                    entry.sourceFileName(),
                    entry.updatedAt()
            ));
        }
        return new CatalogPage(entries, page.nextCursor());
    }

    @Override
    public VersionsOutcome listContentVersions(InvocationRef invocation, AssetId assetId) {
        var located = persistence.locate(assetId);
        if (located instanceof AssetPersistence.LocateNotFound) {
            return new VersionsNotFound();
        }
        if (located instanceof AssetPersistence.LocateUnavailable) {
            return new VersionsPersistenceUnavailable();
        }
        var metadata = ((AssetPersistence.Located) located).metadata();
        if (metadata.lifecycle() == AssetApplication.Lifecycle.DELETED) {
            return new VersionsDeleted();
        }
        var decision = ownerScopeAuthority.authorizeExisting(
                invocation,
                metadata.ownerScope(),
                AssetOwnerScopeAuthority.AssetOperation.READ
        );
        if (decision instanceof AssetOwnerScopeAuthority.ExistingHidden) {
            return new VersionsNotFound();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingForbidden) {
            return new VersionsForbidden();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingUnavailable) {
            return new VersionsAuthorityUnavailable();
        }
        var outcome = persistence.listContentVersions(assetId);
        if (outcome instanceof AssetPersistence.VersionsNotFound) {
            return new VersionsNotFound();
        }
        if (outcome instanceof AssetPersistence.VersionsUnavailable) {
            return new VersionsPersistenceUnavailable();
        }
        List<ContentVersionEntry> entries = new ArrayList<>();
        for (AssetPersistence.ContentVersionEntry entry
                : ((AssetPersistence.VersionsListed) outcome).entries()) {
            entries.add(new ContentVersionEntry(
                    entry.contentVersion(),
                    entry.sha256(),
                    entry.mediaType(),
                    entry.byteLength(),
                    entry.sourceFileName(),
                    entry.createdAt()
            ));
        }
        return new VersionsReadable(entries);
    }

    @Override
    public DownloadOutcome downloadExact(InvocationRef invocation, AssetId assetId, long contentVersion) {
        if (contentVersion < 0) {
            throw new IllegalArgumentException("contentVersion must not be negative");
        }
        var located = persistence.locate(assetId);
        if (located instanceof AssetPersistence.LocateNotFound) {
            return new DownloadNotFound();
        }
        if (located instanceof AssetPersistence.LocateUnavailable) {
            return new DownloadPersistenceUnavailable();
        }
        var metadata = ((AssetPersistence.Located) located).metadata();
        if (metadata.lifecycle() == AssetApplication.Lifecycle.DELETED) {
            return new DownloadDeleted();
        }
        var decision = ownerScopeAuthority.authorizeExisting(
                invocation,
                metadata.ownerScope(),
                AssetOwnerScopeAuthority.AssetOperation.READ
        );
        if (decision instanceof AssetOwnerScopeAuthority.ExistingHidden) {
            return new DownloadNotFound();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingForbidden) {
            return new DownloadForbidden();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingUnavailable) {
            return new DownloadAuthorityUnavailable();
        }
        var versions = persistence.listContentVersions(assetId);
        if (versions instanceof AssetPersistence.VersionsNotFound) {
            return new DownloadNotFound();
        }
        if (versions instanceof AssetPersistence.VersionsUnavailable) {
            return new DownloadPersistenceUnavailable();
        }
        String sha256 = null;
        String mediaType = null;
        long byteLength = -1;
        String sourceFileName = null;
        for (AssetPersistence.ContentVersionEntry entry
                : ((AssetPersistence.VersionsListed) versions).entries()) {
            if (entry.contentVersion() == contentVersion) {
                sha256 = entry.sha256();
                mediaType = entry.mediaType();
                byteLength = entry.byteLength();
                sourceFileName = entry.sourceFileName();
                break;
            }
        }
        if (sha256 == null) {
            return new DownloadVersionNotFound();
        }
        var loaded = blobs.load(metadata.ownerScope().value(), sha256);
        if (loaded instanceof AssetBlobPersistence.LoadNotFound
                || loaded instanceof AssetBlobPersistence.LoadUnavailable) {
            return new DownloadBlobUnavailable();
        }
        byte[] bytes = ((AssetBlobPersistence.Loaded) loaded).bytes();
        if (bytes.length != byteLength || !sha256.equals(sha256Hex(bytes))) {
            return new DownloadBlobUnavailable();
        }
        return new DownloadReadable(new DownloadedContent(
                mediaType,
                sourceFileName,
                sha256,
                byteLength,
                bytes
        ));
    }

    @Override
    public ReplaceOutcome replaceContent(InvocationRef invocation, ReplaceContentCommand command) {
        var located = persistence.locate(command.assetId());
        if (located instanceof AssetPersistence.LocateNotFound) {
            return new ReplaceNotFound();
        }
        if (located instanceof AssetPersistence.LocateUnavailable) {
            return new ReplacePersistenceUnavailable();
        }
        var metadata = ((AssetPersistence.Located) located).metadata();
        if (metadata.lifecycle() == AssetApplication.Lifecycle.DELETED) {
            return new ReplaceDeleted();
        }
        var decision = ownerScopeAuthority.authorizeExisting(
                invocation,
                metadata.ownerScope(),
                AssetOwnerScopeAuthority.AssetOperation.UPDATE
        );
        if (decision instanceof AssetOwnerScopeAuthority.ExistingHidden) {
            return new ReplaceNotFound();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingForbidden) {
            return new ReplaceForbidden();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingUnavailable) {
            return new ReplaceAuthorityUnavailable();
        }

        var admission = acceptance.admit(command.rawContent(), metadata.kind());
        if (admission instanceof AssetAcceptanceAuthority.Rejected rejected) {
            return new ReplaceContentRejected(rejected);
        }
        var admitted = (AssetAcceptanceAuthority.Admitted) admission;

        var current = loadCurrentDetail(command.assetId());
        if (current instanceof CurrentReadable readable
                && readable.detail().sha256().equals(admitted.sha256())) {
            return new ReplaceNoOp(readable.detail());
        }
        if (current instanceof CurrentNotFound) {
            return new ReplaceNotFound();
        }
        if (current instanceof CurrentPersistenceUnavailable) {
            return new ReplacePersistenceUnavailable();
        }

        var recheck = ownerScopeAuthority.recheck(
                ((AssetOwnerScopeAuthority.ExistingGranted) decision).recheckIdentity()
        );
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckDenied) {
            return new ReplaceForbidden();
        }
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckUnavailable) {
            return new ReplaceAuthorityUnavailable();
        }

        var stored = blobs.store(metadata.ownerScope().value(), admitted.sha256(), command.rawContent());
        if (stored instanceof AssetBlobPersistence.StoreUnavailable) {
            return new ReplacePersistenceUnavailable();
        }
        boolean blobCreated = ((AssetBlobPersistence.Stored) stored).created();

        if (blobCreated) {
            var capacity = persistence.capacity();
            if (capacity instanceof AssetPersistence.CapacityUnavailable) {
                return new ReplacePersistenceUnavailable();
            }
            var capacityValue = (AssetPersistence.Capacity) capacity;
            if (capacityValue.usedBytes() + admitted.byteLength() > capacityValue.hardLimitBytes()) {
                return new ReplaceStorageCapacityExceeded();
            }
        }

        var appended = persistence.appendContent(new AppendCommitImpl(
                command.assetId(),
                metadata.ownerScope(),
                command.expectedAssetRevision(),
                metadata.currentContentVersion() + 1,
                admitted.sha256(),
                mediaTypeOf(command.rawContent(), metadata.kind()),
                admitted.byteLength(),
                metadata.sourceFileName(),
                admitted.descriptor(),
                blobCreated,
                AssetPersistence.AuditOperation.CONTENT_REPLACE,
                invocation.value()
        ));
        if (appended instanceof AssetPersistence.AppendNotFound) {
            return new ReplaceNotFound();
        }
        if (appended instanceof AssetPersistence.AppendDeleted) {
            return new ReplaceDeleted();
        }
        if (appended instanceof AssetPersistence.AppendRevisionConflict conflict) {
            return new ReplaceRevisionConflict(conflict.currentAssetRevision());
        }
        if (appended instanceof AssetPersistence.AppendStorageCapacityExceeded) {
            return new ReplaceStorageCapacityExceeded();
        }
        if (appended instanceof AssetPersistence.AppendUnavailable) {
            return new ReplacePersistenceUnavailable();
        }
        var refreshed = loadCurrentDetail(command.assetId());
        if (refreshed instanceof CurrentReadable readable) {
            return new ReplaceApplied(readable.detail());
        }
        return new ReplacePersistenceUnavailable();
    }

    @Override
    public RestoreOutcome restoreContent(InvocationRef invocation, RestoreContentCommand command) {
        var located = persistence.locate(command.assetId());
        if (located instanceof AssetPersistence.LocateNotFound) {
            return new RestoreNotFound();
        }
        if (located instanceof AssetPersistence.LocateUnavailable) {
            return new RestorePersistenceUnavailable();
        }
        var metadata = ((AssetPersistence.Located) located).metadata();
        if (metadata.lifecycle() == AssetApplication.Lifecycle.DELETED) {
            return new RestoreDeleted();
        }
        var decision = ownerScopeAuthority.authorizeExisting(
                invocation,
                metadata.ownerScope(),
                AssetOwnerScopeAuthority.AssetOperation.UPDATE
        );
        if (decision instanceof AssetOwnerScopeAuthority.ExistingHidden) {
            return new RestoreNotFound();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingForbidden) {
            return new RestoreForbidden();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingUnavailable) {
            return new RestoreAuthorityUnavailable();
        }

        if (command.sourceContentVersion() == metadata.currentContentVersion()) {
            var current = loadCurrentDetail(command.assetId());
            if (current instanceof CurrentReadable readable) {
                return new RestoreNoOp(readable.detail());
            }
            if (current instanceof CurrentNotFound) {
                return new RestoreNotFound();
            }
            return new RestorePersistenceUnavailable();
        }

        var source = persistence.loadContentVersion(command.assetId(), command.sourceContentVersion());
        if (source instanceof AssetPersistence.ContentVersionNotFound) {
            return new RestoreVersionNotFound();
        }
        if (source instanceof AssetPersistence.ContentVersionUnavailable) {
            return new RestorePersistenceUnavailable();
        }
        var sourceContent = ((AssetPersistence.ContentVersionLoaded) source).content();

        var recheck = ownerScopeAuthority.recheck(
                ((AssetOwnerScopeAuthority.ExistingGranted) decision).recheckIdentity()
        );
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckDenied) {
            return new RestoreForbidden();
        }
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckUnavailable) {
            return new RestoreAuthorityUnavailable();
        }

        var appended = persistence.appendContent(new AppendCommitImpl(
                command.assetId(),
                metadata.ownerScope(),
                command.expectedAssetRevision(),
                metadata.currentContentVersion() + 1,
                sourceContent.sha256(),
                sourceContent.mediaType(),
                sourceContent.byteLength(),
                sourceContent.sourceFileName(),
                sourceContent.descriptor(),
                false,
                AssetPersistence.AuditOperation.CONTENT_RESTORE,
                invocation.value()
        ));
        if (appended instanceof AssetPersistence.AppendNotFound) {
            return new RestoreNotFound();
        }
        if (appended instanceof AssetPersistence.AppendDeleted) {
            return new RestoreDeleted();
        }
        if (appended instanceof AssetPersistence.AppendRevisionConflict conflict) {
            return new RestoreRevisionConflict(conflict.currentAssetRevision());
        }
        if (appended instanceof AssetPersistence.AppendStorageCapacityExceeded) {
            return new RestorePersistenceUnavailable();
        }
        if (appended instanceof AssetPersistence.AppendUnavailable) {
            return new RestorePersistenceUnavailable();
        }
        var refreshed = loadCurrentDetail(command.assetId());
        if (refreshed instanceof CurrentReadable readable) {
            return new RestoreApplied(readable.detail());
        }
        return new RestorePersistenceUnavailable();
    }

    @Override
    public DeletePrecheckOutcome deletePrecheck(InvocationRef invocation, AssetId assetId) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(assetId, "assetId");
        var located = persistence.locate(assetId);
        if (located instanceof AssetPersistence.LocateNotFound) {
            return new DeletePrecheckNotFound();
        }
        if (located instanceof AssetPersistence.LocateUnavailable) {
            return new DeletePrecheckPersistenceUnavailable();
        }
        var metadata = ((AssetPersistence.Located) located).metadata();
        if (metadata.lifecycle() == AssetApplication.Lifecycle.DELETED) {
            return new DeletePrecheckDeleted();
        }
        var decision = ownerScopeAuthority.authorizeExisting(
                invocation,
                metadata.ownerScope(),
                AssetOwnerScopeAuthority.AssetOperation.DELETE
        );
        if (decision instanceof AssetOwnerScopeAuthority.ExistingHidden) {
            return new DeletePrecheckNotFound();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingForbidden) {
            return new DeletePrecheckForbidden();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingUnavailable) {
            return new DeletePrecheckAuthorityUnavailable();
        }
        var recheck = ownerScopeAuthority.recheck(
                ((AssetOwnerScopeAuthority.ExistingGranted) decision).recheckIdentity()
        );
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckDenied) {
            return new DeletePrecheckForbidden();
        }
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckUnavailable) {
            return new DeletePrecheckAuthorityUnavailable();
        }

        var references = referencePort.references(invocation, assetId);
        if (references instanceof AssetReferencePort.ReferencesUnavailable) {
            return new DeletePrecheckDependencyUnavailable();
        }
        var proof = ((AssetReferencePort.ReferencesReadable) references).proof();
        var impact = new DeleteImpactReport(
                proof.totalCount(),
                proof.readableTemplateIds(),
                proof.redactedCount()
        );

        var token = new ConfirmationToken(randomToken());
        var expiresAt = Instant.now().plus(CONFIRMATION_TTL);
        var issued = persistence.issueDeleteConfirmation(new IssueDeleteConfirmationCommitImpl(
                token,
                metadata.ownerScope(),
                assetId,
                ((AssetOwnerScopeAuthority.ExistingGranted) decision).actorId(),
                metadata.assetRevision(),
                proof.referenceFingerprint(),
                expiresAt
        ));
        if (issued instanceof AssetPersistence.ConfirmationUnavailable) {
            return new DeletePrecheckPersistenceUnavailable();
        }
        return new DeletePrecheckReadable(impact, token, expiresAt);
    }

    @Override
    public DeleteOutcome delete(InvocationRef invocation, DeleteCommand command) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(command, "command");
        var located = persistence.locate(command.assetId());
        if (located instanceof AssetPersistence.LocateNotFound) {
            return new DeleteNotFound();
        }
        if (located instanceof AssetPersistence.LocateUnavailable) {
            return new DeletePersistenceUnavailable();
        }
        var metadata = ((AssetPersistence.Located) located).metadata();
        if (metadata.lifecycle() == AssetApplication.Lifecycle.DELETED) {
            return new DeleteDeleted();
        }
        var decision = ownerScopeAuthority.authorizeExisting(
                invocation,
                metadata.ownerScope(),
                AssetOwnerScopeAuthority.AssetOperation.DELETE
        );
        if (decision instanceof AssetOwnerScopeAuthority.ExistingHidden) {
            return new DeleteNotFound();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingForbidden) {
            return new DeleteForbidden();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingUnavailable) {
            return new DeleteAuthorityUnavailable();
        }
        var recheck = ownerScopeAuthority.recheck(
                ((AssetOwnerScopeAuthority.ExistingGranted) decision).recheckIdentity()
        );
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckDenied) {
            return new DeleteForbidden();
        }
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckUnavailable) {
            return new DeleteAuthorityUnavailable();
        }

        var deleted = persistence.delete(new DeleteCommitImpl(
                command.assetId(),
                metadata.ownerScope(),
                command.confirmationToken(),
                ((AssetOwnerScopeAuthority.ExistingGranted) decision).actorId()
        ));
        return switch (deleted) {
            case AssetPersistence.Deleted ignored -> {
                var refreshed = loadCurrentDetail(command.assetId());
                if (refreshed instanceof CurrentReadable readable) {
                    yield new DeleteApplied(readable.detail());
                }
                yield new DeletePersistenceUnavailable();
            }
            case AssetPersistence.DeleteNotFound ignored -> new DeleteNotFound();
            case AssetPersistence.DeleteDeleted ignored -> new DeleteDeleted();
            case AssetPersistence.DeleteConfirmationRequired ignored ->
                    new DeleteConfirmationRequired();
            case AssetPersistence.DeleteConfirmationExpired ignored ->
                    new DeleteConfirmationExpired();
            case AssetPersistence.DeleteConfirmationStale ignored -> new DeleteConfirmationStale();
            case AssetPersistence.DeleteDependencyUnavailable ignored ->
                    new DeleteDependencyUnavailable();
            case AssetPersistence.DeleteUnavailable ignored -> new DeletePersistenceUnavailable();
        };
    }

    @Override
    public RestoreLifecycleOutcome restore(InvocationRef invocation, RestoreLifecycleCommand command) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(command, "command");
        var located = persistence.locate(command.assetId());
        if (located instanceof AssetPersistence.LocateNotFound) {
            return new RestoreLifecycleNotFound();
        }
        if (located instanceof AssetPersistence.LocateUnavailable) {
            return new RestoreLifecyclePersistenceUnavailable();
        }
        var metadata = ((AssetPersistence.Located) located).metadata();
        if (metadata.lifecycle() == AssetApplication.Lifecycle.ACTIVE) {
            return new RestoreLifecycleActive();
        }
        var decision = ownerScopeAuthority.authorizeExisting(
                invocation,
                metadata.ownerScope(),
                AssetOwnerScopeAuthority.AssetOperation.RESTORE
        );
        if (decision instanceof AssetOwnerScopeAuthority.ExistingHidden) {
            return new RestoreLifecycleNotFound();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingForbidden) {
            return new RestoreLifecycleForbidden();
        }
        if (decision instanceof AssetOwnerScopeAuthority.ExistingUnavailable) {
            return new RestoreLifecycleAuthorityUnavailable();
        }
        var recheck = ownerScopeAuthority.recheck(
                ((AssetOwnerScopeAuthority.ExistingGranted) decision).recheckIdentity()
        );
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckDenied) {
            return new RestoreLifecycleForbidden();
        }
        if (recheck instanceof AssetOwnerScopeAuthority.RecheckUnavailable) {
            return new RestoreLifecycleAuthorityUnavailable();
        }

        var restored = persistence.restore(new RestoreLifecycleCommitImpl(
                command.assetId(),
                metadata.ownerScope(),
                command.expectedAssetRevision(),
                ((AssetOwnerScopeAuthority.ExistingGranted) decision).actorId()
        ));
        return switch (restored) {
            case AssetPersistence.Restored ignored -> {
                var refreshed = loadCurrentDetail(command.assetId());
                if (refreshed instanceof CurrentReadable readable) {
                    yield new RestoreLifecycleApplied(readable.detail());
                }
                yield new RestoreLifecyclePersistenceUnavailable();
            }
            case AssetPersistence.RestoreNotFound ignored -> new RestoreLifecycleNotFound();
            case AssetPersistence.RestoreActive ignored -> new RestoreLifecycleActive();
            case AssetPersistence.RestoreRevisionConflict conflict ->
                    new RestoreLifecycleRevisionConflict(conflict.currentAssetRevision());
            case AssetPersistence.RestoreUnavailable ignored ->
                    new RestoreLifecyclePersistenceUnavailable();
        };
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private CreateOutcome replayResult(
            InvocationRef invocation,
            AssetApplication.OwnerScope scope,
            AssetId assetId
    ) {
        var located = persistence.locate(assetId);
        if (located instanceof AssetPersistence.LocateNotFound) {
            return new CreatePersistenceUnavailable();
        }
        if (located instanceof AssetPersistence.LocateUnavailable) {
            return new CreatePersistenceUnavailable();
        }
        var decision = ownerScopeAuthority.authorizeExisting(
                invocation,
                ((AssetPersistence.Located) located).metadata().ownerScope(),
                AssetOwnerScopeAuthority.AssetOperation.READ
        );
        if (decision instanceof AssetOwnerScopeAuthority.ExistingGranted granted
                && granted.disclosure() == AssetOwnerScopeAuthority.Disclosure.READABLE) {
            var readable = loadCurrentDetail(assetId);
            if (readable instanceof CurrentReadable current) {
                return new CreatedReadable(current.detail());
            }
        }
        return new CreatedOpaque(assetId);
    }

    private CurrentOutcome loadCurrentDetail(AssetId assetId) {
        var loaded = persistence.loadCurrent(assetId);
        if (loaded instanceof AssetPersistence.CurrentNotFound) {
            return new CurrentNotFound();
        }
        if (loaded instanceof AssetPersistence.CurrentLoadUnavailable) {
            return new CurrentPersistenceUnavailable();
        }
        var current = ((AssetPersistence.CurrentLoaded) loaded).current();
        return new CurrentReadable(detail(
                current.metadata(),
                current.content()
        ));
    }

    private AssetDetail detailFrom(AssetPersistence.AssetMetadata metadata) {
        var loaded = persistence.loadCurrent(metadata.assetId());
        if (loaded instanceof AssetPersistence.CurrentNotFound
                || loaded instanceof AssetPersistence.CurrentLoadUnavailable) {
            throw new IllegalStateException("current content disappeared after metadata read");
        }
        var current = ((AssetPersistence.CurrentLoaded) loaded).current();
        return detail(current.metadata(), current.content());
    }

    private static AssetDetail detail(
            AssetPersistence.AssetMetadata metadata,
            AssetPersistence.StoredContent content
    ) {
        return new AssetDetail(
                metadata.assetId(),
                metadata.ownerScope(),
                metadata.kind(),
                metadata.lifecycle(),
                metadata.assetRevision(),
                content.contentVersion(),
                metadata.displayName(),
                metadata.tags(),
                content.sourceFileName(),
                content.mediaType(),
                content.byteLength(),
                content.sha256(),
                content.descriptor(),
                metadata.createdAt(),
                metadata.updatedAt()
        );
    }

    private static AssetDetail detail(AdmittedAssetCreateCommit commit, java.time.Instant now) {
        return new AssetDetail(
                commit.assetId(),
                commit.ownerScope(),
                commit.kind(),
                AssetApplication.Lifecycle.ACTIVE,
                0,
                0,
                commit.displayName(),
                commit.tags(),
                commit.sourceFileName(),
                commit.mediaType(),
                commit.byteLength(),
                commit.sha256(),
                commit.descriptor(),
                now,
                now
        );
    }

    private static String normalizeDisplayName(String displayName) {
        String normalized = Normalizer.normalize(displayName.strip(), Normalizer.Form.NFC);
        if (normalized.codePointCount(0, normalized.length()) < 1
                || normalized.codePointCount(0, normalized.length()) > 200) {
            throw new IllegalArgumentException(
                    "displayName must be 1-200 Unicode scalars after NFC"
            );
        }
        return normalized;
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags.size() > 20) {
            throw new IllegalArgumentException("at most 20 tags");
        }
        var seen = new LinkedHashSet<String>();
        for (String tag : tags) {
            String normalized = Normalizer.normalize(tag.strip(), Normalizer.Form.NFC);
            if (normalized.codePointCount(0, normalized.length()) < 1
                    || normalized.codePointCount(0, normalized.length()) > 64) {
                throw new IllegalArgumentException("each tag must be 1-64 Unicode scalars after NFC");
            }
            seen.add(normalized.toLowerCase(Locale.ROOT));
        }
        List<String> result = new ArrayList<>();
        for (String tag : tags) {
            String normalized = Normalizer.normalize(tag.strip(), Normalizer.Form.NFC);
            String folded = normalized.toLowerCase(Locale.ROOT);
            if (seen.contains(folded)) {
                result.add(normalized);
                seen.remove(folded);
            }
        }
        return List.copyOf(result);
    }

    private static String normalizeSourceFileName(String sourceFileName) {
        if (sourceFileName == null) {
            return null;
        }
        String baseName = sourceFileName;
        int lastSeparator = Math.max(
                baseName.lastIndexOf('/'),
                baseName.lastIndexOf('\\')
        );
        if (lastSeparator >= 0) {
            baseName = baseName.substring(lastSeparator + 1);
        }
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < baseName.length(); i++) {
            char c = baseName.charAt(i);
            if (Character.isISOControl(c)) {
                continue;
            }
            cleaned.append(c);
        }
        String result = cleaned.toString();
        if (result.isEmpty() || result.isBlank()) {
            throw new IllegalArgumentException("sourceFileName must contain a base name");
        }
        if (result.codePointCount(0, result.length()) > 255) {
            throw new IllegalArgumentException("sourceFileName must be at most 255 Unicode scalars");
        }
        return result;
    }

    private static String fingerprint(
            AssetApplication.OwnerScope scope,
            String idempotencyKey,
            AssetAcceptanceAuthority.AssetKind kind,
            String displayName,
            List<String> tags,
            String sourceFileName,
            byte[] rawContent
    ) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(scope.value().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(kind.name().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(displayName.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (String tag : tags) {
                digest.update(tag.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1F);
            }
            digest.update((byte) 0);
            if (sourceFileName != null) {
                digest.update(sourceFileName.getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) 0);
            digest.update(rawContent);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String mediaTypeOf(
            byte[] raw,
            AssetAcceptanceAuthority.AssetKind kind
    ) {
        if (kind == AssetAcceptanceAuthority.AssetKind.FONT) {
            return raw.length >= 4 && raw[0] == 'O' && raw[1] == 'T' && raw[2] == 'T' && raw[3] == 'O'
                    ? "font/otf"
                    : "font/ttf";
        }
        if (raw.length >= 8 && raw[0] == (byte) 0x89 && raw[1] == 'P' && raw[2] == 'N' && raw[3] == 'G') {
            return "image/png";
        }
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        return "image/webp";
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record AdmittedAssetCreateCommit(
            AssetId assetId,
            AssetApplication.OwnerScope ownerScope,
            AssetAcceptanceAuthority.AssetKind kind,
            String displayName,
            List<String> tags,
            String sourceFileName,
            long assetRevision,
            long contentVersion,
            String sha256,
            String mediaType,
            long byteLength,
            AssetAcceptanceAuthority.TechnicalDescriptor descriptor,
            String idempotencyKey,
            String idempotencyFingerprint,
            boolean blobCreated,
            String actorId
    ) implements AssetPersistence.CreateCommit {
    }

    private record IdempotencyQueryImpl(
            AssetApplication.OwnerScope ownerScope,
            String idempotencyKey,
            String fingerprint
    ) implements AssetPersistence.IdempotencyQuery {
    }

    private record MetadataUpdateCommitImpl(
            AssetId assetId,
            AssetApplication.OwnerScope ownerScope,
            long expectedAssetRevision,
            String displayName,
            List<String> tags,
            String actorId
    ) implements AssetPersistence.UpdateMetadataCommit {
    }

    private record AppendCommitImpl(
            AssetId assetId,
            AssetApplication.OwnerScope ownerScope,
            long expectedAssetRevision,
            long contentVersion,
            String sha256,
            String mediaType,
            long byteLength,
            String sourceFileName,
            AssetAcceptanceAuthority.TechnicalDescriptor descriptor,
            boolean blobCreated,
            AssetPersistence.AuditOperation operation,
            String actorId
    ) implements AssetPersistence.AppendContentCommit {
    }

    private record IssueDeleteConfirmationCommitImpl(
            ConfirmationToken token,
            AssetApplication.OwnerScope ownerScope,
            AssetId assetId,
            String actorId,
            long assetRevision,
            String referenceFingerprint,
            Instant expiresAt
    ) implements AssetPersistence.IssueDeleteConfirmationCommit {
        @Override
        public String confirmationToken() {
            return token.value();
        }
    }

    private record DeleteCommitImpl(
            AssetId assetId,
            AssetApplication.OwnerScope ownerScope,
            ConfirmationToken token,
            String actorId
    ) implements AssetPersistence.DeleteCommit {
        @Override
        public String confirmationToken() {
            return token.value();
        }
    }

    private record RestoreLifecycleCommitImpl(
            AssetId assetId,
            AssetApplication.OwnerScope ownerScope,
            long expectedAssetRevision,
            String actorId
    ) implements AssetPersistence.RestoreLifecycleCommit {
    }
}
