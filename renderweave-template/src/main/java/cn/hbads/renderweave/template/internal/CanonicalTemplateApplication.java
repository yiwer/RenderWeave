package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateDependencyProjection;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.InvalidCommitConfirmationAuthority;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

final class CanonicalTemplateApplication implements TemplateApplication {
    private final DesignDslAuthority designs;
    private final OwnerScopeAuthority ownerScopes;
    private final TemplatePersistence persistence;
    private final StaticSchemaAuthority schemas;
    private final AssetRefAtomExtractor extractor;
    private final TemplateDependencyEvaluator dependencies;
    private final InvalidCommitConfirmationAuthority confirmations;

    CanonicalTemplateApplication(
            DesignDslAuthority designs,
            OwnerScopeAuthority ownerScopes,
            TemplatePersistence persistence,
            StaticSchemaAuthority schemas,
            DependencyResolution resolution,
            InvalidCommitConfirmationAuthority confirmations
    ) {
        this.designs = Objects.requireNonNull(designs, "designs");
        this.ownerScopes = Objects.requireNonNull(ownerScopes, "ownerScopes");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.extractor = new AssetRefAtomExtractor();
        this.dependencies = new TemplateDependencyEvaluator(
                Objects.requireNonNull(resolution, "resolution"),
                schemas,
                designs
        );
        this.confirmations = Objects.requireNonNull(confirmations, "confirmations");
    }

    private TemplateDependencyProjection projectionOf(byte[] canonicalUtf8) {
        return extractor.extract(canonicalUtf8);
    }

    private TemplateDependencyEvaluator.Evaluation evaluate(
            TemplateDependencyProjection projection,
            byte[] canonicalDesignDslUtf8,
            StaticSchemaRef rootSchema,
            String templateId,
            OwnerScopeAuthority.OwnerScope ownerScope
    ) {
        return dependencies.evaluate(
                projection,
                canonicalDesignDslUtf8,
                rootSchema,
                templateId,
                ownerScope
        );
    }

    @Override
    public CreateOutcome create(TemplateInvocationRef invocation, CreateCommand command) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(command, "command");

        var createDecision = ownerScopes.authorizeCreate(invocation);
        if (createDecision instanceof OwnerScopeAuthority.CreateDenied) {
            return new CreateForbidden();
        }
        if (createDecision instanceof OwnerScopeAuthority.CreateUnavailable) {
            return new CreateAuthorityUnavailable();
        }
        var granted = (OwnerScopeAuthority.CreateGranted) createDecision;

        var admission = designs.admit(command.rawDesignDslUtf8());
        if (admission instanceof DesignDslAuthority.Rejected rejected) {
            return new CreateDesignRejected(rejected);
        }
        var admitted = (DesignDslAuthority.Admitted) admission;

        var schemaResolution = schemas.resolve(command.staticSchema());
        if (schemaResolution instanceof StaticSchemaAuthority.NotFound) {
            return new CreateStaticSchemaNotFound();
        }
        if (schemaResolution instanceof StaticSchemaAuthority.Unavailable) {
            return new CreatePersistenceUnavailable();
        }
        if (!((StaticSchemaAuthority.Resolved) schemaResolution)
                .reference().equals(command.staticSchema())) {
            return new CreatePersistenceUnavailable();
        }

        var recheck = ownerScopes.recheck(granted.recheckIdentity());
        if (recheck instanceof OwnerScopeAuthority.RecheckDenied) {
            return new CreateForbidden();
        }
        if (recheck instanceof OwnerScopeAuthority.RecheckUnavailable) {
            return new CreateAuthorityUnavailable();
        }

