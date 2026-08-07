package cn.hbads.renderweave.validation;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/root-document-validations")
final class RootDocumentValidationController {

    private final RootDocumentValidationService validations;

    RootDocumentValidationController(RootDocumentValidationService validations) {
        this.validations = validations;
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ValidationBatchResponse validate(@RequestBody byte[] rawRequest) {
        return toResponse(validations.validate(rawRequest));
    }

    private static ValidationBatchResponse toResponse(ValidationBatchResult result) {
        return new ValidationBatchResponse(
                identity(result.target()),
                result.resolvedSchemas().stream()
                        .map(RootDocumentValidationController::identity)
                        .toList(),
                new ValidationSummaryResponse(
                        result.documents().size(),
                        result.validCount(),
                        result.invalidCount()
                ),
                result.documents().stream()
                        .map(document -> new DocumentValidationResponse(
                                document.index(),
                                document.valid(),
                                document.problems().stream()
                                        .map(problem -> new ValidationProblemResponse(
                                                problem.code(),
                                                problem.instancePath(),
                                                problem.schemaPath(),
                                                problem.messageArgs()
                                        ))
                                        .toList(),
                                document.truncated()
                        ))
                        .toList()
        );
    }

    private static ResolvedSchemaResponse identity(ResolvedSchemaIdentity identity) {
        if (identity instanceof ResolvedSchemaIdentity.DraftIdentity draft) {
            return new ResolvedSchemaResponse(
                    "draft", draft.schemaKey().value(), draft.revision(), null
            );
        }
        var exact = ((ResolvedSchemaIdentity.StaticIdentity) identity).reference();
        return new ResolvedSchemaResponse(
                "static", exact.schemaKey().value(), null, exact.versionTag().value()
        );
    }

    record ValidationBatchResponse(
            ResolvedSchemaResponse target,
            List<ResolvedSchemaResponse> resolvedSchemas,
            ValidationSummaryResponse summary,
            List<DocumentValidationResponse> documents
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ResolvedSchemaResponse(
            String kind,
            String schemaKey,
            Long revision,
            String versionTag
    ) {
    }

    record ValidationSummaryResponse(int total, int valid, int invalid) {
    }

    record DocumentValidationResponse(
            int index,
            boolean valid,
            List<ValidationProblemResponse> problems,
            boolean truncated
    ) {
    }

    record ValidationProblemResponse(
            String code,
            String instancePath,
            String schemaPath,
            Map<String, Object> messageArgs
    ) {
    }
}
