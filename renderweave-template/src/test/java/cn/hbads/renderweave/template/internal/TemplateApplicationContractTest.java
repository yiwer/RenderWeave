package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TemplateApplicationContractTest {

    /**
     * The contract fixtures carry no AssetRef atoms or TemplateUse occurrences, so the
     * dependency probe must never be consulted on these paths.
     */
    private static TemplateApplication application(
            OwnerScopeAuthority ownerScopes,
            TemplatePersistence persistence,
            StaticSchemaAuthority schemas
    ) {
        return TemplateModule.application(ownerScopes, persistence, schemas,
                new DependencyResolution() {
                    @Override
                    public AssetCheck checkAsset(String assetId, String kind) {
                        throw new AssertionError("no dependency probe expected: asset " + assetId);
                    }

                    @Override
                    public TemplateCheck checkTemplateUse(String targetTemplateId) {
                        throw new AssertionError(
                                "no dependency probe expected: template use " + targetTemplateId);
                    }
                });
    }

    private static final byte[] DESIGN = """
            {
              "dslVersion":"renderweave-design/1.0",
              "expressionProfile":"renderweave-expression/1.0",
              "displayName":"  First template  ",
              "definitions":[],
              "designRoot":{
                "nodeId":"123e4567-e89b-42d3-a456-426614174000",
                "kind":"canvas",
                "widthMm":210.0,
                "heightMm":297,
                "bindings":[],
                "children":[]
              }
            }
            """.getBytes(StandardCharsets.UTF_8);

    @Test
    void createCommitsCanonicalReadableRevisionZeroAfterAuthorityRecheck() {
        var invocation = TemplateApplication.TemplateInvocationRef.serverCreated("request-1");
        var schema = new StaticSchemaRef(
                SchemaKey.systemProvided("system-empty"),
                VersionTag.of("v1")
        );
        var scope = new OwnerScopeAuthority.OwnerScope("owner-a");
        var recheck = new OwnerScopeAuthority.RecheckIdentity("recheck-create-1");
        var authority = new CreateAuthorityScript(
                invocation,
                new OwnerScopeAuthority.CreateGranted(
                        scope,
                        recheck,
                        OwnerScopeAuthority.Disclosure.READABLE
                )
        );
        var persistence = new CreatePersistenceScript(scope, schema);
        StaticSchemaAuthority schemas = reference -> {
            assertEquals(schema, reference);
            return new StaticSchemaAuthority.Resolved(reference);
        };
        var application = application(authority, persistence, schemas);

        var outcome = application.create(
                invocation,
                new TemplateApplication.CreateCommand(schema, DESIGN)
        );

        var created = assertInstanceOf(TemplateApplication.CreatedReadable.class, outcome);
        assertNotNull(created.current().templateId());
        assertEquals(0, created.current().revision());
        assertEquals(schema, created.current().staticSchema());
        assertEquals(TemplateApplication.Readiness.READY, created.current().readiness());
        assertArrayEquals(persistence.canonicalUtf8, created.current().canonicalDesignDslUtf8());
        assertEquals(persistence.contentHash, created.current().contentHash());
        authority.assertExhausted();
        persistence.assertExhausted();
    }

    @Test
    void currentReadLocatesThenAuthorizesBeforeLoadingAndRevalidatesStoredJson() {
        var invocation = TemplateApplication.TemplateInvocationRef.serverCreated("request-read-1");
        var templateId = TemplateApplication.TemplateId.of("44e515b5-4166-4477-855f-43f96bd53e97");
        var schema = new StaticSchemaRef(
                SchemaKey.systemProvided("system-empty"),
                VersionTag.of("v1")
        );
        var scope = new OwnerScopeAuthority.OwnerScope("owner-read");
        var admitted = assertInstanceOf(
                cn.hbads.renderweave.template.api.DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority().admit(DESIGN)
        );
        var calls = new ArrayList<String>();
        var authority = new ReadAuthorityScript(invocation, scope, calls);
        var persistence = new ReadPersistenceScript(
                templateId,
                scope,
                schema,
                DESIGN,
                admitted.canonicalUtf8(),
                admitted.contentHash(),
                calls
        );
        StaticSchemaAuthority schemas = reference -> {
            throw new AssertionError("read must not resolve the permanent StaticSchema again");
        };
        var application = application(authority, persistence, schemas);

        var outcome = application.getCurrent(invocation, templateId);

        var current = assertInstanceOf(TemplateApplication.CurrentReadable.class, outcome).current();
        assertEquals(templateId, current.templateId());
        assertEquals(7, current.revision());
        assertEquals(schema, current.staticSchema());
        assertArrayEquals(admitted.canonicalUtf8(), current.canonicalDesignDslUtf8());
        assertEquals(admitted.contentHash(), current.contentHash());
        assertEquals(List.of("locate", "authorize:READ", "load"), calls);
    }

    @Test
    void saveAppendsEvenWhenContentHashMatchesCurrentAndAdvancesExactlyOnce() {
        var invocation = TemplateApplication.TemplateInvocationRef.serverCreated("request-save-1");
        var templateId = TemplateApplication.TemplateId.of("52c78fb7-2fa2-4417-aa3f-cbd43b482f79");
        var schema = new StaticSchemaRef(
                SchemaKey.systemProvided("system-empty"),
                VersionTag.of("v1")
        );
        var scope = new OwnerScopeAuthority.OwnerScope("owner-save");
        var admitted = assertInstanceOf(
                cn.hbads.renderweave.template.api.DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority().admit(DESIGN)
        );
        var calls = new ArrayList<String>();
        var authority = new SaveAuthorityScript(invocation, scope, calls);
        var persistence = new SavePersistenceScript(
                templateId,
                scope,
                schema,
                DESIGN,
                admitted.canonicalUtf8(),
                admitted.contentHash(),
                calls
        );
        StaticSchemaAuthority schemas = reference -> {
            calls.add("schema");
            assertEquals(schema, reference);
            return new StaticSchemaAuthority.Resolved(reference);
        };
        var application = application(authority, persistence, schemas);

        var outcome = application.save(
                invocation,
                new TemplateApplication.SaveCommand(templateId, 0, DESIGN)
        );

        var current = assertInstanceOf(TemplateApplication.SavedReadable.class, outcome).current();
        assertEquals(1, current.revision());
        assertEquals(admitted.contentHash(), current.contentHash());
        assertArrayEquals(admitted.canonicalUtf8(), current.canonicalDesignDslUtf8());
        assertEquals(
                List.of("locate", "authorize:UPDATE", "schema", "load", "recheck", "append"),
                calls
        );
    }

    @Test
    void readGrantWithoutReadableDisclosureFailsClosedBeforeContentLoad() {
        var invocation = TemplateApplication.TemplateInvocationRef.serverCreated("request-read-opaque");
        var templateId = TemplateApplication.TemplateId.of("f0e7eeb9-bf1d-4506-888a-d65f044f76fc");
        var scope = new OwnerScopeAuthority.OwnerScope("owner-opaque-read");
        var schema = new StaticSchemaRef(
                SchemaKey.systemProvided("system-empty"),
                VersionTag.of("v1")
        );
        var persistence = new MetadataOnlyPersistenceScript(templateId, scope, schema);
        OwnerScopeAuthority authority = new OwnerScopeAuthority() {
            @Override
            public CreateDecision authorizeCreate(
                    TemplateApplication.TemplateInvocationRef ignored
            ) {
                throw new AssertionError("unexpected authorizeCreate");
            }

            @Override
            public ExistingDecision authorizeExisting(
                    TemplateApplication.TemplateInvocationRef actualInvocation,
                    OwnerScope storedOwnerScope,
                    ExistingOperation operation
            ) {
                assertEquals(invocation, actualInvocation);
                assertEquals(scope, storedOwnerScope);
                assertEquals(ExistingOperation.READ, operation);
                return new ExistingGranted(
                        Disclosure.OPAQUE,
                        new RecheckIdentity("opaque-read-must-not-be-used")
                );
            }

            @Override
            public RecheckDecision recheck(RecheckIdentity identity) {
                throw new AssertionError("read must not recheck");
            }
        };
        var application = application(
                authority,
                persistence,
                reference -> {
                    throw new AssertionError("read must not resolve schema");
                }
        );

        assertInstanceOf(
                TemplateApplication.CurrentNotFound.class,
                application.getCurrent(invocation, templateId)
        );
        assertFalse(persistence.loaded);
    }

    @Test
    void createRetriesAnOpaqueIdentityCollisionWithoutRepeatingAuthorization() {
        var invocation = TemplateApplication.TemplateInvocationRef.serverCreated("request-collision");
        var schema = new StaticSchemaRef(
                SchemaKey.systemProvided("system-empty"),
                VersionTag.of("v1")
        );
        var authority = new CreateAuthorityScript(
                invocation,
                new OwnerScopeAuthority.CreateGranted(
                        new OwnerScopeAuthority.OwnerScope("owner-collision"),
                        new OwnerScopeAuthority.RecheckIdentity("recheck-collision"),
                        OwnerScopeAuthority.Disclosure.OPAQUE
                )
        );
        var persistence = new CollisionPersistenceScript();
        var application = application(
                authority,
                persistence,
                reference -> new StaticSchemaAuthority.Resolved(reference)
        );

        var outcome = application.create(
                invocation,
                new TemplateApplication.CreateCommand(schema, DESIGN)
        );

        var opaque = assertInstanceOf(TemplateApplication.CreatedOpaque.class, outcome);
        assertEquals(persistence.second, opaque.templateId());
        assertNotEquals(persistence.first, persistence.second);
        authority.assertExhausted();
        assertEquals(2, persistence.calls);
    }

    @Test
    void updateWithoutReadGetsConflictWithoutCurrentRevisionDisclosure() {
        var invocation = TemplateApplication.TemplateInvocationRef.serverCreated("request-opaque-conflict");
        var templateId = TemplateApplication.TemplateId.of("e2876a4c-2713-4320-b1ed-efb3c86488b8");
        var schema = new StaticSchemaRef(
                SchemaKey.systemProvided("system-empty"),
                VersionTag.of("v1")
        );
        var scope = new OwnerScopeAuthority.OwnerScope("owner-opaque-conflict");
        var admitted = assertInstanceOf(
                cn.hbads.renderweave.template.api.DesignDslAuthority.Admitted.class,
                new CanonicalDesignDslAuthority().admit(DESIGN)
        );
        var calls = new ArrayList<String>();
        var authority = new SaveAuthorityScript(
                invocation,
                scope,
                OwnerScopeAuthority.Disclosure.OPAQUE,
                calls
        );
        var persistence = new SavePersistenceScript(
                templateId,
                scope,
                schema,
                1,
                DESIGN,
                admitted.canonicalUtf8(),
                admitted.contentHash(),
                calls
        );
        var application = application(
                authority,
                persistence,
                reference -> {
                    calls.add("schema");
                    return new StaticSchemaAuthority.Resolved(reference);
                }
        );

        var conflict = assertInstanceOf(
                TemplateApplication.SaveRevisionConflict.class,
                application.save(
                        invocation,
                        new TemplateApplication.SaveCommand(templateId, 0, DESIGN)
                )
        );

        assertFalse(conflict.currentRevision().isPresent());
        assertEquals(List.of("locate", "authorize:UPDATE", "schema", "load"), calls);
    }

    private static final class CreateAuthorityScript implements OwnerScopeAuthority {
        private final TemplateApplication.TemplateInvocationRef expectedInvocation;
        private final CreateGranted grant;
        private int step;

        private CreateAuthorityScript(
                TemplateApplication.TemplateInvocationRef expectedInvocation,
                CreateGranted grant
        ) {
            this.expectedInvocation = expectedInvocation;
            this.grant = grant;
        }

        @Override
        public CreateDecision authorizeCreate(TemplateApplication.TemplateInvocationRef invocation) {
            assertEquals(0, step++);
            assertEquals(expectedInvocation, invocation);
            return grant;
        }

        @Override
        public ExistingDecision authorizeExisting(
                TemplateApplication.TemplateInvocationRef invocation,
                OwnerScope storedOwnerScope,
                ExistingOperation operation
        ) {
            throw new AssertionError("unexpected authorizeExisting");
        }

        @Override
        public RecheckDecision recheck(RecheckIdentity identity) {
            assertEquals(1, step++);
            assertEquals(grant.recheckIdentity(), identity);
            return new RecheckGranted();
        }

        private void assertExhausted() {
            assertEquals(2, step);
        }
    }

    private static final class CreatePersistenceScript implements TemplatePersistence {
        @Override
        public LoadUseTargetsOutcome loadUseTargets(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("unexpected loadUseTargets");
        }

        @Override
        public FindAssetReferencesOutcome findAssetReferences(String assetId) {
            throw new AssertionError("unexpected findAssetReferences");
        }

        @Override
        public UpdateReadinessOutcome updateReadiness(
                TemplateApplication.TemplateId templateId,
                long currentRevision,
                TemplateApplication.Readiness readiness
        ) {
            throw new AssertionError("unexpected updateReadiness");
        }
        private final OwnerScopeAuthority.OwnerScope expectedScope;
        private final StaticSchemaRef expectedSchema;
        private byte[] canonicalUtf8;
        private String contentHash;
        private int calls;

        private CreatePersistenceScript(
                OwnerScopeAuthority.OwnerScope expectedScope,
                StaticSchemaRef expectedSchema
        ) {
            this.expectedScope = expectedScope;
            this.expectedSchema = expectedSchema;
        }

        @Override
        public LocateOutcome locate(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("unexpected locate");
        }

        @Override
        public LoadCurrentOutcome loadCurrent(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("unexpected loadCurrent");
        }

        @Override
        public CreateOutcome create(CreateCommit commit) {
            calls++;
            assertEquals(expectedScope, commit.ownerScope());
            assertEquals(expectedSchema, commit.staticSchema());
            assertEquals(0, commit.revision());
            assertEquals(TemplateApplication.Readiness.READY, commit.readiness());
            canonicalUtf8 = commit.canonicalDesignDslUtf8();
            contentHash = commit.contentHash();
            assertNotNull(commit.templateId());
            assertNotNull(canonicalUtf8);
            assertNotNull(contentHash);
            return new Created();
        }

        @Override
        public AppendOutcome append(AppendCommit commit) {
            throw new AssertionError("unexpected append");
        }

        private void assertExhausted() {
            assertEquals(1, calls);
            Objects.requireNonNull(canonicalUtf8);
            Objects.requireNonNull(contentHash);
        }
    }

    private static final class ReadAuthorityScript implements OwnerScopeAuthority {
        private final TemplateApplication.TemplateInvocationRef expectedInvocation;
        private final OwnerScope expectedScope;
        private final List<String> calls;

        private ReadAuthorityScript(
                TemplateApplication.TemplateInvocationRef expectedInvocation,
                OwnerScope expectedScope,
                List<String> calls
        ) {
            this.expectedInvocation = expectedInvocation;
            this.expectedScope = expectedScope;
            this.calls = calls;
        }

        @Override
        public CreateDecision authorizeCreate(TemplateApplication.TemplateInvocationRef invocation) {
            throw new AssertionError("unexpected authorizeCreate");
        }

        @Override
        public ExistingDecision authorizeExisting(
                TemplateApplication.TemplateInvocationRef invocation,
                OwnerScope storedOwnerScope,
                ExistingOperation operation
        ) {
            calls.add("authorize:" + operation.name());
            assertEquals(expectedInvocation, invocation);
            assertEquals(expectedScope, storedOwnerScope);
            assertEquals(ExistingOperation.READ, operation);
            return new ExistingGranted(Disclosure.READABLE, new RecheckIdentity("unused-read"));
        }

        @Override
        public RecheckDecision recheck(RecheckIdentity identity) {
            throw new AssertionError("read must not recheck mutation authority");
        }
    }

    private static final class ReadPersistenceScript implements TemplatePersistence {
        @Override
        public LoadUseTargetsOutcome loadUseTargets(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("unexpected loadUseTargets");
        }

        @Override
        public FindAssetReferencesOutcome findAssetReferences(String assetId) {
            throw new AssertionError("unexpected findAssetReferences");
        }

        @Override
        public UpdateReadinessOutcome updateReadiness(
                TemplateApplication.TemplateId templateId,
                long currentRevision,
                TemplateApplication.Readiness readiness
        ) {
            throw new AssertionError("unexpected updateReadiness");
        }
        private final TemplateApplication.TemplateId expectedTemplateId;
        private final TemplateMetadata metadata;
        private final StoredCurrent current;
        private final List<String> calls;

        private ReadPersistenceScript(
                TemplateApplication.TemplateId expectedTemplateId,
                OwnerScopeAuthority.OwnerScope scope,
                StaticSchemaRef schema,
                byte[] storedJsonUtf8,
                byte[] canonicalUtf8,
                String contentHash,
                List<String> calls
        ) {
            this.expectedTemplateId = expectedTemplateId;
            this.metadata = new TemplateMetadata(
                    expectedTemplateId,
                    scope,
                    schema,
                    7,
                    Lifecycle.ACTIVE
            );
            this.current = new StoredCurrent(
                    metadata,
                    storedJsonUtf8,
                    canonicalUtf8,
                    contentHash,
                    TemplateApplication.Readiness.READY
            );
            this.calls = calls;
        }

        @Override
        public LocateOutcome locate(TemplateApplication.TemplateId templateId) {
            calls.add("locate");
            assertEquals(expectedTemplateId, templateId);
            return new Located(metadata);
        }

        @Override
        public LoadCurrentOutcome loadCurrent(TemplateApplication.TemplateId templateId) {
            calls.add("load");
            assertEquals(expectedTemplateId, templateId);
            return new CurrentLoaded(current);
        }

        @Override
        public CreateOutcome create(CreateCommit commit) {
            throw new AssertionError("unexpected create");
        }

        @Override
        public AppendOutcome append(AppendCommit commit) {
            throw new AssertionError("unexpected append");
        }
    }

    private static final class SaveAuthorityScript implements OwnerScopeAuthority {
        private final TemplateApplication.TemplateInvocationRef expectedInvocation;
        private final OwnerScope expectedScope;
        private final RecheckIdentity recheckIdentity = new RecheckIdentity("recheck-save-1");
        private final Disclosure disclosure;
        private final List<String> calls;

        private SaveAuthorityScript(
                TemplateApplication.TemplateInvocationRef expectedInvocation,
                OwnerScope expectedScope,
                List<String> calls
        ) {
            this(expectedInvocation, expectedScope, Disclosure.READABLE, calls);
        }

        private SaveAuthorityScript(
                TemplateApplication.TemplateInvocationRef expectedInvocation,
                OwnerScope expectedScope,
                Disclosure disclosure,
                List<String> calls
        ) {
            this.expectedInvocation = expectedInvocation;
            this.expectedScope = expectedScope;
            this.disclosure = disclosure;
            this.calls = calls;
        }

        @Override
        public CreateDecision authorizeCreate(TemplateApplication.TemplateInvocationRef invocation) {
            throw new AssertionError("unexpected authorizeCreate");
        }

        @Override
        public ExistingDecision authorizeExisting(
                TemplateApplication.TemplateInvocationRef invocation,
                OwnerScope storedOwnerScope,
                ExistingOperation operation
        ) {
            calls.add("authorize:" + operation.name());
            assertEquals(expectedInvocation, invocation);
            assertEquals(expectedScope, storedOwnerScope);
            assertEquals(ExistingOperation.UPDATE, operation);
            return new ExistingGranted(disclosure, recheckIdentity);
        }

        @Override
        public RecheckDecision recheck(RecheckIdentity identity) {
            calls.add("recheck");
            assertEquals(recheckIdentity, identity);
            return new RecheckGranted();
        }
    }

    private static final class SavePersistenceScript implements TemplatePersistence {
        @Override
        public LoadUseTargetsOutcome loadUseTargets(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("unexpected loadUseTargets");
        }

        @Override
        public FindAssetReferencesOutcome findAssetReferences(String assetId) {
            throw new AssertionError("unexpected findAssetReferences");
        }

        @Override
        public UpdateReadinessOutcome updateReadiness(
                TemplateApplication.TemplateId templateId,
                long currentRevision,
                TemplateApplication.Readiness readiness
        ) {
            throw new AssertionError("unexpected updateReadiness");
        }
        private final TemplateApplication.TemplateId expectedTemplateId;
        private final TemplateMetadata metadata;
        private final StoredCurrent current;
        private final List<String> calls;

        private SavePersistenceScript(
                TemplateApplication.TemplateId expectedTemplateId,
                OwnerScopeAuthority.OwnerScope scope,
                StaticSchemaRef schema,
                byte[] storedJsonUtf8,
                byte[] canonicalUtf8,
                String contentHash,
                List<String> calls
        ) {
            this(
                    expectedTemplateId,
                    scope,
                    schema,
                    0,
                    storedJsonUtf8,
                    canonicalUtf8,
                    contentHash,
                    calls
            );
        }

        private SavePersistenceScript(
                TemplateApplication.TemplateId expectedTemplateId,
                OwnerScopeAuthority.OwnerScope scope,
                StaticSchemaRef schema,
                long currentRevision,
                byte[] storedJsonUtf8,
                byte[] canonicalUtf8,
                String contentHash,
                List<String> calls
        ) {
            this.expectedTemplateId = expectedTemplateId;
            this.metadata = new TemplateMetadata(
                    expectedTemplateId,
                    scope,
                    schema,
                    currentRevision,
                    Lifecycle.ACTIVE
            );
            this.current = new StoredCurrent(
                    metadata,
                    storedJsonUtf8,
                    canonicalUtf8,
                    contentHash,
                    TemplateApplication.Readiness.READY
            );
            this.calls = calls;
        }

        @Override
        public LocateOutcome locate(TemplateApplication.TemplateId templateId) {
            calls.add("locate");
            assertEquals(expectedTemplateId, templateId);
            return new Located(metadata);
        }

        @Override
        public LoadCurrentOutcome loadCurrent(TemplateApplication.TemplateId templateId) {
            calls.add("load");
            assertEquals(expectedTemplateId, templateId);
            return new CurrentLoaded(current);
        }

        @Override
        public CreateOutcome create(CreateCommit commit) {
            throw new AssertionError("unexpected create");
        }

        @Override
        public AppendOutcome append(AppendCommit commit) {
            calls.add("append");
            assertEquals(expectedTemplateId, commit.templateId());
            assertEquals(0, commit.expectedRevision());
            assertEquals(1, commit.nextRevision());
            assertEquals(metadata.ownerScope(), commit.ownerScope());
            assertEquals(metadata.staticSchema(), commit.staticSchema());
            assertEquals(current.contentHash(), commit.contentHash());
            assertArrayEquals(current.canonicalDesignDslUtf8(), commit.canonicalDesignDslUtf8());
            return new Appended();
        }
    }

    private static final class MetadataOnlyPersistenceScript implements TemplatePersistence {
        @Override
        public LoadUseTargetsOutcome loadUseTargets(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("unexpected loadUseTargets");
        }

        @Override
        public FindAssetReferencesOutcome findAssetReferences(String assetId) {
            throw new AssertionError("unexpected findAssetReferences");
        }

        @Override
        public UpdateReadinessOutcome updateReadiness(
                TemplateApplication.TemplateId templateId,
                long currentRevision,
                TemplateApplication.Readiness readiness
        ) {
            throw new AssertionError("unexpected updateReadiness");
        }
        private final TemplateMetadata metadata;
        private boolean loaded;

        private MetadataOnlyPersistenceScript(
                TemplateApplication.TemplateId templateId,
                OwnerScopeAuthority.OwnerScope scope,
                StaticSchemaRef schema
        ) {
            metadata = new TemplateMetadata(templateId, scope, schema, 0, Lifecycle.ACTIVE);
        }

        @Override
        public LocateOutcome locate(TemplateApplication.TemplateId templateId) {
            assertEquals(metadata.templateId(), templateId);
            return new Located(metadata);
        }

        @Override
        public LoadCurrentOutcome loadCurrent(TemplateApplication.TemplateId templateId) {
            loaded = true;
            throw new AssertionError("content load must remain hidden");
        }

        @Override
        public CreateOutcome create(CreateCommit commit) {
            throw new AssertionError("unexpected create");
        }

        @Override
        public AppendOutcome append(AppendCommit commit) {
            throw new AssertionError("unexpected append");
        }
    }

    private static final class CollisionPersistenceScript implements TemplatePersistence {
        @Override
        public LoadUseTargetsOutcome loadUseTargets(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("unexpected loadUseTargets");
        }

        @Override
        public FindAssetReferencesOutcome findAssetReferences(String assetId) {
            throw new AssertionError("unexpected findAssetReferences");
        }

        @Override
        public UpdateReadinessOutcome updateReadiness(
                TemplateApplication.TemplateId templateId,
                long currentRevision,
                TemplateApplication.Readiness readiness
        ) {
            throw new AssertionError("unexpected updateReadiness");
        }
        private TemplateApplication.TemplateId first;
        private TemplateApplication.TemplateId second;
        private int calls;

        @Override
        public LocateOutcome locate(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("unexpected locate");
        }

        @Override
        public LoadCurrentOutcome loadCurrent(TemplateApplication.TemplateId templateId) {
            throw new AssertionError("unexpected loadCurrent");
        }

        @Override
        public CreateOutcome create(CreateCommit commit) {
            calls++;
            if (calls == 1) {
                first = commit.templateId();
                return new IdCollision();
            }
            second = commit.templateId();
            return new Created();
        }

        @Override
        public AppendOutcome append(AppendCommit commit) {
            throw new AssertionError("unexpected append");
        }
    }
}
