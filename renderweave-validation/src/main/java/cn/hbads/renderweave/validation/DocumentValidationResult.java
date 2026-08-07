package cn.hbads.renderweave.validation;

import java.util.List;

public record DocumentValidationResult(
        int index,
        boolean valid,
        List<ValidationProblem> problems,
        boolean truncated
) {
    public DocumentValidationResult {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        problems = List.copyOf(problems);
        if (valid != problems.isEmpty()) {
            throw new IllegalArgumentException("valid must reflect whether problems are empty");
        }
        if (problems.size() > RootDocumentValidator.MAX_PROBLEMS) {
            throw new IllegalArgumentException("Too many validation problems");
        }
    }
}
