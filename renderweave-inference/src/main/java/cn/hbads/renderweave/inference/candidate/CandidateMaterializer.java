package cn.hbads.renderweave.inference.candidate;

import cn.hbads.renderweave.schema.definition.DefinitionReferences;
import cn.hbads.renderweave.schema.definition.InvalidSchemaDefinitionException;
import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonParser;
import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonWriter;
import cn.hbads.renderweave.schema.definition.SchemaRef;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.draft.DraftReferenceTarget;
import cn.hbads.renderweave.schema.draft.StaticReferenceTarget;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Deterministically narrows a reviewed Candidate graph into strict, candidate-ID-free Draft snapshots. */
public final class CandidateMaterializer {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private final SchemaDefinitionJsonParser parser;
    private final SchemaDefinitionJsonWriter writer;

    public CandidateMaterializer() {
        this(new SchemaDefinitionJsonParser(), new SchemaDefinitionJsonWriter());
    }

    CandidateMaterializer(SchemaDefinitionJsonParser parser, SchemaDefinitionJsonWriter writer) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public MaterializedDraftBundle materialize(CandidateBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        if (!CandidateBundle.CONTRACT_VERSION.equals(bundle.contractVersion())) {
            invalid("CANDIDATE_VERSION_UNSUPPORTED", "Candidate contract version cannot be materialized");
        }

        var active = new LinkedHashMap<UUID, CandidateSchema>();
        for (var schema : bundle.schemas()) {
            if (schema.assessment().resolution() == CandidateResolution.REMOVED) continue;
            if (schema.assessment().resolution() == CandidateResolution.UNRESOLVED) {
                invalid("CANDIDATE_ITEM_UNRESOLVED", "An unresolved Candidate schema cannot be materialized");
            }
            if (active.putIfAbsent(schema.candidateSchemaId(), schema) != null) {
                invalid("CANDIDATE_SCHEMA_ID_DUPLICATE", "Candidate schema IDs must be unique");
            }
        }
        var root = active.get(bundle.rootCandidateSchemaId());
        if (root == null) {
            invalid("CANDIDATE_ROOT_NOT_FOUND", "The active root Candidate schema is missing");
        }

        var keys = new HashMap<UUID, SchemaKey>();
        var distinctKeys = new LinkedHashSet<SchemaKey>();
        for (var schema : active.values()) {
            final SchemaKey key;
            try {
                key = SchemaKey.userProvided(schema.proposedSchemaKey());
            } catch (RuntimeException failure) {
                throw new CandidateMaterializationException(
                        "CANDIDATE_SCHEMA_KEY_INVALID", "Candidate schema key is not ready for Draft creation", failure
                );
            }
            if (!distinctKeys.add(key)) {
                invalid("CANDIDATE_SCHEMA_KEY_DUPLICATE", "Candidate schema keys must be unique within a bundle");
            }
            keys.put(schema.candidateSchemaId(), key);
        }

        var orderedIds = new ArrayList<UUID>();
        orderChildFirst(bundle.rootCandidateSchemaId(), active, new HashMap<>(), orderedIds);
        if (orderedIds.size() != active.size()) {
            invalid("CANDIDATE_SCHEMA_ORPHAN", "Every active Candidate schema must be reachable from the root");
        }

        var drafts = new ArrayList<MaterializedDraft>();
        for (var schemaId : orderedIds) {
            var schema = active.get(schemaId);
            var definition = strictDefinition(schema, active, keys);
            var occurrences = DefinitionReferences.find(definition);
            drafts.add(new MaterializedDraft(
                    keys.get(schemaId),
                    writer.write(definition),
                    occurrences.stream()
                            .filter(occurrence -> occurrence.reference() instanceof SchemaRef)
                            .map(occurrence -> new DraftReferenceTarget(
                                    occurrence.pointer(), occurrence.reference().schemaKey()
                            ))
                            .toList(),
                    occurrences.stream()
                            .filter(occurrence -> occurrence.reference() instanceof StaticSchemaRef)
                            .map(occurrence -> new StaticReferenceTarget(
                                    occurrence.pointer(), (StaticSchemaRef) occurrence.reference()
                            ))
                            .toList()
            ));
        }
        return new MaterializedDraftBundle(keys.get(bundle.rootCandidateSchemaId()), drafts);
    }

    private SchemaDefinition strictDefinition(
            CandidateSchema schema,
            Map<UUID, CandidateSchema> active,
            Map<UUID, SchemaKey> keys
    ) {
        var root = new LinkedHashMap<String, Object>();
        root.put("dslVersion", SchemaDefinition.DSL_VERSION);
        root.put("displayName", schema.displayName());
        var fields = new ArrayList<Map<String, Object>>();
        for (var field : schema.fields()) {
            if (field.assessment().resolution() == CandidateResolution.REMOVED) continue;
            if (field.assessment().resolution() == CandidateResolution.UNRESOLVED) {
                invalid("CANDIDATE_ITEM_UNRESOLVED", "An unresolved Candidate field cannot be materialized");
            }
            var target = new LinkedHashMap<String, Object>();
            target.put("fieldKey", field.proposedFieldKey());
            if (field.displayName() != null && !field.displayName().isBlank()) {
                target.put("displayName", field.displayName());
            }
            target.put("required", field.required());
            target.put("value", value(field.value(), active, keys));
            fields.add(target);
        }
        root.put("fields", fields);

        try {
            return parser.parse(JSON.writeValueAsString(root));
        } catch (InvalidSchemaDefinitionException failure) {
            throw new CandidateMaterializationException(
                    "CANDIDATE_DSL_INVALID", "Candidate cannot be narrowed to the strict Schema DSL", failure
            );
        } catch (JacksonException failure) {
            throw new IllegalStateException("Candidate materialization JSON could not be encoded", failure);
        }
    }

