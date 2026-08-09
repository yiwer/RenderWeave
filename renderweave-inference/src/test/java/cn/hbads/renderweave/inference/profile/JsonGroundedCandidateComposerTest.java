package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateReference;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.StrictJsonSampleProfiler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonGroundedCandidateComposerTest {
    private static final UUID RUN_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String IMAGE_ID = "1".repeat(64);
    private static final String UNKNOWN_IMAGE_ID = "2".repeat(64);

    private final StrictJsonSampleProfiler reducer = new StrictJsonSampleProfiler();
    private final JsonStructuralProfiler structuralProfiler = new JsonStructuralProfiler();
    private final JsonGroundedCandidateComposer composer = new JsonGroundedCandidateComposer();

    @Test
    void jsonTopologyAndUncertaintyWinWhileSafeVisualScalarIsSanitized() {
        var profile = profile("{\"title\":\"A\",\"amount\":12,\"items\":[]}");
        var proposalRootId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        var extraSchemaId = UUID.fromString("30000000-0000-0000-0000-000000000002");
        var root = schema(
                proposalRootId,
                "visual-card",
                List.of(
                        field("title", scalar(CandidateValueKind.DECIMAL), 9_000, false, IMAGE_ID),
                        field("amount", scalar(CandidateValueKind.TEXT), 9_000, false, IMAGE_ID),
                        field("items", CandidateValue.array(scalar(CandidateValueKind.TEXT)), 9_000, false, IMAGE_ID),
                        field("subtitle", valueWithConstraint(CandidateValueKind.TEXT), 6_500, true, IMAGE_ID),
                        field("unknown", scalar(CandidateValueKind.TEXT), 9_000, false, UNKNOWN_IMAGE_ID),
                        new CandidateField(
                                UUID.randomUUID(), "group", "分组", false,
                                CandidateValue.reference(CandidateReference.candidate(extraSchemaId)),
                                CandidateSource.AI, assessment(9_000, IMAGE_ID)
                        )
                )
        );
        var extra = schema(extraSchemaId, "visual-extra", List.of(
                field("name", scalar(CandidateValueKind.TEXT), 9_000, false, IMAGE_ID)
        ));

        var result = composer.compose(
                RUN_ID,
                "fallback-root",
                "推断数据结构",
                profile,
                Set.of(IMAGE_ID),
                new CandidateBundle(CandidateBundle.CONTRACT_VERSION, proposalRootId, List.of(root, extra)),
                8_000
        );

        assertEquals(1, result.candidate().schemas().size());
        var grounded = result.candidate().schemas().getFirst();
        assertEquals("fallback-root", grounded.proposedSchemaKey());
        assertEquals(List.of("amount", "items", "subtitle", "title"),
                grounded.fields().stream().map(CandidateField::proposedFieldKey).toList());
        assertEquals(CandidateValueKind.TEXT, field(grounded, "title").value().kind());
        assertEquals(CandidateValueKind.DECIMAL, field(grounded, "amount").value().kind());
        assertEquals(CandidateValueKind.UNRESOLVED, field(grounded, "items").value().items().kind());

        var subtitle = field(grounded, "subtitle");
        assertFalse(subtitle.required());
        assertTrue(subtitle.value().constraints().isEmpty());
        assertEquals(CandidateSource.AI, subtitle.source());
        assertTrue(subtitle.assessment().inferred());
        assertEquals(CandidateResolution.UNRESOLVED, subtitle.assessment().resolution());
        assertEquals(List.of(CandidateEvidence.image(
                IMAGE_ID, new CandidateBoundingBox(100, 100, 9000, 9000)
        )), subtitle.assessment().evidence());
        assertTrue(result.semanticProblems().stream().anyMatch(problem ->
                problem.code().equals("VISUAL_TYPE_CONFLICT_IGNORED")));
        assertTrue(result.semanticProblems().stream().anyMatch(problem ->
                problem.code().equals("VISUAL_SCHEMA_ADDITION_IGNORED")));
    }

    @Test
    void highConfidenceImageEvidenceMayRefineJsonTextToDateButNotDecimal() {
        var profile = profile("{\"date\":\"2026-03-21\",\"amount\":12}");
        var rootId = UUID.fromString("40000000-0000-0000-0000-000000000001");
        var proposal = new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION,
                rootId,
                List.of(schema(rootId, "visual-date", List.of(
                        field("date", scalar(CandidateValueKind.DATE), 9_000, false, IMAGE_ID),
                        field("amount", scalar(CandidateValueKind.DATE), 9_000, false, IMAGE_ID)
                )))
        );

        var result = composer.compose(
                RUN_ID, "fallback-root", "推断数据结构", profile,
                Set.of(IMAGE_ID), proposal, 8_000
        );

        var root = result.candidate().schemas().getFirst();
        assertEquals(CandidateValueKind.DATE, field(root, "date").value().kind());
        assertEquals(CandidateValueKind.DECIMAL, field(root, "amount").value().kind());
        assertTrue(field(root, "date").assessment().evidence().stream().anyMatch(evidence ->
                evidence.artifactId() != null && evidence.artifactId().equals(IMAGE_ID)));
        assertTrue(field(root, "date").assessment().evidence().stream().anyMatch(evidence ->
                evidence.jsonPointer() != null && evidence.jsonPointer().equals("/date")));
    }

    @Test
    void unrepresentableJsonKeyRemainsUnresolvedAfterVisualComposition() {
        var profile = profile("{\"\":1}");
        var rootId = UUID.fromString("50000000-0000-0000-0000-000000000001");
        var proposal = new CandidateBundle(
                CandidateBundle.CONTRACT_VERSION,
                rootId,
                List.of(schema(rootId, "visual-card", List.of()))
        );

        var result = composer.compose(
                RUN_ID, "fallback-root", "推断数据结构", profile,
                Set.of(IMAGE_ID), proposal, 8_000
        );

        var unresolved = result.candidate().schemas().getFirst().fields().getFirst();
        assertNull(unresolved.proposedFieldKey());
        assertEquals(CandidateResolution.UNRESOLVED, unresolved.assessment().resolution());
        assertEquals(CandidateValueKind.DECIMAL, unresolved.value().kind());
    }

    private JsonStructuralProfile profile(String... samples) {
        var inputs = java.util.Arrays.stream(samples).map(sample -> new InferenceInput.BinaryInput(
                "sample.json", "application/json", sample.getBytes(StandardCharsets.UTF_8)
        )).toList();
        return structuralProfiler.profile(reducer.profile(inputs));
    }

    private static CandidateSchema schema(UUID id, String key, List<CandidateField> fields) {
        return new CandidateSchema(
                id, key, key, CandidateSource.AI, assessment(9_000, IMAGE_ID), fields
        );
    }

    private static CandidateField field(
            String key,
            CandidateValue value,
            int confidence,
            boolean required,
            String imageId
    ) {
        return new CandidateField(
                UUID.randomUUID(), key, key, required, value, CandidateSource.AI,
                assessment(confidence, imageId)
        );
    }

    private static CandidateAssessment assessment(int confidence, String imageId) {
        return CandidateAssessment.ai(
                confidence,
                true,
                confidence < 8_000 ? CandidateResolution.UNRESOLVED : CandidateResolution.NOT_REQUIRED,
                List.of(CandidateEvidence.image(
                        imageId, new CandidateBoundingBox(100, 100, 9000, 9000)
                ))
        );
    }

    private static CandidateValue scalar(CandidateValueKind kind) {
        return CandidateValue.scalar(kind);
    }

    private static CandidateValue valueWithConstraint(CandidateValueKind kind) {
        return new CandidateValue(kind, null, null, List.of(), Map.of("minLength", "1"));
    }

    private static CandidateField field(CandidateSchema schema, String key) {
        return schema.fields().stream().filter(candidate -> key.equals(candidate.proposedFieldKey()))
                .findFirst().orElseThrow();
    }
}
