package cn.hbads.renderweave.schema.definition;

import java.util.List;

/** Raised only after strict parsing has produced ordered, user-facing diagnostics. */
public final class InvalidSchemaDefinitionException extends IllegalArgumentException {

    private final List<SchemaProblem> problems;

    public InvalidSchemaDefinitionException(List<SchemaProblem> problems) {
        super(firstMessage(problems));
        this.problems = List.copyOf(problems);
    }

    public List<SchemaProblem> problems() {
        return problems;
    }

    private static String firstMessage(List<SchemaProblem> problems) {
        if (problems == null || problems.isEmpty()) {
            throw new IllegalArgumentException("At least one schema problem is required");
        }
        return problems.getFirst().message();
    }
}
