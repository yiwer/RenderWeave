package cn.hbads.renderweave.inference.candidate;

import cn.hbads.renderweave.schema.definition.ArrayValue;
import cn.hbads.renderweave.schema.definition.ReferenceValue;
import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonParser;
import cn.hbads.renderweave.schema.definition.SchemaRef;
import cn.hbads.renderweave.schema.definition.TextValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateMaterializerTest {
    private final CandidateMaterializer materializer = new CandidateMaterializer();
    private final SchemaDefinitionJsonParser parser = new SchemaDefinitionJsonParser();

    @Test
    void materializesReachableSchemasChildFirstAndDropsCandidateOnlyIdentityAndRemovedItems() {
        var rootId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var childId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var removedFieldId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        var bundle = new CandidateBundle(CandidateBundle.CONTRACT_VERSION, rootId, List.of(
                schema(rootId, "catalog", "目录", List.of(
                        field("00000000-0000-0000-0000-000000000011", "entries", true,
                                new CandidateValue(
                                        CandidateValueKind.ARRAY,
                                        new CandidateValue(
                                                CandidateValueKind.REFERENCE, null,
                                                CandidateReference.candidate(childId), List.of(), Map.of()
                                        ),
                                        null, List.of(), Map.of("minItems", "1")
                                )),
                        new CandidateField(
                                removedFieldId, "discarded", "不落库", false,
                                CandidateValue.unresolved("null"), CandidateSource.AI,
                                new CandidateAssessment(
                                        2_000, true, CandidateResolution.REMOVED,
                                        List.of(CandidateEvidence.json(0, "/discarded"))
                                )
                        )
                )),
                schema(childId, "catalog-entry", "目录项", List.of(
                        field("00000000-0000-0000-0000-000000000012", "title", false,
                                new CandidateValue(
                                        CandidateValueKind.TEXT, null, null, List.of(),
                                        Map.of("minLength", "1", "maxLength", "80")
                                ))
                ))
        ));

        var result = materializer.materialize(bundle);

        assertEquals(List.of("catalog-entry", "catalog"), result.draftsInCreationOrder().stream()
                .map(draft -> draft.schemaKey().value()).toList());
        var child = parser.parse(result.draftsInCreationOrder().getFirst().definitionJson());
        var root = parser.parse(result.draftsInCreationOrder().getLast().definitionJson());
        assertInstanceOf(TextValue.class, child.fields().getFirst().value());
        var entries = assertInstanceOf(ArrayValue.class, root.fields().getFirst().value());
        var reference = assertInstanceOf(ReferenceValue.class, entries.items());
        assertEquals("catalog-entry", ((SchemaRef) reference.ref()).schemaKey().value());
        assertEquals(1, root.fields().size());
        assertEquals(1, entries.constraints().minItems().orElseThrow());
        assertFalse(result.draftsInCreationOrder().stream()
                .map(MaterializedDraft::definitionJson)
                .anyMatch(json -> json.contains(rootId.toString())
                        || json.contains(childId.toString())
                        || json.contains(removedFieldId.toString())
                        || json.contains("candidateFieldId")));
    }

    @Test
    void rejectsCandidateOnlyTypesAndInvalidConstraintLiteralsBeforePersistence() {
        var unresolved = simple(CandidateValue.unresolved("empty-array"));
        assertEquals("CANDIDATE_TYPE_UNRESOLVED",
                assertThrows(CandidateMaterializationException.class,
                        () -> materializer.materialize(unresolved)).code());

        var invalidConstraint = simple(new CandidateValue(
                CandidateValueKind.DECIMAL, null, null, List.of(), Map.of("min", "not-a-decimal")
        ));
        assertEquals("CANDIDATE_CONSTRAINT_LITERAL_INVALID",
                assertThrows(CandidateMaterializationException.class,
                        () -> materializer.materialize(invalidConstraint)).code());
    }

    private static CandidateBundle simple(CandidateValue value) {
        var root = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return new CandidateBundle(CandidateBundle.CONTRACT_VERSION, root, List.of(
                schema(root, "simple", "Simple", List.of(
                        field("00000000-0000-0000-0000-000000000010", "value", false, value)
                ))
        ));
    }

    private static CandidateSchema schema(
            UUID id,
            String key,
            String name,
            List<CandidateField> fields
    ) {
        return new CandidateSchema(
                id, key, name, CandidateSource.AI,
                CandidateAssessment.ai(
                        9_500, true, CandidateResolution.NOT_REQUIRED,
                        List.of(CandidateEvidence.json(0, ""))
                ),
                fields
        );
    }

    private static CandidateField field(
            String id,
            String key,
            boolean required,
            CandidateValue value
    ) {
        return new CandidateField(
                UUID.fromString(id), key, key, required, value, CandidateSource.USER,
                CandidateAssessment.user()
        );
    }
}
