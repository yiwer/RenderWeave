package cn.hbads.renderweave.schema;

import cn.hbads.renderweave.schema.definition.InvalidSchemaDefinitionException;
import cn.hbads.renderweave.schema.draft.DraftAlreadyExistsException;
import cn.hbads.renderweave.schema.draft.DraftDeleteBlockedException;
import cn.hbads.renderweave.schema.draft.DraftNotFoundException;
import cn.hbads.renderweave.schema.draft.DraftRevisionConflictException;
import cn.hbads.renderweave.schema.draft.DraftRevisionNotFoundException;
import cn.hbads.renderweave.schema.identity.InvalidSchemaKeyException;
import cn.hbads.renderweave.schema.identity.InvalidVersionTagException;
import cn.hbads.renderweave.schema.compile.CompiledArtifactTooLargeException;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaAlreadyExistsException;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
final class DraftProblemHandler {

    @ExceptionHandler(InvalidSchemaDefinitionException.class)
    ResponseEntity<ApiProblem> invalidDefinition(
            InvalidSchemaDefinitionException exception,
            HttpServletRequest request
    ) {
        var violations = exception.problems().stream()
                .map(problem -> new ApiProblem.ApiViolation(
                        problem.code(),
                        prefixDefinition(problem.pointer()),
                        Map.of(),
                        problem.message()
                ))
                .toList();
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Schema definition invalid",
                "SCHEMA_DEFINITION_INVALID",
                "Fix the reported definition problems and save again.",
                request,
                violations,
                null
        );
    }

    @ExceptionHandler(InvalidSchemaKeyException.class)
    ResponseEntity<ApiProblem> invalidSchemaKey(
            InvalidSchemaKeyException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Schema key invalid",
                "SCHEMA_KEY_INVALID",
                exception.getMessage(),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(InvalidVersionTagException.class)
    ResponseEntity<ApiProblem> invalidVersionTag(
            InvalidVersionTagException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Version tag invalid",
                "VERSION_TAG_INVALID",
                exception.getMessage(),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(InvalidApiRequestException.class)
    ResponseEntity<ApiProblem> invalidRequest(
            InvalidApiRequestException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Request invalid",
                "INVALID_REQUEST",
                exception.getMessage(),
                request,
                null,
                null
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiProblem> unreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        var unknownMember = containsCause(exception, "UnrecognizedPropertyException");
        return problem(
                HttpStatus.BAD_REQUEST,
                unknownMember ? "Request invalid" : "JSON invalid",
                unknownMember ? "INVALID_REQUEST" : "INVALID_JSON",
                unknownMember
                        ? "The request contains an unknown member."
                        : "The request body must be strict JSON with unique object members.",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(DraftAlreadyExistsException.class)
    ResponseEntity<ApiProblem> alreadyExists(
            DraftAlreadyExistsException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.CONFLICT,
                "Schema key conflict",
                "SCHEMA_KEY_CONFLICT",
                "The schemaKey already belongs to a Draft or tombstone.",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(DraftNotFoundException.class)
    ResponseEntity<ApiProblem> notFound(
            DraftNotFoundException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.NOT_FOUND,
                "Draft not found",
                "DRAFT_NOT_FOUND",
                "No active Draft exists for the requested schemaKey.",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(DraftRevisionConflictException.class)
    ResponseEntity<ApiProblem> revisionConflict(
            DraftRevisionConflictException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.CONFLICT,
                "Revision conflict",
                "REVISION_CONFLICT",
                "The Draft changed after this editor session loaded it. Local edits were not written.",
                request,
                null,
                exception.currentRevision()
        );
    }

    @ExceptionHandler(DraftRevisionNotFoundException.class)
    ResponseEntity<ApiProblem> revisionNotFound(
            DraftRevisionNotFoundException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.NOT_FOUND,
                "Draft revision not found",
                "DRAFT_REVISION_NOT_FOUND",
                "The requested immutable Draft revision does not exist.",
                request,
                null,
                exception.revision()
        );
    }

    @ExceptionHandler(DraftDeleteBlockedException.class)
    ResponseEntity<ApiProblem> deleteBlocked(
            DraftDeleteBlockedException exception,
            HttpServletRequest request
    ) {
        var violations = exception.incomingReferences().stream()
                .map(reference -> new ApiProblem.ApiViolation(
                        "ACTIVE_INCOMING_DRAFT_REFERENCE",
                        reference.sourcePointer(),
                        Map.of(
                                "sourceSchemaKey", reference.sourceSchemaKey().value(),
                                "sourceRevision", reference.sourceRevision()
                        ),
                        "An active Draft still references this Draft."
                ))
                .toList();
        return problem(
                HttpStatus.CONFLICT,
                "Draft deletion blocked",
                "DRAFT_DELETE_BLOCKED",
                "Remove " + exception.total() + " active incoming reference(s) before deleting.",
                request,
                violations,
                null
        );
    }

    @ExceptionHandler(StaticSchemaNotFoundException.class)
    ResponseEntity<ApiProblem> staticSchemaNotFound(
            StaticSchemaNotFoundException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.NOT_FOUND,
                "StaticSchema not found",
                "STATIC_SCHEMA_NOT_FOUND",
                "No StaticSchema exists for the requested schemaKey and versionTag.",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(StaticSchemaAlreadyExistsException.class)
    ResponseEntity<ApiProblem> staticSchemaAlreadyExists(
            StaticSchemaAlreadyExistsException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.CONFLICT,
                "StaticSchema identity conflict",
                "STATIC_SCHEMA_CONFLICT",
                "The versionTag is already permanently assigned for this schemaKey.",
                request,
                null,
                null
        );
    }

    @ExceptionHandler(CompiledArtifactTooLargeException.class)
    ResponseEntity<ApiProblem> compiledArtifactTooLarge(
            CompiledArtifactTooLargeException exception,
            HttpServletRequest request
    ) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Compiled artifact too large",
                "COMPILED_ARTIFACT_TOO_LARGE",
                "The fully inlined JSON Schema exceeds the 2 MiB publication limit.",
                request,
                List.of(new ApiProblem.ApiViolation(
                        "COMPILED_ARTIFACT_TOO_LARGE",
                        "/versionTag",
                        Map.of(
                                "actualBytes", exception.actualBytes(),
                                "maximumBytes", exception.maximumBytes()
                        ),
                        "Reduce repeated nested StaticSchema content before publishing."
                )),
                null
        );
    }

    private static ResponseEntity<ApiProblem> problem(
            HttpStatus status,
            String title,
            String code,
            String detail,
            HttpServletRequest request,
            List<ApiProblem.ApiViolation> violations,
            Long revision
    ) {
        var body = new ApiProblem(
                "about:blank",
                title,
                status.value(),
                detail,
                request.getRequestURI(),
                code,
                UUID.randomUUID().toString(),
                violations,
                revision
        );
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static String prefixDefinition(String pointer) {
        return pointer.isEmpty() ? "/definition" : "/definition" + pointer;
    }

    private static boolean containsCause(Throwable throwable, String simpleName) {
        for (var cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getSimpleName().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }
}
