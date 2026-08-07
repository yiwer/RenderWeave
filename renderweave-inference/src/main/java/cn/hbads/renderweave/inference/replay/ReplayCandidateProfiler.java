package cn.hbads.renderweave.inference.replay;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateIds;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import cn.hbads.renderweave.inference.candidate.CandidateReference;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.profile.CandidateProfileResult;
import cn.hbads.renderweave.inference.profile.JsonCandidateProfiler;
import cn.hbads.renderweave.inference.profile.JsonStructuralProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Zero-network replay profiler. JSON owns concrete structure; visual fixtures add semantics and evidence. */
public final class ReplayCandidateProfiler {
    private final JsonCandidateProfiler jsonProfiler;
    private final int lowConfidenceThresholdBps;

    public ReplayCandidateProfiler(int lowConfidenceThresholdBps) {
        this(new JsonCandidateProfiler(), lowConfidenceThresholdBps);
    }

    ReplayCandidateProfiler(JsonCandidateProfiler jsonProfiler, int lowConfidenceThresholdBps) {
        this.jsonProfiler = Objects.requireNonNull(jsonProfiler, "jsonProfiler");
        if (lowConfidenceThresholdBps < 0 || lowConfidenceThresholdBps > 10_000) {
            throw new IllegalArgumentException("lowConfidenceThresholdBps must be 0..10000");
        }
        this.lowConfidenceThresholdBps = lowConfidenceThresholdBps;
    }

    public CandidateProfileResult infer(
            UUID runId,
            ReplayCase fixture,
            List<String> imageArtifactIds,
            JsonStructuralProfile jsonProfile
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(fixture, "fixture");
        imageArtifactIds = List.copyOf(Objects.requireNonNull(imageArtifactIds, "imageArtifactIds"));
        validateInputs(fixture.mode(), imageArtifactIds, jsonProfile);
        return switch (fixture.mode()) {
            case IMAGE_ONLY -> visual(runId, fixture, imageArtifactIds);
            case JSON_ONLY -> jsonProfiler.infer(
                    runId, fixture.rootSchemaKey(), fixture.displayName(), jsonProfile
            );
            case COMBINED -> combined(runId, fixture, imageArtifactIds, jsonProfile);
        };
    }

    private CandidateProfileResult visual(
            UUID runId,
            ReplayCase fixture,
            List<String> imageArtifactIds
    ) {
        if (fixture.visualSchemas().isEmpty()) {
            throw new IllegalArgumentException("IMAGE_ONLY fixture must contain visual schemas");
        }
        var schemaIds = new LinkedHashMap<String, UUID>();
        for (var schema : fixture.visualSchemas()) {
            if (schemaIds.putIfAbsent(schema.schemaKey(), visualSchemaId(runId, schema.schemaKey())) != null) {
                throw new IllegalArgumentException("Duplicate visual schema key " + schema.schemaKey());
            }
        }
        var rootId = schemaIds.get(fixture.rootSchemaKey());
        if (rootId == null) throw new IllegalArgumentException("Visual root schema is missing");

        var schemas = fixture.visualSchemas().stream()
                .map(schema -> visualSchema(runId, schema, schemaIds, imageArtifactIds))
                .toList();
        return new CandidateProfileResult(
                new CandidateBundle(CandidateBundle.CONTRACT_VERSION, rootId, schemas), List.of()
        );
    }

