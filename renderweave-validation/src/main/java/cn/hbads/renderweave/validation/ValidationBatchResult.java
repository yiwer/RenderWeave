package cn.hbads.renderweave.validation;

import java.util.List;
import java.util.Objects;

public record ValidationBatchResult(
        ResolvedSchemaIdentity target,
        List<ResolvedSchemaIdentity> resolvedSchemas,
        List<DocumentValidationResult> documents,
        int validCount,
        int invalidCount
) {
    public ValidationBatchResult {
        Objects.requireNonNull(target, "target");
        resolvedSchemas = List.copyOf(resolvedSchemas);
        documents = List.copyOf(documents);
        if (validCount < 0 || invalidCount < 0 || validCount + invalidCount != documents.size()) {
            throw new IllegalArgumentException("Validation summary does not match documents");
        }
    }
}
