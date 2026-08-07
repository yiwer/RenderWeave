package cn.hbads.renderweave.validation;

import java.util.ArrayList;
import java.util.Objects;

public final class RootDocumentValidationService {

    private final ValidationTargetResolver resolver;
    private final ValidationBatchRequestParser requestParser;
    private final RootDocumentValidator validator;

    public RootDocumentValidationService(ValidationTargetResolver resolver) {
        this(resolver, new ValidationBatchRequestParser(), new RootDocumentValidator());
    }

    RootDocumentValidationService(
            ValidationTargetResolver resolver,
            ValidationBatchRequestParser requestParser,
            RootDocumentValidator validator
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.requestParser = Objects.requireNonNull(requestParser, "requestParser");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public ValidationBatchResult validate(byte[] rawRequest) {
        var request = requestParser.parse(rawRequest);
        var target = resolver.resolve(request.target());
        var results = new ArrayList<DocumentValidationResult>(request.documents().size());
        var valid = 0;
        for (int index = 0; index < request.documents().size(); index++) {
            var result = validator.validate(index, request.documents().get(index), target);
            results.add(result);
            if (result.valid()) {
                valid++;
            }
        }
        return new ValidationBatchResult(
                target.rootIdentity(),
                target.orderedSchemas().stream().map(ResolvedSchema::identity).toList(),
                results,
                valid,
                results.size() - valid
        );
    }
}
