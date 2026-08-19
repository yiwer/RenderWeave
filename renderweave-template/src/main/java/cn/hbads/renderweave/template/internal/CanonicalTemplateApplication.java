package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;

import java.util.Objects;
import java.util.UUID;
import java.util.Arrays;
import java.util.OptionalLong;

final class CanonicalTemplateApplication implements TemplateApplication {
    private final DesignDslAuthority designs;
    private final OwnerScopeAuthority ownerScopes;
    private final TemplatePersistence persistence;
    private final StaticSchemaAuthority schemas;

    CanonicalTemplateApplication(
            DesignDslAuthority designs,
            OwnerScopeAuthority ownerScopes,
            TemplatePersistence persistence,
            StaticSchemaAuthority schemas
    ) {
        this.designs = Objects.requireNonNull(designs, "designs");
        this.ownerScopes = Objects.requireNonNull(ownerScopes, "ownerScopes");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
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

        TemplateId templateId = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            var candidate = TemplateId.of(UUID.randomUUID().toString());
            var committed = persistence.create(new AdmittedCreateCommit(
                    candidate,
                    granted.ownerScope(),
                    command.staticSchema(),
                    admitted.canonicalUtf8(),
                    admitted.contentHash()
            ));
            if (committed instanceof TemplatePersistence.Created) {
                templateId = candidate;
                break;
            }
            if (committed instanceof TemplatePersistence.CreateUnavailable) {
                return new CreatePersistenceUnavailable();
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
                Readiness.READY
        ));
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

        var appended = persistence.append(new AdmittedAppendCommit(
                command.templateId(),
                metadata.ownerScope(),
                metadata.staticSchema(),
                command.expectedRevision(),
                admitted.canonicalUtf8(),
                admitted.contentHash()
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
                Readiness.READY
        ));
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
