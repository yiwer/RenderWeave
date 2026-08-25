package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/templates")
final class TemplateController {
    static final String DESIGN_MEDIA_TYPE = "application/vnd.renderweave.design+json";
    private final TemplateApplication templates;
    private final ObjectMapper json;

    TemplateController(TemplateApplication templates, ObjectMapper json) {
        this.templates = templates;
        this.json = json;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> catalog(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit
    ) {
        TemplateApplication.CatalogCommand command;
        try {
            command = new TemplateApplication.CatalogCommand(search, cursor, limit);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidTemplateApiRequestException(invalid.getMessage());
        }
        var outcome = templates.catalog(invocation(), command);
        return switch (outcome) {
            case TemplateApplication.CatalogPage page -> ResponseEntity.ok(
                    new TemplateCatalogResponse(
                            page.entries().stream()
                                    .map(TemplateController::catalogEntry)
                                    .toList(),
                            page.nextCursor().orElse(null)
                    )
            );
            case TemplateApplication.CatalogForbidden ignored -> problem(
                    HttpStatus.FORBIDDEN,
                    "TEMPLATE_FORBIDDEN",
                    "Template catalog access is not permitted"
            );
            case TemplateApplication.CatalogInvalidCursor ignored -> problem(
                    HttpStatus.BAD_REQUEST,
                    "TEMPLATE_REQUEST_INVALID",
                    "Template catalog cursor is invalid"
            );
            case TemplateApplication.CatalogAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_AUTHORITY_UNAVAILABLE",
                    "Template authorization is unavailable"
            );
            case TemplateApplication.CatalogPersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_PERSISTENCE_UNAVAILABLE",
                    "Template persistence is unavailable"
            );
        };
    }

    @PostMapping(consumes = DESIGN_MEDIA_TYPE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> create(
            @RequestParam String schemaKey,
            @RequestParam String versionTag,
            @RequestBody byte[] rawDesignDslUtf8
    ) {
        var outcome = templates.create(
                invocation(),
                new TemplateApplication.CreateCommand(
                        staticSchema(schemaKey, versionTag),
                        rawDesignDslUtf8
                )
        );
        return switch (outcome) {
            case TemplateApplication.CreatedReadable created -> ResponseEntity
                    .created(templateUri(created.current().templateId()))
                    .body(readable(created.current()));
            case TemplateApplication.CreatedOpaque created -> ResponseEntity
                    .created(templateUri(created.templateId()))
                    .body(new OpaqueCommitResponse(created.templateId().value(), "OPAQUE"));
            case TemplateApplication.CreateDesignRejected rejected ->
                    designProblem(rejected.rejection());
            case TemplateApplication.CreateDependencyRejected rejected ->
                    dependencyProblem(
                            "TEMPLATE_DEPENDENCY_REJECTED",
                            "Template dependencies rejected",
                            "Strict create requires a complete READY dependency set",
                            rejected.report(),
                            null
                    );
            case TemplateApplication.CreateStaticSchemaNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "TEMPLATE_STATIC_SCHEMA_NOT_FOUND",
                    "The exact StaticSchema does not exist"
            );
            case TemplateApplication.CreateForbidden ignored -> problem(
                    HttpStatus.FORBIDDEN,
                    "TEMPLATE_FORBIDDEN",
                    "Template creation is not permitted"
            );
            case TemplateApplication.CreateAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_AUTHORITY_UNAVAILABLE",
                    "Template authorization is unavailable"
            );
            case TemplateApplication.CreateDependencyUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_DEPENDENCY_UNAVAILABLE",
                    "Template dependencies could not be checked"
            );
            case TemplateApplication.CreatePersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_PERSISTENCE_UNAVAILABLE",
                    "Template persistence is unavailable"
            );
        };
    }

    @GetMapping(value = "/{templateId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> getCurrent(@PathVariable String templateId) {
        var outcome = templates.getCurrent(invocation(), templateId(templateId));
        return switch (outcome) {
            case TemplateApplication.CurrentReadable current -> ResponseEntity.ok(
                    readable(current.current())
            );
            case TemplateApplication.CurrentNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "TEMPLATE_NOT_FOUND",
                    "Template was not found"
            );
            case TemplateApplication.CurrentDeleted ignored -> problem(
                    HttpStatus.GONE,
                    "TEMPLATE_DELETED",
                    "Template is deleted"
            );
            case TemplateApplication.CurrentIntegrityMismatch ignored -> problem(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "TEMPLATE_INTEGRITY_MISMATCH",
                    "Stored Template integrity verification failed"
            );
            case TemplateApplication.CurrentAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_AUTHORITY_UNAVAILABLE",
                    "Template authorization is unavailable"
            );
            case TemplateApplication.CurrentPersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_PERSISTENCE_UNAVAILABLE",
                    "Template persistence is unavailable"
            );
        };
    }

    @PostMapping(
            value = "/{templateId}/readiness-recheck",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<?> recheckCurrent(@PathVariable String templateId) {
        var outcome = templates.recheckCurrent(invocation(), templateId(templateId));
        return switch (outcome) {
            case TemplateApplication.CurrentRechecked rechecked -> ResponseEntity.ok(
                    readinessRecheck(rechecked.current())
            );
            case TemplateApplication.RecheckCurrentNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "TEMPLATE_NOT_FOUND",
                    "Template was not found"
            );
            case TemplateApplication.RecheckCurrentDeleted ignored -> problem(
                    HttpStatus.GONE,
                    "TEMPLATE_DELETED",
                    "Template is deleted"
            );
            case TemplateApplication.RecheckCurrentIntegrityMismatch ignored -> problem(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "TEMPLATE_INTEGRITY_MISMATCH",
                    "Stored Template integrity verification failed"
            );
            case TemplateApplication.RecheckCurrentAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_AUTHORITY_UNAVAILABLE",
                    "Template authorization is unavailable"
            );
            case TemplateApplication.RecheckCurrentDependencyUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_DEPENDENCY_UNAVAILABLE",
                    "Template dependencies could not be checked"
            );
            case TemplateApplication.RecheckCurrentPersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_PERSISTENCE_UNAVAILABLE",
                    "Template persistence is unavailable"
            );
            case TemplateApplication.RecheckCurrentDrifted ignored -> problem(
                    HttpStatus.CONFLICT,
                    "TEMPLATE_CURRENT_DRIFTED",
                    "Template current changed repeatedly during readiness recheck"
            );
        };
    }

    @PutMapping(
            value = "/{templateId}",
            consumes = DESIGN_MEDIA_TYPE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<?> save(
            @PathVariable String templateId,
            @RequestParam long expectedRevision,
            @RequestHeader(name = "X-Confirmation-Token", required = false)
            String confirmationToken,
            @RequestBody byte[] rawDesignDslUtf8
    ) {
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) {
            throw new InvalidTemplateApiRequestException(
                    "expectedRevision must be non-negative and have a successor"
            );
        }
        if (confirmationToken != null
                && !confirmationToken.matches("[0-9a-f]{64}")) {
            throw new InvalidTemplateApiRequestException(
                    "X-Confirmation-Token must be 64 lowercase hexadecimal characters"
            );
        }
        var outcome = templates.save(
                invocation(),
                new TemplateApplication.SaveCommand(
                        templateId(templateId),
                        expectedRevision,
                        rawDesignDslUtf8,
                        confirmationToken
                )
        );
        return switch (outcome) {
            case TemplateApplication.SavedReadable saved -> ResponseEntity.ok(
                    readable(saved.current())
            );
            case TemplateApplication.SavedOpaque saved -> ResponseEntity.ok(
                    new OpaqueCommitResponse(saved.templateId().value(), "OPAQUE")
            );
            case TemplateApplication.SaveDesignRejected rejected ->
                    designProblem(rejected.rejection());
            case TemplateApplication.SaveConfirmationRequired required ->
                    dependencyProblem(
                            "TEMPLATE_DEPENDENCY_CONFIRMATION_REQUIRED",
                            "Template dependency confirmation required",
                            "Confirm the exact complete dependency problem set to save INVALID",
                            required.offer().report(),
                            required.offer()
                    );
            case TemplateApplication.SaveDependencyRejected rejected ->
                    dependencyProblem(
                            "TEMPLATE_DEPENDENCY_REJECTED",
                            "Template dependencies rejected",
                            "Hard or truncated dependency problems cannot be confirmed",
                            rejected.report(),
                            null
                    );
            case TemplateApplication.SaveConfirmationInvalid ignored -> problem(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "TEMPLATE_CONFIRMATION_INVALID",
                    "The invalid-save confirmation token is not valid"
            );
            case TemplateApplication.SaveConfirmationExpired ignored -> problem(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "TEMPLATE_CONFIRMATION_EXPIRED",
                    "The invalid-save confirmation token has expired"
            );
            case TemplateApplication.SaveConfirmationStale stale -> staleProblem(stale);
            case TemplateApplication.SaveNotFound ignored -> problem(
                    HttpStatus.NOT_FOUND,
                    "TEMPLATE_NOT_FOUND",
                    "Template was not found"
            );
            case TemplateApplication.SaveForbidden ignored -> problem(
                    HttpStatus.FORBIDDEN,
                    "TEMPLATE_FORBIDDEN",
                    "Template update is not permitted"
            );
            case TemplateApplication.SaveDeleted ignored -> problem(
                    HttpStatus.CONFLICT,
                    "TEMPLATE_DELETED",
                    "Deleted Template cannot be saved"
            );
            case TemplateApplication.SaveRevisionConflict conflict -> conflictProblem(conflict);
            case TemplateApplication.SaveIntegrityMismatch ignored -> problem(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "TEMPLATE_INTEGRITY_MISMATCH",
                    "Stored Template integrity verification failed"
            );
            case TemplateApplication.SaveAuthorityUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_AUTHORITY_UNAVAILABLE",
                    "Template authorization is unavailable"
            );
            case TemplateApplication.SaveDependencyUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_DEPENDENCY_UNAVAILABLE",
                    "Template dependencies could not be checked"
            );
            case TemplateApplication.SaveConfirmationUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_CONFIRMATION_UNAVAILABLE",
                    "Template invalid-save confirmation is unavailable"
            );
            case TemplateApplication.SavePersistenceUnavailable ignored -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TEMPLATE_PERSISTENCE_UNAVAILABLE",
                    "Template persistence is unavailable"
            );
        };
    }

    private TemplateResponse readable(TemplateApplication.Current current) {
        return new TemplateResponse(
                current.templateId().value(),
                "READABLE",
                current.revision(),
                new StaticSchemaResponse(
                        current.staticSchema().schemaKey().value(),
                        current.staticSchema().versionTag().value()
                ),
                current.contentHash(),
                current.readiness().name(),
                designJson(current.canonicalDesignDslUtf8())
        );
    }

    private static TemplateCatalogEntryResponse catalogEntry(
            TemplateApplication.CatalogEntry entry
    ) {
        return new TemplateCatalogEntryResponse(
                entry.templateId().value(),
                entry.displayName(),
                new StaticSchemaResponse(
                        entry.staticSchema().schemaKey().value(),
                        entry.staticSchema().versionTag().value()
                ),
                entry.revision(),
                entry.readiness().name(),
                entry.updatedAt()
        );
    }

    private ReadinessRecheckResponse readinessRecheck(TemplateApplication.Current current) {
        return new ReadinessRecheckResponse(
                current.templateId().value(),
                current.revision(),
                current.contentHash(),
                current.readiness().name()
        );
    }

    private ResponseEntity<TemplateProblemResponse> designProblem(
            DesignDslAuthority.Rejected rejection
    ) {
        var status = rejection.code() == DesignDslAuthority.FailureCode.DESIGN_DSL_LIMIT_EXCEEDED
                ? HttpStatus.PAYLOAD_TOO_LARGE
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new TemplateProblemResponse(
                "urn:renderweave:problem:" + rejection.code().name().toLowerCase(),
                "DesignDSL rejected",
                status.value(),
                "DesignDSL did not satisfy the admitted kernel",
                null,
                rejection.code().name(),
                UUID.randomUUID().toString(),
                rejection.stage().name(),
                rejection.pointer(),
                rejection.limit().map(DesignDslAuthority.Limit::id).orElse(null),
                null
        ));
    }

    private ResponseEntity<TemplateProblemResponse> conflictProblem(
            TemplateApplication.SaveRevisionConflict conflict
    ) {
        var status = HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new TemplateProblemResponse(
                "urn:renderweave:problem:template-revision-conflict",
                "Template revision conflict",
                status.value(),
                "expectedRevision is no longer current",
                null,
                "TEMPLATE_REVISION_CONFLICT",
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                conflict.currentRevision().isPresent()
                        ? conflict.currentRevision().getAsLong()
                        : null
        ));
    }

    private ResponseEntity<TemplateDependencyProblemResponse> staleProblem(
            TemplateApplication.SaveConfirmationStale stale
    ) {
        if (stale.replacement().isPresent()) {
            var offer = stale.replacement().orElseThrow();
            return dependencyProblem(
                    "TEMPLATE_CONFIRMATION_STALE",
                    "Template confirmation is stale",
                    "Dependency facts changed; review and confirm the fresh complete problem set",
                    offer.report(),
                    offer
            );
        }
        return dependencyProblem(
                "TEMPLATE_CONFIRMATION_STALE",
                "Template confirmation is stale",
                "Bound content, current, or dependency facts changed",
                null,
                null
        );
    }

    private ResponseEntity<TemplateDependencyProblemResponse> dependencyProblem(
            String code,
            String title,
            String detail,
            TemplateApplication.ValidationReport report,
            TemplateApplication.InvalidCommitConfirmationOffer offer
    ) {
        var status = HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new TemplateDependencyProblemResponse(
                        "urn:renderweave:problem:" + code.toLowerCase().replace('_', '-'),
                        title,
                        status.value(),
                        detail,
                        code,
                        UUID.randomUUID().toString(),
                        offer == null ? null : offer.proposedContentHash(),
                        offer == null ? null : offer.confirmationToken(),
                        offer == null ? null : offer.expiresAt(),
                        report == null ? List.of() : report.problems().stream()
                                .map(problem -> new ValidationProblemResponse(
                                        problem.code(),
                                        problem.category().name(),
                                        problem.severity().name(),
                                        problem.canonicalPointer(),
                                        problem.messageArgs()
                                ))
                                .toList(),
                        report != null && report.truncated()
                ));
    }

    private ResponseEntity<TemplateProblemResponse> problem(
            HttpStatus status,
            String code,
            String detail
    ) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new TemplateProblemResponse(
                "urn:renderweave:problem:" + code.toLowerCase().replace('_', '-'),
                code,
                status.value(),
                detail,
                null,
                code,
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null
        ));
    }

    private JsonNode designJson(byte[] canonicalUtf8) {
        try {
            return json.readTree(canonicalUtf8);
        } catch (JacksonException impossible) {
            throw new IllegalStateException("Canonical DesignDSL could not be rendered", impossible);
        }
    }

    private static StaticSchemaRef staticSchema(String rawSchemaKey, String rawVersionTag) {
        var schemaKey = rawSchemaKey.startsWith("system-")
                ? SchemaKey.systemProvided(rawSchemaKey)
                : SchemaKey.userProvided(rawSchemaKey);
        return new StaticSchemaRef(schemaKey, VersionTag.of(rawVersionTag));
    }

    private static TemplateApplication.TemplateInvocationRef invocation() {
        return TemplateApplication.TemplateInvocationRef.serverCreated(UUID.randomUUID().toString());
    }

    private static TemplateApplication.TemplateId templateId(String raw) {
        try {
            return TemplateApplication.TemplateId.of(raw);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidTemplateApiRequestException(
                    "templateId must be non-blank and at most 128 characters"
            );
        }
    }

    private static URI templateUri(TemplateApplication.TemplateId templateId) {
        return URI.create("/api/v1/templates/" + templateId.value());
    }

    record StaticSchemaResponse(String schemaKey, String versionTag) {
    }

    record TemplateResponse(
            String templateId,
            String disclosure,
            long revision,
            StaticSchemaResponse staticSchema,
            String contentHash,
            String readiness,
            JsonNode designDsl
    ) {
    }

    record TemplateCatalogEntryResponse(
            String templateId,
            String displayName,
            StaticSchemaResponse staticSchema,
            long revision,
            String readiness,
            Instant updatedAt
    ) {
    }

    record TemplateCatalogResponse(
            List<TemplateCatalogEntryResponse> items,
            String nextCursor
    ) {
    }

    record OpaqueCommitResponse(String templateId, String disclosure) {
    }

    record ReadinessRecheckResponse(
            String templateId,
            long revision,
            String contentHash,
            String readiness
    ) {
    }

    record TemplateProblemResponse(
            String type,
            String title,
            int status,
            String detail,
            String instance,
            String code,
            String traceId,
            String stage,
            String pointer,
            String limit,
            Long currentRevision
    ) {
    }

    record ValidationProblemResponse(
            String code,
            String category,
            String severity,
            String canonicalPointer,
            List<String> messageArgs
    ) {
    }

    record TemplateDependencyProblemResponse(
            String type,
            String title,
            int status,
            String detail,
            String code,
            String traceId,
            String proposedContentHash,
            String confirmationToken,
            Instant expiresAt,
            List<ValidationProblemResponse> problems,
            boolean truncated
    ) {
    }
}
