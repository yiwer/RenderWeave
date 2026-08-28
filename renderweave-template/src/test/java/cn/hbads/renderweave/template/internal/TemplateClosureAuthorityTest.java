package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateClosureAuthorityTest {

    private static final int MAX_DESIGN_BYTES = 16 * 1024 * 1024;
    private static final int MAX_CLOSURE_CANONICAL_DESIGN_BYTES = 32 * 1024 * 1024;
    private static final String SCRIPTED_CONTENT_HASH = "sha256:" + "a".repeat(64);
    private static final DesignDslAuthority DESIGNS = new CanonicalDesignDslAuthority();
    private static final DesignDslAuthority SCRIPTED_DESIGNS = rawUtf8 ->
            new DesignDslAuthority.Admitted(rawUtf8, SCRIPTED_CONTENT_HASH);
    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"),
            VersionTag.of("v1")
    );

    private final FakePersistence persistence = new FakePersistence();
    private final TemplateClosureAuthority authority = new CanonicalTemplateClosureAuthority(persistence);

    @Test
    void singleTemplateFreezesWithIntegrityReplay() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        persistence.put(rootId, 3, "owner-a", canvasDoc("Root"));

        var outcome = authority.freezeClosure(request("render-1"), rootId);

        var frozen = assertInstanceOf(TemplateClosureAuthority.ClosureFrozen.class, outcome);
        var closure = frozen.closure();
        assertEquals("owner-a", closure.ownerScope().value());
        assertEquals(rootId, closure.rootTemplateId());
        assertEquals(3, closure.rootRevision());
        assertEquals(1, closure.snapshots().size());
        assertEquals(0, closure.edges().size());
        var snapshot = closure.snapshots().get(0);
        assertEquals(rootId, snapshot.templateId());
        assertEquals(3, snapshot.revision());
        assertEquals("renderweave-design/1.0", snapshot.dslVersion());
        assertEquals("renderweave-expression/1.0", snapshot.expressionProfile());
        assertEquals(SCHEMA, snapshot.staticSchema());
        assertTrue(snapshot.contentHash().startsWith("sha256:"));
    }

    @Test
    void templateUseFreezesChildSnapshotAndAuthoredEdge() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        var childId = id("00000000-0000-4000-8000-0000000000b1");
        persistence.put(childId, 2, "owner-a", canvasDoc("Child"));
        persistence.put(rootId, 5, "owner-a", useDoc("Root", childId.value(), useId(1)));

        var outcome = authority.freezeClosure(request("render-2"), rootId);

        var frozen = assertInstanceOf(TemplateClosureAuthority.ClosureFrozen.class, outcome);
        var closure = frozen.closure();
        assertEquals(2, closure.snapshots().size());
        // UTF-8 sort by templateId: root …a1 precedes child …b1.
        assertEquals(rootId, closure.snapshots().get(0).templateId());
        assertEquals(childId, closure.snapshots().get(1).templateId());
        assertEquals(1, closure.edges().size());
        var edge = closure.edges().get(0);
        assertEquals(rootId, edge.parentTemplateId());
        assertEquals(5, edge.parentRevision());
        assertEquals(useId(1), edge.useId());
        assertEquals(childId, edge.childTemplateId());
        assertEquals(2, edge.childRevision());
    }

    @Test
    void diamondUsesFreezeChildOnceButKeepBothEdges() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        var childId = id("00000000-0000-4000-8000-0000000000b1");
        persistence.put(childId, 1, "owner-a", canvasDoc("Child"));
        persistence.put(rootId, 1, "owner-a", multiUseDoc("Root",
                new String[] { childId.value(), childId.value() },
                new String[] { useId(1), useId(2) }));

        var outcome = authority.freezeClosure(request("render-3"), rootId);

        var frozen = assertInstanceOf(TemplateClosureAuthority.ClosureFrozen.class, outcome);
        assertEquals(2, frozen.closure().snapshots().size());
        assertEquals(2, frozen.closure().edges().size());
        assertEquals(useId(1), frozen.closure().edges().get(0).useId());
        assertEquals(useId(2), frozen.closure().edges().get(1).useId());
    }

    @Test
    void corruptedContentHashIsInternalIntegrityViolation() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        persistence.put(rootId, 1, "owner-a", canvasDoc("Root"));
        persistence.corruptContentHash(rootId);

        var outcome = authority.freezeClosure(request("render-4"), rootId);

        assertInstanceOf(TemplateClosureAuthority.ClosureIntegrityViolation.class, outcome);
    }

    @Test
    void corruptedCanonicalBytesAreInternalIntegrityViolation() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        persistence.put(rootId, 1, "owner-a", canvasDoc("Root"));
        persistence.corruptCanonicalBytes(rootId);

        var outcome = authority.freezeClosure(request("render-5"), rootId);

        assertInstanceOf(TemplateClosureAuthority.ClosureIntegrityViolation.class, outcome);
    }

    @Test
    void missingRootIsNotFound() {
        var outcome = authority.freezeClosure(
                request("render-6"), id("00000000-0000-4000-8000-0000000000f1"));
        assertInstanceOf(TemplateClosureAuthority.ClosureNotFound.class, outcome);
    }

    @Test
    void deletedRootIsClosureDeleted() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        persistence.put(rootId, 1, "owner-a", canvasDoc("Root"));
        persistence.delete(rootId);

        var outcome = authority.freezeClosure(request("render-7"), rootId);

        assertInstanceOf(TemplateClosureAuthority.ClosureDeleted.class, outcome);
    }

    @Test
    void missingOrDeletedChildIsDependencyInvalid() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        persistence.put(rootId, 1, "owner-a", useDoc(
                "Root", "00000000-0000-4000-8000-0000000000c9", useId(1)));

        var outcome = authority.freezeClosure(request("render-8"), rootId);

        assertInstanceOf(TemplateClosureAuthority.ClosureDependencyInvalid.class, outcome);
    }

    @Test
    void crossScopeChildIsDependencyInvalid() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        var childId = id("00000000-0000-4000-8000-0000000000b1");
        persistence.put(childId, 1, "owner-b", canvasDoc("Child"));
        persistence.put(rootId, 1, "owner-a", useDoc("Root", childId.value(), useId(1)));

        var outcome = authority.freezeClosure(request("render-9"), rootId);

        assertInstanceOf(TemplateClosureAuthority.ClosureDependencyInvalid.class, outcome);
    }

    @Test
    void cyclicUseGraphIsDependencyInvalid() {
        var a = id("00000000-0000-4000-8000-0000000000a1");
        var b = id("00000000-0000-4000-8000-0000000000b1");
        persistence.put(b, 1, "owner-a", useDoc("B", a.value(), useId(1)));
        persistence.put(a, 1, "owner-a", useDoc("A", b.value(), useId(2)));

        var outcome = authority.freezeClosure(request("render-10"), a);

        assertInstanceOf(TemplateClosureAuthority.ClosureDependencyInvalid.class, outcome);
    }

    @Test
    void currentDriftRetriesThenFreezes() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        persistence.put(rootId, 1, "owner-a", canvasDoc("Root"));
        persistence.driftNextLocate(rootId, 2);

        var outcome = authority.freezeClosure(request("render-11"), rootId);

        var frozen = assertInstanceOf(TemplateClosureAuthority.ClosureFrozen.class, outcome);
        assertEquals(1, frozen.closure().rootRevision());
    }

    @Test
    void exhaustedDriftIsClosureUnstable() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        persistence.put(rootId, 1, "owner-a", canvasDoc("Root"));
        persistence.driftEveryLocate(rootId);

        var outcome = authority.freezeClosure(request("render-12"), rootId);

        assertInstanceOf(TemplateClosureAuthority.ClosureUnstable.class, outcome);
    }

    @Test
    void snapshotCountBeyondBudgetIsLimitExceeded() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        var childIds = new String[66];
        var useIds = new String[66];
        for (int index = 0; index < 66; index++) {
            var childId = String.format(
                    "00000000-0000-4000-8000-%012d", index + 1);
            childIds[index] = childId;
            useIds[index] = useId(index + 1);
            persistence.put(id(childId), 1, "owner-a", canvasDoc("Child" + index));
        }
        persistence.put(rootId, 1, "owner-a", multiUseDoc("Root", childIds, useIds));

        var outcome = authority.freezeClosure(request("render-13"), rootId);

        var limited = assertInstanceOf(TemplateClosureAuthority.ClosureLimitExceeded.class, outcome);
        assertEquals("uniqueTemplateSnapshots", limited.limitId().value());
    }

    @Test
    void edgeCountBeyondBudgetIsLimitExceeded() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        var leafIds = new String[] {
                "00000000-0000-4000-8000-0000000000e1",
                "00000000-0000-4000-8000-0000000000e2",
                "00000000-0000-4000-8000-0000000000e3",
                "00000000-0000-4000-8000-0000000000e4",
                "00000000-0000-4000-8000-0000000000e5"
        };
        for (var leafId : leafIds) {
            persistence.put(id(leafId), 1, "owner-a", canvasDoc("Leaf"));
        }
        var midIds = new String[50];
        var midUseIds = new String[50];
        for (int index = 0; index < 50; index++) {
            var midId = String.format("00000000-0000-4000-8000-%012d", index + 1);
            midIds[index] = midId;
            midUseIds[index] = useId(100 + index);
            var leafUses = new String[leafIds.length];
            var leafUseIds = new String[leafIds.length];
            for (int leaf = 0; leaf < leafIds.length; leaf++) {
                leafUses[leaf] = leafIds[leaf];
                leafUseIds[leaf] = useId(index * 10 + leaf + 1);
            }
            persistence.put(id(midId), 1, "owner-a", multiUseDoc("Mid" + index, leafUses, leafUseIds));
        }
        persistence.put(rootId, 1, "owner-a", multiUseDoc("Root", midIds, midUseIds));

        var outcome = authority.freezeClosure(request("render-14"), rootId);

        var limited = assertInstanceOf(TemplateClosureAuthority.ClosureLimitExceeded.class, outcome);
        assertEquals("authoredTemplateRefEdges", limited.limitId().value());
    }

    @Test
    void depthBeyondBudgetIsLimitExceeded() {
        var chainStart = id("00000000-0000-4000-8000-000000000001");
        var current = chainStart;
        for (int depth = 1; depth <= 18; depth++) {
            var next = id(String.format("00000000-0000-4000-8000-%012d", depth + 1));
            persistence.put(current, 1, "owner-a", useDoc("Chain" + depth, next.value(), useId(depth)));
            current = next;
        }
        persistence.put(current, 1, "owner-a", canvasDoc("ChainEnd"));

        var outcome = authority.freezeClosure(request("render-15"), chainStart);

        var limited = assertInstanceOf(TemplateClosureAuthority.ClosureLimitExceeded.class, outcome);
        assertEquals("closureDepth", limited.limitId().value());
    }

    @Test
    void closureCanonicalDesignBytesAboveBudgetIsLimitExceeded() {
        var scriptedPersistence = new FakePersistence();
        var scriptedAuthority = new CanonicalTemplateClosureAuthority(
                scriptedPersistence,
                SCRIPTED_DESIGNS
        );
        var rootId = putClosureWithCanonicalByteCount(
                scriptedPersistence,
                MAX_CLOSURE_CANONICAL_DESIGN_BYTES + 1
        );

        var outcome = scriptedAuthority.freezeClosure(request("render-canonical-bytes-above"), rootId);

        var limited = assertInstanceOf(TemplateClosureAuthority.ClosureLimitExceeded.class, outcome);
        assertEquals("closureCanonicalDesignBytes", limited.limitId().value());
    }

    @Test
    void closureCanonicalDesignBytesBelowBudgetIsAccepted() {
        var scriptedPersistence = new FakePersistence();
        var scriptedAuthority = new CanonicalTemplateClosureAuthority(
                scriptedPersistence,
                SCRIPTED_DESIGNS
        );
        var rootId = putClosureWithCanonicalByteCount(
                scriptedPersistence,
                MAX_CLOSURE_CANONICAL_DESIGN_BYTES - 1
        );

        var outcome = scriptedAuthority.freezeClosure(request("render-canonical-bytes-below"), rootId);

        assertInstanceOf(TemplateClosureAuthority.ClosureFrozen.class, outcome);
    }

    @Test
    void closureCanonicalDesignBytesAtBudgetIsAccepted() {
        var scriptedPersistence = new FakePersistence();
        var scriptedAuthority = new CanonicalTemplateClosureAuthority(
                scriptedPersistence,
                SCRIPTED_DESIGNS
        );
        var rootId = putClosureWithCanonicalByteCount(
                scriptedPersistence,
                MAX_CLOSURE_CANONICAL_DESIGN_BYTES
        );

        var outcome = scriptedAuthority.freezeClosure(request("render-canonical-bytes-at"), rootId);

        assertInstanceOf(TemplateClosureAuthority.ClosureFrozen.class, outcome);
    }

    @Test
    void persistenceUnavailableIsClosureUnavailable() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        persistence.put(rootId, 1, "owner-a", canvasDoc("Root"));
        persistence.unavailable = true;

        var outcome = authority.freezeClosure(request("render-16"), rootId);

        assertInstanceOf(TemplateClosureAuthority.ClosureUnavailable.class, outcome);
    }

    @Test
    void frozenSnapshotsCarryAdmittedCanonicalBytesByteIdentical() {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        var admitted = persistence.put(rootId, 1, "owner-a", canvasDoc("Root"));

        var frozen = assertInstanceOf(
                TemplateClosureAuthority.ClosureFrozen.class,
                authority.freezeClosure(request("render-17"), rootId));

        assertArrayEquals(admitted.canonicalUtf8(), frozen.closure().snapshots().get(0).canonicalDesignDslUtf8());
        assertEquals(admitted.contentHash(), frozen.closure().snapshots().get(0).contentHash());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static TemplateClosureAuthority.RenderRequestId request(String value) {
        return new TemplateClosureAuthority.RenderRequestId(value);
    }

    private static TemplateApplication.TemplateId id(String value) {
        return new TemplateApplication.TemplateId(value);
    }

    /** Canonical UUID v4-shaped useId (kernel requires the UUID lexical domain). */
    private static String useId(int sequence) {
        return String.format("00000000-0000-4000-a000-%012d", sequence);
    }

    private static String canvasDoc(String displayName) {
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"" + displayName + "\","
                + "\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,"
                + "\"bindings\":[],\"children\":[]}}";
    }

    private static String useDoc(String displayName, String childTemplateId, String useId) {
        return multiUseDoc(displayName, new String[] { childTemplateId }, new String[] { useId });
    }

    private static String multiUseDoc(String displayName, String[] childTemplateIds, String[] useIds) {
        var children = new StringBuilder();
        for (int index = 0; index < childTemplateIds.length; index++) {
            if (index > 0) {
                children.append(',');
            }
            children.append("{\"nodeId\":\"")
                    .append(String.format("00000000-0000-4000-9000-%012d", index + 1))
                    .append("\",\"kind\":\"templateUse\",\"bindings\":[],\"useId\":\"")
                    .append(useIds[index])
                    .append("\",\"templateRef\":{\"templateId\":\"")
                    .append(childTemplateIds[index])
                    .append("\"},\"contextSelector\":{\"kind\":\"empty\"},\"fills\":[],")
                    .append("\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0,")
                    .append("\"widthMode\":\"HUG_CONTENT\",\"heightMode\":\"HUG_CONTENT\"}}");
        }
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"" + displayName + "\","
                + "\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,"
                + "\"bindings\":[],\"children\":[" + children + "]}}";
    }

    private static TemplateApplication.TemplateId putClosureWithCanonicalByteCount(
            FakePersistence target,
            int totalCanonicalBytes
    ) {
        var rootId = id("00000000-0000-4000-8000-0000000000a1");
        var firstChildId = id("00000000-0000-4000-8000-0000000000b1");
        var secondChildId = id("00000000-0000-4000-8000-0000000000c1");
        var rootJson = multiUseDoc(
                "Root",
                new String[] {firstChildId.value(), secondChildId.value()},
                new String[] {useId(1), useId(2)}
        );
        var leafJson = canvasDoc("Leaf");
        var secondChildBytes = leafJson.getBytes(StandardCharsets.UTF_8);
        var rootBytes = paddedJson(rootJson, MAX_DESIGN_BYTES);
        var firstChildBytes = paddedJson(
                leafJson,
                totalCanonicalBytes - rootBytes.length - secondChildBytes.length
        );
        assertEquals(
                totalCanonicalBytes,
                rootBytes.length + firstChildBytes.length + secondChildBytes.length
        );
        target.putCanonical(firstChildId, 1, "owner-a", firstChildBytes);
        target.putCanonical(secondChildId, 1, "owner-a", secondChildBytes);
        target.putCanonical(rootId, 1, "owner-a", rootBytes);
        return rootId;
    }

    private static byte[] paddedJson(String json, int byteLength) {
        var raw = json.getBytes(StandardCharsets.UTF_8);
        if (raw.length > byteLength || raw[raw.length - 1] != '}') {
            throw new IllegalArgumentException("JSON cannot be padded to requested byte length");
        }
        var padded = new byte[byteLength];
        System.arraycopy(raw, 0, padded, 0, raw.length - 1);
        Arrays.fill(padded, raw.length - 1, byteLength - 1, (byte) ' ');
        padded[byteLength - 1] = '}';
        return padded;
    }

    // ------------------------------------------------------------------
    // fake persistence
    // ------------------------------------------------------------------

    static final class FakePersistence implements TemplatePersistence {

        private final Map<String, Entry> store = new HashMap<>();
        private final Map<String, Long> driftNextLocate = new HashMap<>();
        private final Map<String, Boolean> driftEveryLocate = new HashMap<>();
        boolean unavailable;

        record Entry(StoredCurrent current, boolean deleted) {
        }

        DesignDslAuthority.Admitted put(
                TemplateApplication.TemplateId templateId,
                long revision,
                String ownerScope,
                String designDslJson
        ) {
            var admission = DESIGNS.admit(designDslJson.getBytes(StandardCharsets.UTF_8));
            var admitted = assertInstanceOf(DesignDslAuthority.Admitted.class, admission);
            var metadata = new TemplateMetadata(
                    templateId,
                    new OwnerScopeAuthority.OwnerScope(ownerScope),
                    SCHEMA,
                    revision,
                    Lifecycle.ACTIVE
            );
            var stored = new StoredCurrent(
                    metadata,
                    designDslJson.getBytes(StandardCharsets.UTF_8),
                    admitted.canonicalUtf8(),
                    admitted.contentHash(),
                    TemplateApplication.Readiness.READY
            );
            store.put(templateId.value(), new Entry(stored, false));
            return admitted;
        }

        void putCanonical(
                TemplateApplication.TemplateId templateId,
                long revision,
                String ownerScope,
                byte[] canonicalUtf8
        ) {
            var metadata = new TemplateMetadata(
                    templateId,
                    new OwnerScopeAuthority.OwnerScope(ownerScope),
                    SCHEMA,
                    revision,
                    Lifecycle.ACTIVE
            );
            var stored = new StoredCurrent(
                    metadata,
                    new byte[] {'{', '}'},
                    canonicalUtf8,
                    SCRIPTED_CONTENT_HASH,
                    TemplateApplication.Readiness.READY
            );
            store.put(templateId.value(), new Entry(stored, false));
        }

        void delete(TemplateApplication.TemplateId templateId) {
            var entry = store.get(templateId.value());
            store.put(templateId.value(), new Entry(entry.current(), true));
        }

        void corruptContentHash(TemplateApplication.TemplateId templateId) {
            var entry = store.get(templateId.value());
            var stored = entry.current();
            var corrupted = new StoredCurrent(
                    stored.metadata(),
                    stored.storedJsonUtf8(),
                    stored.canonicalDesignDslUtf8(),
                    "sha256:" + "0".repeat(64),
                    stored.readiness()
            );
            store.put(templateId.value(), new Entry(corrupted, false));
        }

        void corruptCanonicalBytes(TemplateApplication.TemplateId templateId) {
            var entry = store.get(templateId.value());
            var stored = entry.current();
            var canonical = stored.canonicalDesignDslUtf8();
            // Replace the displayName value bytes so re-admission canonicalizes differently.
            var text = new String(canonical, StandardCharsets.UTF_8);
            var corrupted = text.replace("\"displayName\":\"Root\"", "\"displayName\":\"Tampered\"")
                    .getBytes(StandardCharsets.UTF_8);
            var corruptedStored = new StoredCurrent(
                    stored.metadata(),
                    stored.storedJsonUtf8(),
                    corrupted,
                    stored.contentHash(),
                    stored.readiness()
            );
            store.put(templateId.value(), new Entry(corruptedStored, false));
        }

        void driftNextLocate(TemplateApplication.TemplateId templateId, long driftedRevision) {
            driftNextLocate.put(templateId.value(), driftedRevision);
        }

        void driftEveryLocate(TemplateApplication.TemplateId templateId) {
            driftEveryLocate.put(templateId.value(), Boolean.TRUE);
        }

        @Override
        public LocateOutcome locate(TemplateApplication.TemplateId templateId) {
            if (unavailable) {
                return new LocateUnavailable();
            }
            var entry = store.get(templateId.value());
            if (entry == null || entry.deleted()) {
                return new LocateNotFound();
            }
            var metadata = entry.current().metadata();
            if (Boolean.TRUE.equals(driftEveryLocate.get(templateId.value()))) {
                metadata = withRevision(metadata, metadata.currentRevision() + 99);
            } else {
                var drifted = driftNextLocate.remove(templateId.value());
                if (drifted != null) {
                    metadata = withRevision(metadata, drifted);
                }
            }
            return new Located(metadata);
        }

        @Override
        public LoadCurrentOutcome loadCurrent(TemplateApplication.TemplateId templateId) {
            if (unavailable) {
                return new CurrentLoadUnavailable();
            }
            var entry = store.get(templateId.value());
            if (entry == null) {
                return new CurrentNotFound();
            }
            if (entry.deleted()) {
                var metadata = withLifecycle(entry.current().metadata(), Lifecycle.DELETED);
                var stored = entry.current();
                return new CurrentLoaded(new StoredCurrent(
                        metadata,
                        stored.storedJsonUtf8(),
                        stored.canonicalDesignDslUtf8(),
                        stored.contentHash(),
                        stored.readiness()
                ));
            }
            return new CurrentLoaded(entry.current());
        }

        private static TemplateMetadata withRevision(TemplateMetadata metadata, long revision) {
            return new TemplateMetadata(
                    metadata.templateId(),
                    metadata.ownerScope(),
                    metadata.staticSchema(),
                    revision,
                    metadata.lifecycle()
            );
        }

        private static TemplateMetadata withLifecycle(TemplateMetadata metadata, Lifecycle lifecycle) {
            return new TemplateMetadata(
                    metadata.templateId(),
                    metadata.ownerScope(),
                    metadata.staticSchema(),
                    metadata.currentRevision(),
                    lifecycle
            );
        }

        @Override
        public CreateOutcome create(CreateCommit commit) {
            throw new AssertionError("not used by closure tests");
        }

        @Override
        public AppendOutcome append(AppendCommit commit) {
            throw new AssertionError("not used by closure tests");
        }

        @Override
        public LoadUseTargetsOutcome loadUseTargets(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("not used by closure tests");
        }

        @Override
        public FindAssetReferencesOutcome findAssetReferences(String assetId) {
            throw new AssertionError("not used by closure tests");
        }

        @Override
        public UpdateReadinessOutcome updateReadiness(
                TemplateApplication.TemplateId templateId,
                long currentRevision,
                TemplateApplication.Readiness readiness,
                cn.hbads.renderweave.template.spi.TemplateDependencySnapshot dependencySnapshot
        ) {
            throw new AssertionError("not used by closure tests");
        }
    }
}