        var projection = projectionOf(admitted.canonicalUtf8());
        TemplateId templateId = null;
        TemplateApplication.Readiness computedReadiness = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            var candidate = TemplateId.of(UUID.randomUUID().toString());
            TemplateDependencyEvaluator.Evaluation evaluation;
            try {
                evaluation = evaluate(
                        projection,
                        admitted.canonicalUtf8(),
                        command.staticSchema(),
                        candidate.value(),
                        granted.ownerScope()
                );
            } catch (TemplateDependencyEvaluator.Unavailable unavailable) {
                return new CreateDependencyUnavailable();
            }
            if (evaluation.classification() != TemplateDependencyEvaluator.Classification.READY) {
                return new CreateDependencyRejected(evaluation.report());
            }
            computedReadiness = TemplateApplication.Readiness.READY;
            var committed = persistence.create(new AdmittedCreateCommit(
                    candidate,
                    granted.ownerScope(),
                    command.staticSchema(),
                    admitted.canonicalUtf8(),
                    admitted.contentHash(),
                    computedReadiness,
                    projection,
                    evaluation.snapshot()
            ));
            if (committed instanceof TemplatePersistence.Created) {
                templateId = candidate;
                break;
            }
            if (committed instanceof TemplatePersistence.CreateUnavailable) {
                return new CreatePersistenceUnavailable();
            }
            if (committed instanceof TemplatePersistence.CreateDependencyDrift) {
                continue;
            }
        }
        if (templateId == null) {
            return new CreatePersistenceUnavailable();
        }

        if (granted.disclosure() == OwnerScopeAuthority.Disclosure.OPAQUE) {
            return new CreatedOpaque(templateId);
        }
        return new CreatedReadable(new Current(
                templateId,
                0,
                command.staticSchema(),
                admitted.canonicalUtf8(),
                admitted.contentHash(),
                computedReadiness
        ));
    }

    @Override
    public CatalogOutcome catalog(TemplateInvocationRef invocation, CatalogCommand command) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(command, "command");

        var decision = ownerScopes.authorizeCatalog(invocation);
        if (decision instanceof OwnerScopeAuthority.CatalogDenied) {
            return new CatalogForbidden();
        }
        if (decision instanceof OwnerScopeAuthority.CatalogUnavailable) {
            return new CatalogAuthorityUnavailable();
        }
        var granted = (OwnerScopeAuthority.CatalogGranted) decision;
        var persisted = persistence.catalog(new TemplatePersistence.CatalogQuery(
                granted.ownerScope(),
                command.search(),
                command.cursor(),
                command.limit()
        ));
        if (persisted instanceof TemplatePersistence.CatalogInvalidCursor) {
            return new CatalogInvalidCursor();
        }
        if (persisted instanceof TemplatePersistence.CatalogUnavailable) {
            return new CatalogPersistenceUnavailable();
        }
        var page = (TemplatePersistence.CatalogPage) persisted;
        var entries = new ArrayList<TemplateApplication.CatalogEntry>();
        for (var entry : page.entries()) {
            if (!granted.ownerScope().equals(entry.ownerScope())
                    || entry.lifecycle() != TemplatePersistence.Lifecycle.ACTIVE) {
                return new CatalogPersistenceUnavailable();
            }
            entries.add(new TemplateApplication.CatalogEntry(
                    entry.templateId(),
                    entry.displayName(),
                    entry.staticSchema(),
                    entry.currentRevision(),
                    entry.readiness(),
                    entry.updatedAt()
            ));
        }
        return new TemplateApplication.CatalogPage(entries, page.nextCursor());
    }

    @Override
    public CurrentOutcome getCurrent(TemplateInvocationRef invocation, TemplateId templateId) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(templateId, "templateId");

        var locate = persistence.locate(templateId);
        if (locate instanceof TemplatePersistence.LocateNotFound) {
            return new CurrentNotFound();
        }
        if (locate instanceof TemplatePersistence.LocateUnavailable) {
            return new CurrentPersistenceUnavailable();
        }
        var metadata = ((TemplatePersistence.Located) locate).metadata();

        var access = ownerScopes.authorizeExisting(
                invocation,
                metadata.ownerScope(),
                OwnerScopeAuthority.ExistingOperation.READ
        );
        if (access instanceof OwnerScopeAuthority.ExistingHidden
                || access instanceof OwnerScopeAuthority.ExistingForbidden) {
            return new CurrentNotFound();
        }
        if (access instanceof OwnerScopeAuthority.ExistingUnavailable) {
            return new CurrentAuthorityUnavailable();
        }
        var granted = (OwnerScopeAuthority.ExistingGranted) access;
        if (granted.disclosure() != OwnerScopeAuthority.Disclosure.READABLE) {
            return new CurrentNotFound();
        }
        if (metadata.lifecycle() == TemplatePersistence.Lifecycle.DELETED) {
            return new CurrentDeleted();
        }

        var loaded = persistence.loadCurrent(templateId);
        if (loaded instanceof TemplatePersistence.CurrentNotFound) {
            return new CurrentNotFound();
        }
        if (loaded instanceof TemplatePersistence.CurrentLoadUnavailable) {
            return new CurrentPersistenceUnavailable();
        }
        var stored = ((TemplatePersistence.CurrentLoaded) loaded).current();
        if (!metadata.equals(stored.metadata())) {
            return new CurrentIntegrityMismatch();
        }
        var verified = verify(stored);
        if (verified == null) {
            return new CurrentIntegrityMismatch();
        }
        return new CurrentReadable(new Current(
                metadata.templateId(),
                metadata.currentRevision(),
                metadata.staticSchema(),
                verified.canonicalUtf8(),
                verified.contentHash(),
                stored.readiness()
        ));
    }

    @Override
    public RecheckCurrentOutcome recheckCurrent(
            TemplateInvocationRef invocation,
            TemplateId templateId
    ) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(templateId, "templateId");

        for (int attempt = 0; attempt < 3; attempt++) {
            var locate = persistence.locate(templateId);
            if (locate instanceof TemplatePersistence.LocateNotFound) {
                return new RecheckCurrentNotFound();
            }
            if (locate instanceof TemplatePersistence.LocateUnavailable) {
                return new RecheckCurrentPersistenceUnavailable();
            }
            var metadata = ((TemplatePersistence.Located) locate).metadata();
            var access = ownerScopes.authorizeExisting(
                    invocation,
                    metadata.ownerScope(),
                    OwnerScopeAuthority.ExistingOperation.READ
            );
            if (access instanceof OwnerScopeAuthority.ExistingHidden
                    || access instanceof OwnerScopeAuthority.ExistingForbidden) {
                return new RecheckCurrentNotFound();
            }
            if (access instanceof OwnerScopeAuthority.ExistingUnavailable) {
                return new RecheckCurrentAuthorityUnavailable();
            }
            if (((OwnerScopeAuthority.ExistingGranted) access).disclosure()
                    != OwnerScopeAuthority.Disclosure.READABLE) {
                return new RecheckCurrentNotFound();
            }
            if (metadata.lifecycle() == TemplatePersistence.Lifecycle.DELETED) {
                return new RecheckCurrentDeleted();
            }
            var loaded = persistence.loadCurrent(templateId);
            if (loaded instanceof TemplatePersistence.CurrentNotFound) {
                return new RecheckCurrentNotFound();
            }
            if (loaded instanceof TemplatePersistence.CurrentLoadUnavailable) {
                return new RecheckCurrentPersistenceUnavailable();
            }
            var stored = ((TemplatePersistence.CurrentLoaded) loaded).current();
            var admitted = verify(stored);
            if (!metadata.equals(stored.metadata()) || admitted == null) {
                return new RecheckCurrentIntegrityMismatch();
            }
            var current = new Current(
                    templateId,
                    metadata.currentRevision(),
                    metadata.staticSchema(),
                    admitted.canonicalUtf8(),
                    admitted.contentHash(),
                    stored.readiness()
            );
            TemplateDependencyEvaluator.Evaluation evaluation;
            try {
                evaluation = evaluate(
                        projectionOf(current.canonicalDesignDslUtf8()),
                        current.canonicalDesignDslUtf8(),
                        current.staticSchema(),
                        templateId.value(),
                        metadata.ownerScope()
                );
            } catch (TemplateDependencyEvaluator.Unavailable unavailable) {
                return new RecheckCurrentDependencyUnavailable();
            }
            var readiness = evaluation.readiness();
            var updated = persistence.updateReadiness(
                    templateId,
                    current.revision(),
                    readiness,
                    evaluation.snapshot()
            );
            if (updated instanceof TemplatePersistence.ReadinessUpdated) {
                return new CurrentRechecked(new Current(
                        current.templateId(),
                        current.revision(),
                        current.staticSchema(),
                        current.canonicalDesignDslUtf8(),
                        current.contentHash(),
                        readiness
                ));
            }
            if (updated instanceof TemplatePersistence.ReadinessUnavailable) {
                return new RecheckCurrentPersistenceUnavailable();
            }
            // Current moved or disappeared between the trusted read and guarded update.
            // Re-enter the complete authorized read path, but never spin indefinitely.
        }
        return new RecheckCurrentDrifted();
    }

    @Override
    public SaveOutcome save(TemplateInvocationRef invocation, SaveCommand command) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(command, "command");

        var locate = persistence.locate(command.templateId());
        if (locate instanceof TemplatePersistence.LocateNotFound) {
            return new SaveNotFound();
        }
        if (locate instanceof TemplatePersistence.LocateUnavailable) {
            return new SavePersistenceUnavailable();
        }
        var metadata = ((TemplatePersistence.Located) locate).metadata();

        var access = ownerScopes.authorizeExisting(
                invocation,
                metadata.ownerScope(),
                OwnerScopeAuthority.ExistingOperation.UPDATE
        );
        if (access instanceof OwnerScopeAuthority.ExistingHidden) {
            return new SaveNotFound();
        }
        if (access instanceof OwnerScopeAuthority.ExistingForbidden) {
            return new SaveForbidden();
        }
        if (access instanceof OwnerScopeAuthority.ExistingUnavailable) {
            return new SaveAuthorityUnavailable();
        }
        var granted = (OwnerScopeAuthority.ExistingGranted) access;
        if (metadata.lifecycle() == TemplatePersistence.Lifecycle.DELETED) {
            return new SaveDeleted();
        }

        var admission = designs.admit(command.rawDesignDslUtf8());
        if (admission instanceof DesignDslAuthority.Rejected rejected) {
            return new SaveDesignRejected(rejected);
        }
        var admitted = (DesignDslAuthority.Admitted) admission;

        var schema = schemas.resolve(metadata.staticSchema());
        if (schema instanceof StaticSchemaAuthority.NotFound) {
            return new SaveIntegrityMismatch();
        }
        if (schema instanceof StaticSchemaAuthority.Unavailable) {
            return new SavePersistenceUnavailable();
        }
        if (!((StaticSchemaAuthority.Resolved) schema)
                .reference().equals(metadata.staticSchema())) {
            return new SaveIntegrityMismatch();
        }

        var loaded = persistence.loadCurrent(command.templateId());
        if (loaded instanceof TemplatePersistence.CurrentNotFound) {
            return new SaveIntegrityMismatch();
        }
        if (loaded instanceof TemplatePersistence.CurrentLoadUnavailable) {
            return new SavePersistenceUnavailable();
        }
        var stored = ((TemplatePersistence.CurrentLoaded) loaded).current();
        if (!metadata.equals(stored.metadata()) || verify(stored) == null) {
            return new SaveIntegrityMismatch();
        }
        if (metadata.currentRevision() != command.expectedRevision()) {
            return conflict(metadata.currentRevision(), granted.disclosure());
        }

        var recheck = ownerScopes.recheck(granted.recheckIdentity());
        if (recheck instanceof OwnerScopeAuthority.RecheckDenied) {
            return new SaveForbidden();
        }
        if (recheck instanceof OwnerScopeAuthority.RecheckUnavailable) {
            return new SaveAuthorityUnavailable();
        }

        var projection = projectionOf(admitted.canonicalUtf8());
        for (int dependencyAttempt = 0; dependencyAttempt < 3; dependencyAttempt++) {
            TemplateDependencyEvaluator.Evaluation evaluation;
            try {
                evaluation = evaluate(
                        projection,
                        admitted.canonicalUtf8(),
                        metadata.staticSchema(),
                        command.templateId().value(),
                        metadata.ownerScope()
                );
            } catch (TemplateDependencyEvaluator.Unavailable unavailable) {
                return new SaveDependencyUnavailable();
            }

            if (evaluation.classification()
                    == TemplateDependencyEvaluator.Classification.HARD_ERROR) {
                return new SaveDependencyRejected(evaluation.report());
            }

            var claims = confirmationClaims(granted, metadata, command, admitted, evaluation);
            if (evaluation.classification()
                    == TemplateDependencyEvaluator.Classification.DEPENDENCY_ERROR) {
                if (command.confirmationToken().isEmpty()) {
                    var offer = issueOffer(
                            claims, admitted.contentHash(), evaluation.report());
                    return offer.<SaveOutcome>map(SaveConfirmationRequired::new)
                            .orElseGet(SaveConfirmationUnavailable::new);
                }
                var verified = confirmations.verify(
                        command.confirmationToken().orElseThrow(),
                        claims
                );
                if (verified instanceof InvalidCommitConfirmationAuthority.Invalid) {
                    return new SaveConfirmationInvalid();
                }
                if (verified instanceof InvalidCommitConfirmationAuthority.Expired) {
                    return new SaveConfirmationExpired();
                }
                if (verified instanceof InvalidCommitConfirmationAuthority.Stale) {
                    var replacement = issueOffer(
                            claims, admitted.contentHash(), evaluation.report());
                    if (replacement.isEmpty()) {
                        return new SaveConfirmationUnavailable();
                    }
                    return new SaveConfirmationStale(replacement);
                }
                if (verified instanceof InvalidCommitConfirmationAuthority.VerifyUnavailable) {
                    return new SaveConfirmationUnavailable();
                }
            } else if (command.confirmationToken().isPresent()) {
                return new SaveConfirmationStale(Optional.empty());
            }

            var readiness = evaluation.readiness();
            var appended = persistence.append(new AdmittedAppendCommit(
                    command.templateId(),
                    metadata.ownerScope(),
                    metadata.staticSchema(),
                    command.expectedRevision(),
                    admitted.canonicalUtf8(),
                    admitted.contentHash(),
                    readiness,
                    projection,
                    evaluation.snapshot()
            ));
            if (appended instanceof TemplatePersistence.AppendNotFound) {
                return new SaveNotFound();
            }
            if (appended instanceof TemplatePersistence.AppendDeleted) {
                return new SaveDeleted();
            }
            if (appended instanceof TemplatePersistence.AppendRevisionConflict conflict) {
                return conflict(conflict.currentRevision(), granted.disclosure());
            }
            if (appended instanceof TemplatePersistence.AppendDependencyDrift) {
                if (command.confirmationToken().isPresent()) {
                    return freshOutcomeAfterConfirmedDependencyDrift(
                            projection, granted, metadata, command, admitted);
                }
                continue;
            }
            if (appended instanceof TemplatePersistence.AppendUnavailable) {
                return new SavePersistenceUnavailable();
            }

            if (granted.disclosure() == OwnerScopeAuthority.Disclosure.OPAQUE) {
                return new SavedOpaque(command.templateId());
            }
            return new SavedReadable(new Current(
                    command.templateId(),
                    command.expectedRevision() + 1,
                    metadata.staticSchema(),
                    admitted.canonicalUtf8(),
                    admitted.contentHash(),
                    readiness
            ));
        }
        return new SaveDependencyUnavailable();
    }

    private SaveOutcome freshOutcomeAfterConfirmedDependencyDrift(
            TemplateDependencyProjection projection,
            OwnerScopeAuthority.ExistingGranted granted,
            TemplatePersistence.TemplateMetadata metadata,
            SaveCommand command,
            DesignDslAuthority.Admitted admitted
    ) {
        TemplateDependencyEvaluator.Evaluation fresh;
        try {
            fresh = evaluate(
                    projection,
                    admitted.canonicalUtf8(),
                    metadata.staticSchema(),
                    command.templateId().value(),
                    metadata.ownerScope()
            );
        } catch (TemplateDependencyEvaluator.Unavailable unavailable) {
            return new SaveDependencyUnavailable();
        }
        if (fresh.classification() == TemplateDependencyEvaluator.Classification.HARD_ERROR) {
            return new SaveDependencyRejected(fresh.report());
        }
        if (fresh.classification() == TemplateDependencyEvaluator.Classification.READY) {
            return new SaveConfirmationStale(Optional.empty());
        }
        var claims = confirmationClaims(granted, metadata, command, admitted, fresh);
        var replacement = issueOffer(claims, admitted.contentHash(), fresh.report());
        return replacement.<SaveOutcome>map(
                        offer -> new SaveConfirmationStale(Optional.of(offer)))
                .orElseGet(SaveConfirmationUnavailable::new);
    }

    private InvalidCommitConfirmationAuthority.Claims confirmationClaims(
            OwnerScopeAuthority.ExistingGranted granted,
            TemplatePersistence.TemplateMetadata metadata,
            SaveCommand command,
            DesignDslAuthority.Admitted admitted,
            TemplateDependencyEvaluator.Evaluation evaluation
    ) {
        return new InvalidCommitConfirmationAuthority.Claims(
                InvalidCommitConfirmationAuthority.Operation.SAVE,
                granted.actorId(),
                metadata.ownerScope(),
                command.templateId(),
                command.expectedRevision(),
                metadata.staticSchema(),
                admitted.contentHash(),
                evaluation.report().fingerprint(),
                evaluation.snapshot().fingerprint()
        );
    }

    private Optional<InvalidCommitConfirmationOffer> issueOffer(
            InvalidCommitConfirmationAuthority.Claims claims,
            String proposedContentHash,
            ValidationReport report
    ) {
        var issued = confirmations.issue(claims);
        if (issued instanceof InvalidCommitConfirmationAuthority.Issued offer) {
            return Optional.of(new InvalidCommitConfirmationOffer(
                    offer.confirmationToken(),
                    offer.expiresAt(),
                    proposedContentHash,
                    report
            ));
        }
        return Optional.empty();
    }

    private SaveRevisionConflict conflict(
            long currentRevision,
            OwnerScopeAuthority.Disclosure disclosure
    ) {
        return new SaveRevisionConflict(
                disclosure == OwnerScopeAuthority.Disclosure.READABLE
                        ? OptionalLong.of(currentRevision)
                        : OptionalLong.empty()
        );
    }

    private DesignDslAuthority.Admitted verify(TemplatePersistence.StoredCurrent stored) {
        var admission = designs.admit(stored.storedJsonUtf8());
        if (!(admission instanceof DesignDslAuthority.Admitted admitted)) {
            return null;
        }
        if (!Arrays.equals(admitted.canonicalUtf8(), stored.canonicalDesignDslUtf8())
                || !admitted.contentHash().equals(stored.contentHash())) {
            return null;
        }
        return admitted;
    }
}
