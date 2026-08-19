package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetBlobPersistence;
import cn.hbads.renderweave.asset.spi.AssetOwnerScopeAuthority;
import cn.hbads.renderweave.asset.spi.AssetPersistence;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetApplicationContractTest {

    private final ScriptedOwnerScopeAuthority authority = new ScriptedOwnerScopeAuthority();
    private final InMemoryAssetPersistence persistence = new InMemoryAssetPersistence();
    private final InMemoryBlobs blobs = new InMemoryBlobs();
    private final AssetApplication application = AssetModule.application(authority, persistence, blobs);

    private static byte[] jpegFixture() {
        try (var stream = AssetApplicationContractTest.class.getResourceAsStream(
                "/asset-fixtures/grayscale-baseline.jpg")) {
            assertTrue(stream != null);
            return stream.readAllBytes();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private AssetApplication.CreateCommand createCommand(byte[] content, String key) {
        return new AssetApplication.CreateCommand(
                key,
                AssetAcceptanceAuthority.AssetKind.IMAGE,
                "Asset One",
                List.of(),
                "photo.jpg",
                content
        );
    }

    @Test
    void createAdmitsContentAndReturnsReadableDetail() {
        authority.createDecision = new AssetOwnerScopeAuthority.CreateGranted(
                new AssetApplication.OwnerScope("scope-1"),
                new AssetOwnerScopeAuthority.RecheckIdentity("recheck-1"),
                AssetOwnerScopeAuthority.Disclosure.READABLE
        );
        authority.recheckDecision = new AssetOwnerScopeAuthority.RecheckGranted();

        var outcome = application.create(
                AssetApplication.InvocationRef.serverCreated("inv-1"),
                createCommand(jpegFixture(), "key-1")
        );
        var created = assertInstanceOf(AssetApplication.CreatedReadable.class, outcome);
        assertEquals("Asset One", created.detail().displayName());
        assertEquals("scope-1", created.detail().ownerScope().value());
        assertEquals(0, created.detail().assetRevision());
        assertEquals(0, created.detail().currentContentVersion());
        assertEquals("image/jpeg", created.detail().mediaType());
        assertEquals("photo.jpg", created.detail().sourceFileName());
        assertEquals(1, persistence.commitCount);
    }

    @Test
    void createRejectsInvalidContent() {
        authority.createDecision = granted();
        var outcome = application.create(
                AssetApplication.InvocationRef.serverCreated("inv-1"),
                createCommand(new byte[]{0x01, 0x02, 0x03}, "key-1")
        );
        assertInstanceOf(AssetApplication.CreateContentRejected.class, outcome);
        assertEquals(0, persistence.commitCount);
    }

    @Test
    void createDeniedByAuthorityIsForbidden() {
        authority.createDecision = new AssetOwnerScopeAuthority.CreateDenied();
        var outcome = application.create(
                AssetApplication.InvocationRef.serverCreated("inv-1"),
                createCommand(jpegFixture(), "key-1")
        );
        assertInstanceOf(AssetApplication.CreateForbidden.class, outcome);
    }

    @Test
    void createReplaysSameIdempotentRequest() {
        authority.createDecision = granted();
        authority.recheckDecision = new AssetOwnerScopeAuthority.RecheckGranted();
        authority.existingDecision = new AssetOwnerScopeAuthority.ExistingGranted(
                AssetOwnerScopeAuthority.Disclosure.READABLE,
                new AssetOwnerScopeAuthority.RecheckIdentity("recheck-1")
        );
        var invocation = AssetApplication.InvocationRef.serverCreated("inv-1");
        var command = createCommand(jpegFixture(), "key-1");

        var first = application.create(invocation, command);
        var firstCreated = assertInstanceOf(AssetApplication.CreatedReadable.class, first);
        var second = application.create(invocation, command);
        var secondCreated = assertInstanceOf(AssetApplication.CreatedReadable.class, second);

        assertEquals(firstCreated.detail().assetId(), secondCreated.detail().assetId());
        assertEquals(1, persistence.commitCount);
    }

    @Test
    void createConflictsWhenSameKeyCarriesDifferentInput() {
        authority.createDecision = granted();
        authority.recheckDecision = new AssetOwnerScopeAuthority.RecheckGranted();
        var invocation = AssetApplication.InvocationRef.serverCreated("inv-1");
        var first = application.create(invocation, createCommand(jpegFixture(), "key-1"));
        assertInstanceOf(AssetApplication.CreatedReadable.class, first);

        var conflict = application.create(
                invocation,
                new AssetApplication.CreateCommand(
                        "key-1",
                        AssetAcceptanceAuthority.AssetKind.IMAGE,
                        "Different Name",
                        List.of(),
                        "photo.jpg",
                        jpegFixture()
                )
        );
        assertInstanceOf(AssetApplication.CreateIdempotencyConflict.class, conflict);
    }

    @Test
    void createReturnsOpaqueReceiptWhenDisclosureIsOpaque() {
        authority.createDecision = new AssetOwnerScopeAuthority.CreateGranted(
                new AssetApplication.OwnerScope("scope-1"),
                new AssetOwnerScopeAuthority.RecheckIdentity("recheck-1"),
                AssetOwnerScopeAuthority.Disclosure.OPAQUE
        );
        authority.recheckDecision = new AssetOwnerScopeAuthority.RecheckGranted();
        var outcome = application.create(
                AssetApplication.InvocationRef.serverCreated("inv-1"),
                createCommand(jpegFixture(), "key-1")
        );
        assertInstanceOf(AssetApplication.CreatedOpaque.class, outcome);
    }

    @Test
    void createFailsClosedWhenCapacityWatermarkIsCrossed() {
        authority.createDecision = granted();
        authority.recheckDecision = new AssetOwnerScopeAuthority.RecheckGranted();
        persistence.hardLimitBytes = 1;
        var outcome = application.create(
                AssetApplication.InvocationRef.serverCreated("inv-1"),
                createCommand(jpegFixture(), "key-1")
        );
        assertInstanceOf(AssetApplication.CreateStorageCapacityExceeded.class, outcome);
        assertEquals(0, persistence.commitCount);
    }

    @Test
    void getCurrentReadsCreatedAsset() {
        authority.createDecision = granted();
        authority.recheckDecision = new AssetOwnerScopeAuthority.RecheckGranted();
        authority.existingDecision = new AssetOwnerScopeAuthority.ExistingGranted(
                AssetOwnerScopeAuthority.Disclosure.READABLE,
                new AssetOwnerScopeAuthority.RecheckIdentity("recheck-1")
        );
        var created = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                application.create(
                        AssetApplication.InvocationRef.serverCreated("inv-1"),
                        createCommand(jpegFixture(), "key-1")
                )
        );
        var current = assertInstanceOf(
                AssetApplication.CurrentReadable.class,
                application.getCurrent(
                        AssetApplication.InvocationRef.serverCreated("inv-2"),
                        created.detail().assetId()
                )
        );
        assertEquals(created.detail().assetId(), current.detail().assetId());
        assertEquals(created.detail().sha256(), current.detail().sha256());
    }

    @Test
    void getCurrentHidesCrossScopeQuery() {
        authority.existingDecision = new AssetOwnerScopeAuthority.ExistingHidden();
        var outcome = application.getCurrent(
                AssetApplication.InvocationRef.serverCreated("inv-1"),
                AssetApplication.AssetId.of("00000000-0000-4000-8000-000000000001")
        );
        assertInstanceOf(AssetApplication.CurrentNotFound.class, outcome);
    }

    @Test
    void updateMetadataAdvancesRevisionAndPreservesNoOp() {
        authority.createDecision = granted();
        authority.recheckDecision = new AssetOwnerScopeAuthority.RecheckGranted();
        authority.existingDecision = new AssetOwnerScopeAuthority.ExistingGranted(
                AssetOwnerScopeAuthority.Disclosure.READABLE,
                new AssetOwnerScopeAuthority.RecheckIdentity("recheck-1")
        );
        var created = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                application.create(
                        AssetApplication.InvocationRef.serverCreated("inv-1"),
                        createCommand(jpegFixture(), "key-1")
                )
        );
        var assetId = created.detail().assetId();
        int commitsBefore = persistence.updateCount;

        var updated = assertInstanceOf(
                AssetApplication.UpdatedReadable.class,
                application.updateMetadata(
                        AssetApplication.InvocationRef.serverCreated("inv-2"),
                        new AssetApplication.UpdateMetadataCommand(
                                assetId, 0, "Renamed", List.of("TagA")
                        )
                )
        );
        assertEquals("Renamed", updated.detail().displayName());
        assertEquals(List.of("TagA"), updated.detail().tags());
        assertEquals(1, updated.detail().assetRevision());
        assertEquals(commitsBefore + 1, persistence.updateCount);

        var noOp = assertInstanceOf(
                AssetApplication.UpdatedReadable.class,
                application.updateMetadata(
                        AssetApplication.InvocationRef.serverCreated("inv-3"),
                        new AssetApplication.UpdateMetadataCommand(
                                assetId, 1, "Renamed", List.of("TagA")
                        )
                )
        );
        assertEquals(1, noOp.detail().assetRevision());
        assertEquals(commitsBefore + 1, persistence.updateCount);
    }

    @Test
    void updateMetadataConflictsOnStaleRevision() {
        authority.createDecision = granted();
        authority.recheckDecision = new AssetOwnerScopeAuthority.RecheckGranted();
        authority.existingDecision = new AssetOwnerScopeAuthority.ExistingGranted(
                AssetOwnerScopeAuthority.Disclosure.READABLE,
                new AssetOwnerScopeAuthority.RecheckIdentity("recheck-1")
        );
        var created = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                application.create(
                        AssetApplication.InvocationRef.serverCreated("inv-0"),
                        createCommand(jpegFixture(), "key-0")
                )
        );
        persistence.putAsset(created.detail().assetId(), 3);
        var outcome = application.updateMetadata(
                AssetApplication.InvocationRef.serverCreated("inv-1"),
                new AssetApplication.UpdateMetadataCommand(
                        created.detail().assetId(),
                        1,
                        "Renamed",
                        List.of()
                )
        );
        var conflict = assertInstanceOf(AssetApplication.UpdateRevisionConflict.class, outcome);
        assertEquals(3, conflict.currentAssetRevision());
    }

    @Test
    void catalogReturnsScopeFilteredPage() {
        authority.createDecision = granted();
        authority.recheckDecision = new AssetOwnerScopeAuthority.RecheckGranted();
        authority.existingDecision = new AssetOwnerScopeAuthority.ExistingGranted(
                AssetOwnerScopeAuthority.Disclosure.READABLE,
                new AssetOwnerScopeAuthority.RecheckIdentity("recheck-1")
        );
        var created = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                application.create(
                        AssetApplication.InvocationRef.serverCreated("inv-1"),
                        createCommand(jpegFixture(), "key-1")
                )
        );
        authority.catalogDecision = new AssetOwnerScopeAuthority.CatalogGranted(
                new AssetApplication.OwnerScope("scope-1")
        );
        var page = assertInstanceOf(
                AssetApplication.CatalogPage.class,
                application.catalog(
                        AssetApplication.InvocationRef.serverCreated("inv-2"),
                        new AssetApplication.CatalogCommand(
                                AssetAcceptanceAuthority.AssetKind.IMAGE,
                                List.of(),
                                List.of(),
                                null,
                                null,
                                false,
                                null,
                                20
                        )
                )
        );
        assertEquals(1, page.entries().size());
        assertEquals(created.detail().assetId(), page.entries().get(0).assetId());
        assertTrue(page.nextCursor().isEmpty());
    }

    @Test
    void listContentVersionsAndDownloadExactWork() {
        authority.createDecision = granted();
        authority.recheckDecision = new AssetOwnerScopeAuthority.RecheckGranted();
        authority.existingDecision = new AssetOwnerScopeAuthority.ExistingGranted(
                AssetOwnerScopeAuthority.Disclosure.READABLE,
                new AssetOwnerScopeAuthority.RecheckIdentity("recheck-1")
        );
        var raw = jpegFixture();
        var created = assertInstanceOf(
                AssetApplication.CreatedReadable.class,
                application.create(
                        AssetApplication.InvocationRef.serverCreated("inv-1"),
                        createCommand(raw, "key-1")
                )
        );
        var assetId = created.detail().assetId();

        var versions = assertInstanceOf(
                AssetApplication.VersionsReadable.class,
                application.listContentVersions(
                        AssetApplication.InvocationRef.serverCreated("inv-2"),
                        assetId
                )
        );
        assertEquals(1, versions.entries().size());
        assertEquals(0, versions.entries().get(0).contentVersion());

        var download = assertInstanceOf(
                AssetApplication.DownloadReadable.class,
                application.downloadExact(
                        AssetApplication.InvocationRef.serverCreated("inv-3"),
                        assetId,
                        0
                )
        );
        assertEquals(created.detail().sha256(), download.content().sha256());
        assertEquals(raw.length, download.content().byteLength());
        assertEquals(raw.length, download.content().bytes().length);

        assertInstanceOf(
                AssetApplication.DownloadVersionNotFound.class,
                application.downloadExact(
                        AssetApplication.InvocationRef.serverCreated("inv-4"),
                        assetId,
                        5
                )
        );
    }

    @Test
    void createNormalizesMetadataBeforeCommit() {
        authority.createDecision = granted();
        authority.recheckDecision = new AssetOwnerScopeAuthority.RecheckGranted();
        var outcome = application.create(
                AssetApplication.InvocationRef.serverCreated("inv-1"),
                new AssetApplication.CreateCommand(
                        "key-1",
                        AssetAcceptanceAuthority.AssetKind.IMAGE,
                        "  Asset One  ",
                        List.of("TagA", "taga", "TAGB"),
                        "dir\\photo.jpg",
                        jpegFixture()
                )
        );
        var created = assertInstanceOf(AssetApplication.CreatedReadable.class, outcome);
        assertEquals("Asset One", created.detail().displayName());
        assertEquals(List.of("TagA", "TAGB"), created.detail().tags());
        assertEquals("photo.jpg", created.detail().sourceFileName());
    }

    private AssetOwnerScopeAuthority.CreateGranted granted() {
        return new AssetOwnerScopeAuthority.CreateGranted(
                new AssetApplication.OwnerScope("scope-1"),
                new AssetOwnerScopeAuthority.RecheckIdentity("recheck-1"),
                AssetOwnerScopeAuthority.Disclosure.READABLE
        );
    }

    private static final class ScriptedOwnerScopeAuthority implements AssetOwnerScopeAuthority {
        CreateDecision createDecision = new CreateDenied();
        ExistingDecision existingDecision = new ExistingHidden();
        CatalogDecision catalogDecision = new CatalogForbidden();
        RecheckDecision recheckDecision = new RecheckDenied();

        @Override
        public CreateDecision authorizeCreate(AssetApplication.InvocationRef invocation) {
            return createDecision;
        }

        @Override
        public ExistingDecision authorizeExisting(
                AssetApplication.InvocationRef invocation,
                AssetApplication.OwnerScope storedOwnerScope,
                AssetOperation operation
        ) {
            return existingDecision;
        }

        @Override
        public CatalogDecision authorizeCatalog(AssetApplication.InvocationRef invocation) {
            return catalogDecision;
        }

        @Override
        public RecheckDecision recheck(RecheckIdentity identity) {
            return recheckDecision;
        }
    }

    private static final class InMemoryBlobs implements AssetBlobPersistence {
        private final Map<String, byte[]> blobs = new HashMap<>();

        @Override
        public StoreOutcome store(String ownerScope, String sha256, byte[] bytes) {
            boolean created = !blobs.containsKey(ownerScope + "/" + sha256);
            blobs.put(ownerScope + "/" + sha256, bytes.clone());
            return new Stored(created);
        }

        @Override
        public LoadOutcome load(String ownerScope, String sha256) {
            byte[] bytes = blobs.get(ownerScope + "/" + sha256);
            return bytes == null ? new LoadNotFound() : new Loaded(bytes);
        }
    }

    private static final class InMemoryAssetPersistence implements AssetPersistence {
        private final Map<AssetApplication.AssetId, StoredCurrent> assets = new LinkedHashMap<>();
        private final Map<String, AssetApplication.AssetId> idempotency = new HashMap<>();
        int commitCount = 0;
        int updateCount = 0;
        long hardLimitBytes = Long.MAX_VALUE;
        long usedBytes = 0;

        void putAsset(AssetApplication.AssetId assetId, long assetRevision) {
            var current = assets.get(assetId);
            assets.put(assetId, new StoredCurrent(
                    new AssetMetadata(
                            assetId,
                            current.metadata().ownerScope(),
                            current.metadata().kind(),
                            AssetApplication.Lifecycle.ACTIVE,
                            assetRevision,
                            current.metadata().currentContentVersion(),
                            current.metadata().displayName(),
                            current.metadata().tags(),
                            current.metadata().sourceFileName(),
                            current.metadata().createdAt(),
                            current.metadata().updatedAt()
                    ),
                    current.content()
            ));
        }

        @Override
        public LocateOutcome locate(AssetApplication.AssetId assetId) {
            var current = assets.get(assetId);
            return current == null ? new LocateNotFound() : new Located(current.metadata());
        }

        @Override
        public LoadCurrentOutcome loadCurrent(AssetApplication.AssetId assetId) {
            var current = assets.get(assetId);
            return current == null ? new CurrentNotFound() : new CurrentLoaded(current);
        }

        @Override
        public CreateOutcome create(CreateCommit commit) {
            commitCount++;
            if (commit.blobCreated() && usedBytes + commit.byteLength() > hardLimitBytes) {
                return new StorageCapacityExceeded();
            }
            var now = Instant.now();
            var metadata = new AssetMetadata(
                    commit.assetId(),
                    commit.ownerScope(),
                    commit.kind(),
                    AssetApplication.Lifecycle.ACTIVE,
                    0,
                    0,
                    commit.displayName(),
                    commit.tags(),
                    commit.sourceFileName(),
                    now,
                    now
            );
            var content = new StoredContent(
                    0,
                    commit.sha256(),
                    commit.mediaType(),
                    commit.byteLength(),
                    commit.sourceFileName(),
                    commit.descriptor(),
                    now
            );
            assets.put(commit.assetId(), new StoredCurrent(metadata, content));
            usedBytes += commit.byteLength();
            idempotency.put(
                    commit.ownerScope().value() + "/" + commit.idempotencyKey(),
                    commit.assetId()
            );
            idempotencyFingerprint.put(
                    commit.ownerScope().value() + "/" + commit.idempotencyKey(),
                    commit.idempotencyFingerprint()
            );
            return new Created();
        }

        @Override
        public UpdateMetadataOutcome updateMetadata(UpdateMetadataCommit commit) {
            var current = assets.get(commit.assetId());
            if (current == null) {
                return new UpdateNotFound();
            }
            if (current.metadata().lifecycle() == AssetApplication.Lifecycle.DELETED) {
                return new UpdateDeleted();
            }
            if (current.metadata().assetRevision() != commit.expectedAssetRevision()) {
                return new UpdateRevisionConflict(current.metadata().assetRevision());
            }
            updateCount++;
            long next = current.metadata().assetRevision() + 1;
            assets.put(commit.assetId(), new StoredCurrent(
                    new AssetMetadata(
                            commit.assetId(),
                            commit.ownerScope(),
                            current.metadata().kind(),
                            current.metadata().lifecycle(),
                            next,
                            current.metadata().currentContentVersion(),
                            commit.displayName(),
                            commit.tags(),
                            current.metadata().sourceFileName(),
                            current.metadata().createdAt(),
                            Instant.now()
                    ),
                    current.content()
            ));
            return new MetadataUpdated(true);
        }

        @Override
        public CatalogOutcome catalog(CatalogQuery query) {
            List<CatalogEntry> entries = new ArrayList<>();
            for (StoredCurrent current : assets.values()) {
                var metadata = current.metadata();
                if (!metadata.ownerScope().equals(query.ownerScope())) {
                    continue;
                }
                if (!query.includeDeleted() && metadata.lifecycle() == AssetApplication.Lifecycle.DELETED) {
                    continue;
                }
                if (query.kind() != null && metadata.kind() != query.kind()) {
                    continue;
                }
                entries.add(new CatalogEntry(
                        metadata.assetId(),
                        metadata.kind(),
                        metadata.lifecycle(),
                        metadata.displayName(),
                        metadata.tags(),
                        metadata.sourceFileName(),
                        metadata.updatedAt()
                ));
            }
            entries.sort((a, b) -> {
                int byUpdated = b.updatedAt().compareTo(a.updatedAt());
                return byUpdated != 0 ? byUpdated : a.assetId().value().compareTo(b.assetId().value());
            });
            if (entries.size() > query.limit()) {
                entries = new ArrayList<>(entries.subList(0, query.limit()));
            }
            return new CatalogPage(entries, Optional.empty());
        }

        @Override
        public VersionsOutcome listContentVersions(AssetApplication.AssetId assetId) {
            var current = assets.get(assetId);
            if (current == null) {
                return new VersionsNotFound();
            }
            return new VersionsListed(List.of(new ContentVersionEntry(
                    current.content().contentVersion(),
                    current.content().sha256(),
                    current.content().mediaType(),
                    current.content().byteLength(),
                    current.content().sourceFileName(),
                    current.content().createdAt()
            )));
        }

        @Override
        public IdempotencyOutcome resolveIdempotency(IdempotencyQuery query) {
            String key = query.ownerScope().value() + "/" + query.idempotencyKey();
            AssetApplication.AssetId existing = idempotency.get(key);
            if (existing == null) {
                return new IdempotencyMiss();
            }
            String storedFingerprint = idempotencyFingerprint.get(key);
            if (storedFingerprint != null && !storedFingerprint.equals(query.fingerprint())) {
                return new IdempotencyConflict();
            }
            return new IdempotencyReplay(existing);
        }

        private final Map<String, String> idempotencyFingerprint = new HashMap<>();

        @Override
        public CapacityOutcome capacity() {
            return new Capacity(hardLimitBytes, usedBytes);
        }
    }
}
