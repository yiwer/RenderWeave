package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateValidationContext;
import cn.hbads.renderweave.inference.candidate.CandidateValidator;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.StrictJsonSampleProfiler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCandidateProfilerTest {
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private final StrictJsonSampleProfiler reducer = new StrictJsonSampleProfiler();
    private final JsonStructuralProfiler structuralProfiler = new JsonStructuralProfiler();
    private final JsonCandidateProfiler candidateProfiler = new JsonCandidateProfiler();
    private final CandidateValidator validator = new CandidateValidator();
    private JsonStructuralProfile lastProfile;

    @Test
    void concreteJsonBuildsSeparateNestedSchemasAndNeverInfersRequiredOrConstraints() {
        var result = infer(
                "{\"name\":\"Ada\",\"age\":37,\"active\":true,\"address\":{\"city\":\"Paris\"},"
                        + "\"items\":[{\"sku\":\"A\",\"price\":12.5}]}",
                "{\"name\":\"Lin\",\"address\":{\"city\":\"杭州\"},"
                        + "\"items\":[{\"sku\":\"B\"}]}"
        );

        assertEquals(3, result.candidate().schemas().size());
        var root = result.candidate().schemas().getFirst();
        assertEquals(List.of("active", "address", "age", "items", "name"),
                root.fields().stream().map(field -> field.proposedFieldKey()).toList());
        assertTrue(root.fields().stream().noneMatch(field -> field.required()));
        assertTrue(root.fields().stream().allMatch(field -> field.value().constraints().isEmpty()));
        assertEquals(CandidateValueKind.REFERENCE, field(root, "address").value().kind());
        assertEquals(CandidateValueKind.ARRAY, field(root, "items").value().kind());
        assertEquals(CandidateValueKind.REFERENCE, field(root, "items").value().items().kind());

        var itemSchema = result.candidate().schemas().stream()
                .filter(schema -> schema.fields().stream().anyMatch(field -> field.proposedFieldKey().equals("sku")))
                .findFirst().orElseThrow();
        assertEquals(List.of("price", "sku"),
                itemSchema.fields().stream().map(field -> field.proposedFieldKey()).toList());
        assertTrue(allProblems(result).isEmpty(), () -> "Unexpected problems: " + allProblems(result));
    }

    @Test
    void scalarConflictsDowngradeToTextAndNullAdaptationStaysVisibleForReview() {
        var result = infer(
                "{\"mixed\":12,\"nullable\":null}",
                "{\"mixed\":\"twelve\",\"nullable\":true}"
        );
        var root = result.candidate().schemas().getFirst();

        assertEquals(CandidateValueKind.TEXT, field(root, "mixed").value().kind());
        assertEquals(CandidateValueKind.BOOLEAN, field(root, "nullable").value().kind());
        assertCodes(allProblems(result),
                "SCALAR_TYPE_DOWNGRADED_TO_TEXT",
                "NULL_ADAPTATION_REQUIRED",
                "LOW_CONFIDENCE_UNRESOLVED"
        );
    }

    @Test
    void allNullEmptyNestedAndHeterogeneousArraysAreBlockersInsteadOfGuesses() {
        var result = infer(
                "{\"unknown\":null,\"empty\":[],\"nested\":[[1]],\"mixed\":[1,\"one\"]}"
        );
        var root = result.candidate().schemas().getFirst();

        assertEquals(CandidateValueKind.UNRESOLVED, field(root, "unknown").value().kind());
        assertEquals(CandidateValueKind.UNRESOLVED, field(root, "empty").value().items().kind());
        assertEquals(CandidateValueKind.UNRESOLVED, field(root, "nested").value().items().kind());
        assertEquals(CandidateValueKind.CONFLICT, field(root, "mixed").value().items().kind());
        assertCodes(allProblems(result),
                "ALL_NULL_TYPE_UNRESOLVED",
                "EMPTY_ARRAY_ITEM_UNRESOLVED",
                "NESTED_ARRAY_UNSUPPORTED",
                "HETEROGENEOUS_ARRAY",
                "CANDIDATE_TYPE_UNRESOLVED",
                "CANDIDATE_TYPE_CONFLICT"
        );
    }

    @Test
    void objectArrayTakesFieldUnionWhileEveryFieldRemainsOptional() {
        var result = infer(
                "{\"rows\":[{\"left\":1},{\"right\":true},{\"left\":2,\"right\":false}]}"
        );

        var child = result.candidate().schemas().get(1);
        assertEquals(List.of("left", "right"),
                child.fields().stream().map(field -> field.proposedFieldKey()).toList());
        assertTrue(child.fields().stream().noneMatch(field -> field.required()));
        assertTrue(allProblems(result).isEmpty());
    }

    @Test
    void objectScalarConflictsBlockAndDoNotCreateAnOrphanChildSchema() {
        var result = infer("{\"detail\":{\"name\":\"A\"}}", "{\"detail\":\"unknown\"}");

        assertEquals(1, result.candidate().schemas().size());
        assertEquals(CandidateValueKind.CONFLICT,
                field(result.candidate().schemas().getFirst(), "detail").value().kind());
        assertCodes(allProblems(result), "STRUCTURAL_TYPE_CONFLICT", "CANDIDATE_TYPE_CONFLICT");
        assertFalse(allProblems(result).stream().anyMatch(problem -> problem.code().equals("CANDIDATE_SCHEMA_ORPHAN")));
    }

    @Test
    void liveProfilePreservesAnUnrepresentableExactJsonKeyForHumanResolution() {
        var input = new InferenceInput.BinaryInput(
                "sample.json", "application/json", "{\"\":1}".getBytes(StandardCharsets.UTF_8)
        );
        var profile = structuralProfiler.profile(reducer.profile(List.of(input)));
        lastProfile = profile;

        var result = candidateProfiler.inferLive(RUN_ID, "json-card", "JSON 卡片", profile);
        var field = result.candidate().schemas().getFirst().fields().getFirst();

        assertNull(field.proposedFieldKey());
        assertEquals("/", field.displayName());
        assertEquals(cn.hbads.renderweave.inference.candidate.CandidateResolution.UNRESOLVED,
                field.assessment().resolution());
        assertCodes(allProblems(result),
                "CANDIDATE_FIELD_KEY_UNRESOLVED",
                "CANDIDATE_ITEM_UNRESOLVED"
        );
    }

    private CandidateProfileResult infer(String... samples) {
        var inputs = java.util.Arrays.stream(samples).map(sample -> new InferenceInput.BinaryInput(
                "sample.json", "application/json", sample.getBytes(StandardCharsets.UTF_8)
        )).toList();
        var profile = structuralProfiler.profile(reducer.profile(inputs));
        lastProfile = profile;
        return candidateProfiler.infer(RUN_ID, "json-card", "JSON 卡片", profile);
    }

    private List<CandidateProblem> allProblems(CandidateProfileResult result) {
        var problems = new ArrayList<>(result.semanticProblems());
        problems.addAll(validator.validate(
                result.candidate(),
                CandidateValidationContext.trustedReplayOutput(Set.of(), lastProfile, 8_000)
        ));
        return problems;
    }

    private static cn.hbads.renderweave.inference.candidate.CandidateField field(
            cn.hbads.renderweave.inference.candidate.CandidateSchema schema,
            String key
    ) {
        return schema.fields().stream().filter(field -> field.proposedFieldKey().equals(key))
                .findFirst().orElseThrow();
    }

    private static void assertCodes(List<CandidateProblem> problems, String... codes) {
        for (var code : codes) {
            assertTrue(problems.stream().anyMatch(problem -> problem.code().equals(code)),
                    () -> "Expected " + code + ", got " + problems);
        }
    }
}
