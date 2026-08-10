package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies narrow, deterministic formatting repairs to IMAGE_ONLY provider output.
 * Business field identity, inferred kinds, evidence, confidence and graph topology are never changed.
 */
final class ImageOnlyCandidateCanonicalizer {

    Result canonicalize(CandidateBundle candidate) {
        var usedSchemaKeys = new HashSet<String>();
        var problems = new ArrayList<CandidateProblem>();
        var schemas = new ArrayList<CandidateSchema>(candidate.schemas().size());
        for (var schemaIndex = 0; schemaIndex < candidate.schemas().size(); schemaIndex++) {
            var schema = candidate.schemas().get(schemaIndex);
            var schemaPointer = "/schemas/" + schemaIndex;
            var schemaKey = schema.proposedSchemaKey();
            if (!validUniqueSchemaKey(schemaKey, usedSchemaKeys)) {
                schemaKey = generatedSchemaKey(schema.candidateSchemaId(), usedSchemaKeys);
                problems.add(warning(
                        "CANDIDATE_SCHEMA_KEY_NORMALIZED", schema.candidateSchemaId(),
                        schemaPointer + "/proposedSchemaKey"
                ));
            }
            usedSchemaKeys.add(schemaKey);

            var fields = new ArrayList<CandidateField>(schema.fields().size());
            for (var fieldIndex = 0; fieldIndex < schema.fields().size(); fieldIndex++) {
                var field = schema.fields().get(fieldIndex);
                var value = canonicalValue(
                        field.value(), field.candidateFieldId(),
                        schemaPointer + "/fields/" + fieldIndex + "/value", problems
                );
                fields.add(new CandidateField(
                        field.candidateFieldId(), field.proposedFieldKey(), field.displayName(),
                        field.required(), value, field.source(), field.assessment()
                ));
            }
            schemas.add(new CandidateSchema(
                    schema.candidateSchemaId(), schemaKey, schema.displayName(),
                    schema.source(), schema.assessment(), fields
            ));
        }
        return new Result(
                new CandidateBundle(candidate.contractVersion(), candidate.rootCandidateSchemaId(), schemas),
                problems
        );
    }

    private static CandidateValue canonicalValue(
            CandidateValue value,
            UUID itemId,
            String pointer,
            List<CandidateProblem> problems
    ) {
        if (value.kind() == CandidateValueKind.ARRAY && value.items() != null) {
            var items = canonicalValue(value.items(), itemId, pointer + "/items", problems);
            if (items != value.items()) {
                return new CandidateValue(
                        value.kind(), items, value.reference(), value.observedKinds(), value.constraints()
                );
            }
            return value;
        }
        if (supportedScalar(value.kind())
                && value.items() == null
                && value.reference() == null
                && !value.observedKinds().isEmpty()) {
            problems.add(warning(
                    "CANDIDATE_SCALAR_OBSERVED_KINDS_NORMALIZED", itemId,
                    pointer + "/observedKinds"
            ));
            return new CandidateValue(
                    value.kind(), null, null, List.of(), value.constraints()
            );
        }
        return value;
    }

    private static boolean supportedScalar(CandidateValueKind kind) {
        return kind == CandidateValueKind.TEXT
                || kind == CandidateValueKind.DECIMAL
                || kind == CandidateValueKind.DATE
                || kind == CandidateValueKind.TIME
                || kind == CandidateValueKind.BOOLEAN;
    }

    private static boolean validUniqueSchemaKey(String value, Set<String> used) {
        if (value == null || used.contains(value)) return false;
        try {
            SchemaKey.userProvided(value);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String generatedSchemaKey(UUID candidateSchemaId, Set<String> used) {
        var stem = "inferred-" + candidateSchemaId.toString().replace("-", "");
        var candidate = stem;
        for (var suffix = 2; used.contains(candidate); suffix++) {
            candidate = stem + "-" + suffix;
        }
        return candidate;
    }

    private static CandidateProblem warning(String code, UUID itemId, String pointer) {
        return new CandidateProblem(
                code, CandidateProblemSeverity.WARNING, itemId, pointer, Map.of()
        );
    }

    record Result(CandidateBundle candidate, List<CandidateProblem> problems) {
        Result {
            problems = List.copyOf(problems);
        }
    }
}