    private CandidateProfileResult combined(
            UUID runId,
            ReplayCase fixture,
            List<String> imageArtifactIds,
            JsonStructuralProfile jsonProfile
    ) {
        var json = jsonProfiler.infer(runId, fixture.rootSchemaKey(), fixture.displayName(), jsonProfile);
        if (fixture.visualSchemas().isEmpty()) return json;
        var visualRoot = fixture.visualSchemas().stream()
                .filter(schema -> fixture.rootSchemaKey().equals(schema.schemaKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Combined visual root schema is missing"));

        var problems = new ArrayList<>(json.semanticProblems());
        var schemas = new ArrayList<CandidateSchema>();
        for (var schema : json.candidate().schemas()) {
            if (!schema.candidateSchemaId().equals(json.candidate().rootCandidateSchemaId())) {
                schemas.add(schema);
                continue;
            }
            schemas.add(overlayRoot(runId, schema, visualRoot, imageArtifactIds, problems));
        }
        return new CandidateProfileResult(
                new CandidateBundle(
                        CandidateBundle.CONTRACT_VERSION,
                        json.candidate().rootCandidateSchemaId(),
                        schemas
                ),
                problems
        );
    }

    private CandidateSchema overlayRoot(
            UUID runId,
            CandidateSchema jsonRoot,
            ReplayVisualSchema visualRoot,
            List<String> imageArtifactIds,
            List<CandidateProblem> problems
    ) {
        var visualFields = new LinkedHashMap<String, ReplayVisualField>();
        for (var visualField : visualRoot.fields()) {
            if (visualFields.putIfAbsent(visualField.fieldKey(), visualField) != null) {
                throw new IllegalArgumentException("Duplicate visual field key " + visualField.fieldKey());
            }
        }

        var fields = new ArrayList<CandidateField>();
        for (var jsonField : jsonRoot.fields()) {
            var visualField = visualFields.remove(jsonField.proposedFieldKey());
            fields.add(visualField == null
                    ? jsonField
                    : overlayField(jsonField, visualField, imageArtifactIds, problems));
        }
        for (var visualField : visualFields.values()) {
            fields.add(visualField(
                    runId,
                    "combined-extra:" + visualRoot.schemaKey(),
                    visualField,
                    Map.of(),
                    imageArtifactIds
            ));
        }
        fields.sort(java.util.Comparator.comparing(CandidateField::proposedFieldKey));

        var schemaEvidence = appendEvidence(
                jsonRoot.assessment().evidence(), imageEvidence(visualRoot, imageArtifactIds)
        );
        var confidence = Math.min(jsonRoot.assessment().confidenceBps(), visualRoot.confidenceBps());
        return new CandidateSchema(
                jsonRoot.candidateSchemaId(),
                jsonRoot.proposedSchemaKey(),
                visualRoot.displayName(),
                CandidateSource.AI,
                assessment(confidence, schemaEvidence),
                fields
        );
    }

    private CandidateField overlayField(
            CandidateField jsonField,
            ReplayVisualField visual,
            List<String> imageArtifactIds,
            List<CandidateProblem> problems
    ) {
        var visualValue = visualValue(visual, Map.of());
        var compatible = compatible(jsonField.value(), visual);
        var refined = semanticRefinement(jsonField.value(), visual);
        if (!compatible && refined == null) {
            problems.add(new CandidateProblem(
                    "VISUAL_TYPE_CONFLICT_IGNORED",
                    CandidateProblemSeverity.WARNING,
                    jsonField.candidateFieldId(),
                    "/fields/" + escapePointer(jsonField.proposedFieldKey()) + "/value",
                    Map.of(
                            "jsonKind", describe(jsonField.value()),
                            "visualKind", describe(visualValue)
                    )
            ));
        }
        var confidence = Math.min(jsonField.assessment().confidenceBps(), visual.confidenceBps());
        var evidence = appendEvidence(
                jsonField.assessment().evidence(), imageEvidence(visual, imageArtifactIds)
        );
        return new CandidateField(
                jsonField.candidateFieldId(),
                jsonField.proposedFieldKey(),
                visual.displayName(),
                visual.required(),
                refined == null ? jsonField.value() : refined,
                CandidateSource.AI,
                assessment(confidence, evidence)
        );
    }

    private CandidateSchema visualSchema(
            UUID runId,
            ReplayVisualSchema visual,
            Map<String, UUID> schemaIds,
            List<String> imageArtifactIds
    ) {
        var localPath = "visual:" + visual.schemaKey();
        var fields = visual.fields().stream()
                .map(field -> visualField(runId, localPath, field, schemaIds, imageArtifactIds))
                .toList();
        return new CandidateSchema(
                schemaIds.get(visual.schemaKey()),
                visual.schemaKey(),
                visual.displayName(),
                CandidateSource.AI,
                assessment(visual.confidenceBps(), List.of(imageEvidence(visual, imageArtifactIds))),
                fields
        );
    }

    private CandidateField visualField(
            UUID runId,
            String schemaPath,
            ReplayVisualField visual,
            Map<String, UUID> schemaIds,
            List<String> imageArtifactIds
    ) {
        return new CandidateField(
                CandidateIds.field(runId, schemaPath, visual.fieldKey()),
                visual.fieldKey(),
                visual.displayName(),
                visual.required(),
                visualValue(visual, schemaIds),
                CandidateSource.AI,
                assessment(visual.confidenceBps(), List.of(imageEvidence(visual, imageArtifactIds)))
        );
    }

    private static CandidateValue visualValue(
            ReplayVisualField visual,
            Map<String, UUID> schemaIds
    ) {
        CandidateValue value;
        if (visual.type() == CandidateValueKind.REFERENCE) {
            var target = schemaIds.get(visual.targetSchemaKey());
            if (target == null) value = CandidateValue.unresolved("visual-reference");
            else value = CandidateValue.reference(CandidateReference.candidate(target));
        } else if (visual.type() == CandidateValueKind.UNRESOLVED) {
            value = CandidateValue.unresolved("visual-unresolved");
        } else if (visual.type() == CandidateValueKind.CONFLICT) {
            value = CandidateValue.conflict("visual-text", "visual-decimal");
        } else if (visual.type() == CandidateValueKind.ARRAY) {
            value = CandidateValue.unresolved("visual-array-item");
        } else {
            value = CandidateValue.scalar(visual.type());
        }
        return visual.array() ? CandidateValue.array(value) : value;
    }

    private CandidateAssessment assessment(int confidenceBps, List<CandidateEvidence> evidence) {
        return CandidateAssessment.ai(
                confidenceBps,
                true,
                confidenceBps < lowConfidenceThresholdBps
                        ? CandidateResolution.UNRESOLVED
                        : CandidateResolution.NOT_REQUIRED,
                evidence
        );
    }

    private static CandidateEvidence imageEvidence(
            ReplayVisualSchema schema,
            List<String> imageArtifactIds
    ) {
        return CandidateEvidence.image(
                artifactId(imageArtifactIds, schema.imageOrdinal()), box(schema.boundingBox())
        );
    }

    private static CandidateEvidence imageEvidence(
            ReplayVisualField field,
            List<String> imageArtifactIds
    ) {
        return CandidateEvidence.image(
                artifactId(imageArtifactIds, field.imageOrdinal()), box(field.boundingBox())
        );
    }

    private static String artifactId(List<String> imageArtifactIds, int ordinal) {
        if (ordinal < 0 || ordinal >= imageArtifactIds.size()) {
            throw new IllegalArgumentException("Visual evidence image ordinal is out of range");
        }
        return imageArtifactIds.get(ordinal);
    }

    private static CandidateBoundingBox box(List<Integer> coordinates) {
        if (coordinates == null || coordinates.size() != 4 || coordinates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Visual evidence boundingBox must contain four coordinates");
        }
        return new CandidateBoundingBox(
                coordinates.get(0), coordinates.get(1), coordinates.get(2), coordinates.get(3)
        );
    }

    private static UUID visualSchemaId(UUID runId, String schemaKey) {
        return CandidateIds.schema(runId, "visual:" + schemaKey);
    }

    private static CandidateValue semanticRefinement(CandidateValue json, ReplayVisualField visual) {
        if (!visual.array() && json.kind() == CandidateValueKind.TEXT
                && (visual.type() == CandidateValueKind.DATE || visual.type() == CandidateValueKind.TIME)) {
            return CandidateValue.scalar(visual.type());
        }
        return null;
    }

    private static boolean compatible(CandidateValue json, ReplayVisualField visual) {
        if (visual.array()) {
            if (json.kind() != CandidateValueKind.ARRAY || json.items() == null) return false;
            if (json.items().kind() == CandidateValueKind.UNRESOLVED) return true;
            return compatibleKind(json.items().kind(), visual.type());
        }
        return compatibleKind(json.kind(), visual.type()) || semanticRefinement(json, visual) != null;
    }

    private static boolean compatibleKind(CandidateValueKind json, CandidateValueKind visual) {
        return json == visual || json == CandidateValueKind.REFERENCE && visual == CandidateValueKind.REFERENCE;
    }

    private static String describe(CandidateValue value) {
        if (value.kind() == CandidateValueKind.ARRAY && value.items() != null) {
            return "ARRAY[" + describe(value.items()) + "]";
        }
        return value.kind().name();
    }

    private static List<CandidateEvidence> appendEvidence(
            List<CandidateEvidence> existing,
            CandidateEvidence addition
    ) {
        var evidence = new ArrayList<>(existing);
        evidence.add(addition);
        return List.copyOf(evidence);
    }

    private static String escapePointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static void validateInputs(
            InferenceMode mode,
            List<String> imageArtifactIds,
            JsonStructuralProfile jsonProfile
    ) {
        var hasImages = !imageArtifactIds.isEmpty();
        var hasJson = jsonProfile != null;
        var valid = switch (mode) {
            case IMAGE_ONLY -> hasImages && !hasJson;
            case JSON_ONLY -> !hasImages && hasJson;
            case COMBINED -> hasImages && hasJson;
        };
        if (!valid) throw new IllegalArgumentException("Replay inputs do not match inference mode " + mode);
    }
}
