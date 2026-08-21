package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.InvalidCommitConfirmationAuthority;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvalidSaveConfirmationContractTest {
    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));
    private static final OwnerScopeAuthority.OwnerScope OWNER =
            new OwnerScopeAuthority.OwnerScope("owner-confirmation");
    private static final TemplateApplication.TemplateId TEMPLATE_ID =
            TemplateApplication.TemplateId.of("00000000-0000-4000-8000-000000000001");
    private static final String MISSING_ASSET =
            "00000000-0000-4000-8000-0000000000aa";
    private static final String CHILD =
            "00000000-0000-4000-8000-0000000000bb";
    private static final byte[] READY_DESIGN = design("");
    private static final byte[] MISSING_ASSET_DESIGN = design(
            "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000011\"," +
                    "\"kind\":\"image\",\"bindings\":[]," +
                    "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0," +
                    "\"widthMode\":\"FIXED\",\"widthMm\":10," +
                    "\"heightMode\":\"FIXED\",\"heightMm\":10}," +
                    "\"imageRef\":{\"assetId\":\"" + MISSING_ASSET + "\"}}]"
    );
    private static final byte[] CHILD_USE_DESIGN = design(
            "\"children\":[{\"nodeId\":\"00000000-0000-4000-8000-000000000012\"," +
                    "\"kind\":\"templateUse\",\"bindings\":[]," +
                    "\"useId\":\"00000000-0000-4000-8000-000000000013\"," +
                    "\"templateRef\":{\"templateId\":\"" + CHILD + "\"}," +
                    "\"contextSelector\":{\"kind\":\"empty\"},\"fills\":[]," +
                    "\"placement\":{\"type\":\"ABSOLUTE\",\"xMm\":0,\"yMm\":0," +
                    "\"widthMode\":\"HUG_CONTENT\",\"heightMode\":\"HUG_CONTENT\"}}]"
    );

    @Test
    void strictCreateRejectsDependencyErrorsWithoutIssuingOrWriting() {
        var fixture = fixture(Verification.VERIFIED, missingDependencies());

        var outcome = fixture.application.create(
                invocation(),
                new TemplateApplication.CreateCommand(SCHEMA, MISSING_ASSET_DESIGN)
        );

        var rejected = assertInstanceOf(
                TemplateApplication.CreateDependencyRejected.class, outcome);
        assertTrue(rejected.report().confirmable());
        assertEquals("TEMPLATE_ASSET_NOT_FOUND", rejected.report().problems().getFirst().code());
        assertEquals(0, fixture.persistence.creates);
        assertEquals(0, fixture.confirmations.issues);
    }

    @Test
    void firstDependencyInvalidSaveIssuesBoundOfferAndPerformsZeroWrites() {
        var fixture = fixture(Verification.VERIFIED, missingDependencies());

        var outcome = fixture.application.save(
                invocation(),
                new TemplateApplication.SaveCommand(TEMPLATE_ID, 0, MISSING_ASSET_DESIGN)
        );

        var required = assertInstanceOf(
                TemplateApplication.SaveConfirmationRequired.class, outcome);
        assertEquals(64, required.offer().confirmationToken().length());
        assertEquals(Instant.parse("2030-01-01T00:05:00Z"), required.offer().expiresAt());
        assertTrue(required.offer().report().confirmable());
        assertEquals(required.offer().proposedContentHash(),
                fixture.confirmations.issuedClaims.contentHash());
        assertEquals(0, fixture.persistence.appends);
        assertEquals(1, fixture.confirmations.issues);
        assertEquals("actor-confirmation", fixture.confirmations.issuedClaims.actorId());
    }

    @Test
    void exactConfirmedSaveAppendsOneInvalidRevision() {
        var fixture = fixture(Verification.VERIFIED, missingDependencies());
        var first = assertInstanceOf(
                TemplateApplication.SaveConfirmationRequired.class,
                fixture.application.save(
                        invocation(),
                        new TemplateApplication.SaveCommand(TEMPLATE_ID, 0, MISSING_ASSET_DESIGN)
                )
        );

        var confirmed = fixture.application.save(
                invocation(),
                new TemplateApplication.SaveCommand(
                        TEMPLATE_ID,
                        0,
                        MISSING_ASSET_DESIGN,
                        first.offer().confirmationToken()
                )
        );

        var saved = assertInstanceOf(TemplateApplication.SavedReadable.class, confirmed);
        assertEquals(1, saved.current().revision());
        assertEquals(TemplateApplication.Readiness.INVALID, saved.current().readiness());
        assertEquals(1, fixture.persistence.appends);
        assertEquals(TemplateApplication.Readiness.INVALID,
                fixture.persistence.appended.readiness());
        assertEquals(1, fixture.confirmations.verifies);
    }

    @Test
    void cycleIsHardAndNeverIssuesAConfirmationOrAppends() {
        var resolution = new ResolutionScript();
        resolution.templates = new DependencyResolution.TemplateResolved(
                new DependencyResolution.TemplateState(
                        CHILD,
                        OWNER,
                        2,
                        DependencyResolution.Lifecycle.ACTIVE,
                        TemplateApplication.Readiness.READY,
                        SCHEMA,
                        TemplateTestData.emptyDesignContentHash(),
                        List.of(new DependencyResolution.TemplateUseEdge(
                                TEMPLATE_ID.value(),
                                "/designRoot/children/0/templateRef"
                        )),
                        TemplateTestData.emptyDesignCanonical()
                )
        );
        var fixture = fixture(Verification.VERIFIED, resolution);

        var rejected = assertInstanceOf(
                TemplateApplication.SaveDependencyRejected.class,
                fixture.application.save(
                        invocation(),
                        new TemplateApplication.SaveCommand(TEMPLATE_ID, 0, CHILD_USE_DESIGN)
                )
        );

        assertFalse(rejected.report().confirmable());
        assertEquals("TEMPLATE_REF_CYCLE", rejected.report().problems().getFirst().code());
        assertEquals(0, fixture.confirmations.issues);
        assertEquals(0, fixture.persistence.appends);
    }

    @Test
    void invalidExpiredAndStaleTokensAreDistinctZeroWriteOutcomes() {
        assertTokenOutcome(Verification.INVALID, TemplateApplication.SaveConfirmationInvalid.class);
        assertTokenOutcome(Verification.EXPIRED, TemplateApplication.SaveConfirmationExpired.class);

        var fixture = fixture(Verification.STALE, missingDependencies());
        var offer = offer(fixture);
        var stale = assertInstanceOf(
                TemplateApplication.SaveConfirmationStale.class,
                fixture.application.save(
                        invocation(),
                        new TemplateApplication.SaveCommand(
                                TEMPLATE_ID, 0, MISSING_ASSET_DESIGN,
                                offer.confirmationToken())
                )
        );
        assertTrue(stale.replacement().isPresent());
        assertEquals(2, fixture.confirmations.issues);
        assertEquals(0, fixture.persistence.appends);
    }

    @Test
    void commitFenceDriftReevaluatesAndIssuesAFreshOfferWhenStillConfirmable() {
        var fixture = fixture(Verification.VERIFIED, missingDependencies());
        var first = offer(fixture);
        fixture.persistence.dependencyDriftsRemaining = 1;

        var outcome = fixture.application.save(
                invocation(),
                new TemplateApplication.SaveCommand(
                        TEMPLATE_ID,
                        0,
                        MISSING_ASSET_DESIGN,
                        first.confirmationToken()
                )
        );

        var stale = assertInstanceOf(
                TemplateApplication.SaveConfirmationStale.class, outcome);
        var replacement = stale.replacement().orElseThrow();
        assertFalse(replacement.confirmationToken().equals(first.confirmationToken()));
        assertEquals(first.report(), replacement.report());
        assertEquals(2, fixture.confirmations.issues);
        assertEquals(1, fixture.persistence.appends);
        assertEquals(0, fixture.persistence.successfulAppends);
    }

    @Test
    void readySaveRetriesOneCommitFenceDriftAgainstAFreshSnapshot() {
        var fixture = fixture(Verification.VERIFIED, missingDependencies());
        fixture.persistence.dependencyDriftsRemaining = 1;

        var outcome = fixture.application.save(
                invocation(),
                new TemplateApplication.SaveCommand(TEMPLATE_ID, 0, READY_DESIGN)
        );

        assertInstanceOf(TemplateApplication.SavedReadable.class, outcome);
        assertEquals(2, fixture.persistence.appends);
        assertEquals(1, fixture.persistence.successfulAppends);
        assertEquals(0, fixture.confirmations.issues);
    }

    @Test
    void readySaveStopsAfterThreeCommitFenceDrifts() {
        var fixture = fixture(Verification.VERIFIED, missingDependencies());
        fixture.persistence.dependencyDriftsRemaining = 3;

        var outcome = fixture.application.save(
                invocation(),
                new TemplateApplication.SaveCommand(TEMPLATE_ID, 0, READY_DESIGN)
        );

        assertInstanceOf(TemplateApplication.SaveDependencyUnavailable.class, outcome);
        assertEquals(3, fixture.persistence.appends);
        assertEquals(0, fixture.persistence.successfulAppends);
        assertEquals(0, fixture.confirmations.issues);
    }

    private static void assertTokenOutcome(
            Verification verification,
            Class<? extends TemplateApplication.SaveOutcome> expected
    ) {
        var fixture = fixture(verification, missingDependencies());
        var offer = offer(fixture);
        assertInstanceOf(
                expected,
                fixture.application.save(
                        invocation(),
                        new TemplateApplication.SaveCommand(
                                TEMPLATE_ID, 0, MISSING_ASSET_DESIGN,
                                offer.confirmationToken())
                )
        );
        assertEquals(0, fixture.persistence.appends);
    }

    private static TemplateApplication.InvalidCommitConfirmationOffer offer(Fixture fixture) {
        return assertInstanceOf(
                TemplateApplication.SaveConfirmationRequired.class,
                fixture.application.save(
                        invocation(),
                        new TemplateApplication.SaveCommand(TEMPLATE_ID, 0, MISSING_ASSET_DESIGN)
                )
        ).offer();
    }

    private static Fixture fixture(Verification verification, ResolutionScript resolution) {
        var persistence = new PersistenceScript();
        var confirmations = new ConfirmationScript(verification);
        var application = TemplateModule.application(
                new AuthorityScript(),
                persistence,
                TemplateTestData::resolvedEmpty,
                resolution,
                confirmations
        );
        return new Fixture(application, persistence, confirmations);
    }

    private static ResolutionScript missingDependencies() {
        return new ResolutionScript();
    }

    private static TemplateApplication.TemplateInvocationRef invocation() {
        return TemplateApplication.TemplateInvocationRef.serverCreated("confirmation-contract");
    }

    private static byte[] design(String children) {
        return ("{\"dslVersion\":\"renderweave-design/1.0\"," +
                "\"expressionProfile\":\"renderweave-expression/1.0\"," +
                "\"displayName\":\"Confirmation fixture\",\"definitions\":[]," +
                "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000010\"," +
                "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,\"bindings\":[]" +
                (children.isEmpty() ? ",\"children\":[]" : "," + children) + "}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(
            TemplateApplication application,
            PersistenceScript persistence,
            ConfirmationScript confirmations
    ) {
    }

    private enum Verification {
        VERIFIED,
        INVALID,
        EXPIRED,
        STALE
    }

    private static final class ConfirmationScript
            implements InvalidCommitConfirmationAuthority {
        private static final String TOKEN = "a".repeat(64);
        private final Verification verification;
        private int issues;
        private int verifies;
        private Claims issuedClaims;

        private ConfirmationScript(Verification verification) {
            this.verification = verification;
        }

        @Override
        public IssueOutcome issue(Claims claims) {
            issues++;
            issuedClaims = claims;
            return new Issued(
                    String.valueOf((char) ('a' + issues - 1)).repeat(64),
                    Instant.parse("2030-01-01T00:05:00Z")
            );
        }

        @Override
        public VerifyOutcome verify(String confirmationToken, Claims expectedClaims) {
            verifies++;
            assertEquals(TOKEN, confirmationToken);
            return switch (verification) {
                case VERIFIED -> new Verified();
                case INVALID -> new Invalid();
                case EXPIRED -> new Expired();
                case STALE -> new Stale();
            };
        }
    }

    private static final class AuthorityScript implements OwnerScopeAuthority {
        @Override
        public CreateDecision authorizeCreate(TemplateApplication.TemplateInvocationRef invocation) {
            return new CreateGranted(
                    OWNER,
                    new RecheckIdentity("create-recheck"),
                    Disclosure.READABLE
            );
        }

        @Override
        public ExistingDecision authorizeExisting(
                TemplateApplication.TemplateInvocationRef invocation,
                OwnerScope storedOwnerScope,
                ExistingOperation operation
        ) {
            assertEquals(OWNER, storedOwnerScope);
            return new ExistingGranted(
                    Disclosure.READABLE,
                    new RecheckIdentity("save-recheck"),
                    "actor-confirmation"
            );
        }

        @Override
        public RecheckDecision recheck(RecheckIdentity identity) {
            return new RecheckGranted();
        }
    }

    private static final class ResolutionScript implements DependencyResolution {
        private TemplateResolution templates = new TemplateMissing();

        @Override
        public AssetResolution resolveAsset(String assetId) {
            assertEquals(MISSING_ASSET, assetId);
            return new AssetMissing();
        }

        @Override
        public TemplateResolution resolveTemplate(String targetTemplateId) {
            assertEquals(CHILD, targetTemplateId);
            return templates;
        }
    }

    private static final class PersistenceScript implements TemplatePersistence {
        private final StoredCurrent current;
        private int creates;
        private int appends;
        private int successfulAppends;
        private int dependencyDriftsRemaining;
        private AppendCommit appended;

        private PersistenceScript() {
            var admitted = assertInstanceOf(
                    DesignDslAuthority.Admitted.class,
                    new CanonicalDesignDslAuthority().admit(READY_DESIGN)
            );
            current = new StoredCurrent(
                    new TemplateMetadata(TEMPLATE_ID, OWNER, SCHEMA, 0, Lifecycle.ACTIVE),
                    READY_DESIGN,
                    admitted.canonicalUtf8(),
                    admitted.contentHash(),
                    TemplateApplication.Readiness.READY
            );
        }

        @Override
        public LocateOutcome locate(TemplateApplication.TemplateId templateId) {
            assertEquals(TEMPLATE_ID, templateId);
            return new Located(current.metadata());
        }

        @Override
        public LoadCurrentOutcome loadCurrent(TemplateApplication.TemplateId templateId) {
            assertEquals(TEMPLATE_ID, templateId);
            return new CurrentLoaded(current);
        }

        @Override
        public CreateOutcome create(CreateCommit commit) {
            creates++;
            return new Created();
        }

        @Override
        public AppendOutcome append(AppendCommit commit) {
            appends++;
            appended = commit;
            if (dependencyDriftsRemaining > 0) {
                dependencyDriftsRemaining--;
                return new AppendDependencyDrift();
            }
            successfulAppends++;
            return new Appended();
        }

        @Override
        public LoadUseTargetsOutcome loadUseTargets(TemplateApplication.TemplateId templateId) {
            return new UseTargetsLoaded(List.of());
        }

        @Override
        public FindAssetReferencesOutcome findAssetReferences(String assetId) {
            throw new AssertionError("unexpected findAssetReferences");
        }

        @Override
        public UpdateReadinessOutcome updateReadiness(
                TemplateApplication.TemplateId templateId,
                long currentRevision,
                TemplateApplication.Readiness readiness,
                cn.hbads.renderweave.template.spi.TemplateDependencySnapshot dependencySnapshot
        ) {
            throw new AssertionError("unexpected updateReadiness");
        }
    }
}
