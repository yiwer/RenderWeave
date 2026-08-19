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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
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

    @PutMapping(
            value = "/{templateId}",
            consumes = DESIGN_MEDIA_TYPE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<?> save(
            @PathVariable String templateId,
            @RequestParam long expectedRevision,
            @RequestBody byte[] rawDesignDslUtf8
    ) {
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) {
            throw new InvalidTemplateApiRequestException(
                    "expectedRevision must be non-negative and have a successor"
            );
        }
        var outcome = templates.save(
                invocation(),
                new TemplateApplication.SaveCommand(
                        templateId(templateId),
                        expectedRevision,
                        rawDesignDslUtf8
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

    record OpaqueCommitResponse(String templateId, String disclosure) {
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
}