    private Map<String, Object> value(
            CandidateValue source,
            Map<UUID, CandidateSchema> active,
            Map<UUID, SchemaKey> keys
    ) {
        var result = new LinkedHashMap<String, Object>();
        switch (source.kind()) {
            case UNRESOLVED -> invalid(
                    "CANDIDATE_TYPE_UNRESOLVED", "An unresolved Candidate type cannot enter the Schema DSL"
            );
            case CONFLICT -> invalid(
                    "CANDIDATE_TYPE_CONFLICT", "A conflicting Candidate type cannot enter the Schema DSL"
            );
            default -> result.put("type", source.kind().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (!source.constraints().isEmpty()) {
            var constraints = new LinkedHashMap<String, Object>();
            source.constraints().forEach((name, literal) -> constraints.put(
                    name, constraintLiteral(source.kind(), name, literal)
            ));
            result.put("constraints", constraints);
        }
        if (source.kind() == CandidateValueKind.ARRAY) {
            if (source.items() == null) {
                invalid("CANDIDATE_ARRAY_SHAPE_INVALID", "Candidate array items are required");
            }
            if (source.items().kind() == CandidateValueKind.ARRAY) {
                invalid("NESTED_ARRAY_UNSUPPORTED", "Nested Candidate arrays cannot enter the Schema DSL");
            }
            result.put("items", value(source.items(), active, keys));
        } else if (source.kind() == CandidateValueKind.REFERENCE) {
            result.put("ref", reference(source.reference(), active, keys));
        }
        return result;
    }

    private Map<String, Object> reference(
            CandidateReference reference,
            Map<UUID, CandidateSchema> active,
            Map<UUID, SchemaKey> keys
    ) {
        if (reference == null || reference.kind() == null) {
            invalid("CANDIDATE_REFERENCE_SHAPE_INVALID", "Candidate reference is incomplete");
        }
        var result = new LinkedHashMap<String, Object>();
        switch (reference.kind()) {
            case CANDIDATE_SCHEMA -> {
                if (reference.candidateSchemaId() == null || !active.containsKey(reference.candidateSchemaId())) {
                    invalid("CANDIDATE_REFERENCE_TARGET_MISSING", "Candidate reference target is missing or removed");
                }
                result.put("schemaKey", keys.get(reference.candidateSchemaId()).value());
            }
            case DRAFT -> result.put("schemaKey", reference.schemaKey());
            case STATIC -> {
                result.put("schemaKey", reference.schemaKey());
                result.put("versionTag", reference.versionTag());
            }
        }
        return result;
    }

    private Object constraintLiteral(CandidateValueKind kind, String name, String raw) {
        if (raw == null) {
            invalid("CANDIDATE_CONSTRAINT_LITERAL_INVALID", "Candidate constraint literal cannot be null");
        }
        try {
            if ("enum".equals(name)) {
                JsonNode value = JSON.readTree(raw);
                if (value == null || !value.isArray()) throw new IllegalArgumentException("enum must be a JSON array");
                return value;
            }
            if (Set.of("minLength", "maxLength", "minItems", "maxItems").contains(name)) {
                return Integer.valueOf(raw);
            }
            if ("uniqueItems".equals(name) || (kind == CandidateValueKind.BOOLEAN && "const".equals(name))) {
                if (!"true".equals(raw) && !"false".equals(raw)) {
                    throw new IllegalArgumentException("boolean literal must be true or false");
                }
                return Boolean.valueOf(raw);
            }
            if (kind == CandidateValueKind.DECIMAL) {
                return new BigDecimal(raw);
            }
            return raw;
        } catch (RuntimeException failure) {
            throw new CandidateMaterializationException(
                    "CANDIDATE_CONSTRAINT_LITERAL_INVALID",
                    "Candidate constraint " + name + " has an invalid literal", failure
            );
        }
    }

    private static void orderChildFirst(
            UUID current,
            Map<UUID, CandidateSchema> active,
            Map<UUID, Visit> visits,
            List<UUID> result
    ) {
        var visit = visits.get(current);
        if (visit == Visit.COMPLETE) return;
        if (visit == Visit.ACTIVE) {
            invalid("CANDIDATE_REFERENCE_CYCLE", "Candidate reference graph must be acyclic");
        }
        var schema = active.get(current);
        if (schema == null) {
            invalid("CANDIDATE_REFERENCE_TARGET_MISSING", "Candidate reference target is missing or removed");
        }
        visits.put(current, Visit.ACTIVE);
        for (var field : schema.fields()) {
            if (field.assessment().resolution() == CandidateResolution.REMOVED) continue;
            for (var target : candidateTargets(field.value())) {
                orderChildFirst(target, active, visits, result);
            }
        }
        visits.put(current, Visit.COMPLETE);
        result.add(current);
    }

    private static List<UUID> candidateTargets(CandidateValue value) {
        if (value.kind() == CandidateValueKind.REFERENCE && value.reference() != null
                && value.reference().kind() == CandidateReferenceKind.CANDIDATE_SCHEMA
                && value.reference().candidateSchemaId() != null) {
            return List.of(value.reference().candidateSchemaId());
        }
        if (value.kind() == CandidateValueKind.ARRAY && value.items() != null) {
            return candidateTargets(value.items());
        }
        return List.of();
    }

    private static void invalid(String code, String message) {
        throw new CandidateMaterializationException(code, message);
    }

    private enum Visit {
        ACTIVE,
        COMPLETE
    }
}
