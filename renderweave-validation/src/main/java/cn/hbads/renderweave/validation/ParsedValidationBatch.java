package cn.hbads.renderweave.validation;

import java.util.List;
import java.util.Objects;

public record ParsedValidationBatch(
        ValidationTarget target,
        List<StrictJsonValue> documents
) {
    public ParsedValidationBatch {
        Objects.requireNonNull(target, "target");
        documents = List.copyOf(documents);
    }
}
