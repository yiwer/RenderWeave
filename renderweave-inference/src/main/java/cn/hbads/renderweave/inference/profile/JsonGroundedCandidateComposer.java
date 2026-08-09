package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateEvidenceKind;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateIds;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateProblemSeverity;
import cn.hbads.renderweave.inference.candidate.CandidateReferenceKind;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import cn.hbads.renderweave.schema.identity.FieldKey;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Treats deterministic JSON structure as authoritative and extracts only bounded visual semantics
 * from an untrusted provider Candidate.
 */
public final class JsonGroundedCandidateComposer {
    private final JsonCandidateProfiler jsonProfiler;

    public JsonGroundedCandidateComposer() {
        this(new JsonCandidateProfiler());
    }

    JsonGroundedCandidateComposer(JsonCandidateProfiler jsonProfiler) {
        this.jsonProfiler = Objects.requireNonNull(jsonProfiler, "jsonProfiler");
    }

    public CandidateProfileResult compose(
            UUID runId,
            String fallbackRootSchemaKey,
            String fallbackDisplayName,
            JsonStructuralProfile jsonProfile,
            Set<String> imageArtifactIds,
            CandidateBundle visualProposal,
            int lowConfidenceThresholdBps
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(jsonProfile, "jsonProfile");
        imageArtifactIds = Set.copyOf(Objects.requireNonNull(imageArtifactIds, "imageArtifactIds"));
        Objects.requireNonNull(visualProposal, "visualProposal");
        if (lowConfidenceThresholdBps < 0 || lowConfidenceThresholdBps > 10_000) {
            throw new IllegalArgumentException("lowConfidenceThresholdBps must be 0..10000");
        }

        var providerGraph = ProviderGraph.from(visualProposal);
        var providerRoot = providerGraph.byPath().get("/");
        var rootKey = requireSchemaKey(fallbackRootSchemaKey);
        var rootDisplayName = providerRoot != null
                && !validImageEvidence(providerRoot.assessment().evidence(), imageArtifactIds).isEmpty()
                && text(providerRoot.displayName())
                ? providerRoot.displayName()
                : requireText(fallbackDisplayName, "fallbackDisplayName");

        var base = jsonProfiler.inferLive(runId, rootKey, rootDisplayName, jsonProfile);
        var basePaths = canonicalPaths(base.candidate());
        var problems = new ArrayList<>(base.semanticProblems());
        var schemas = new ArrayList<CandidateSchema>();
        for (var baseSchema : base.candidate().schemas()) {
            var path = basePaths.get(baseSchema.candidateSchemaId());
            var proposalSchema = path == null ? null : providerGraph.byPath().get(path);
            schemas.add(composeSchema(
                    runId,
                    path == null ? "/@unmatched-" + baseSchema.candidateSchemaId() : path,
                    baseSchema, proposalSchema, imageArtifactIds,
                    lowConfidenceThresholdBps, problems
            ));
        }

        if (providerGraph.ambiguous()) {
            problems.add(problem(
                    "VISUAL_GRAPH_AMBIGUOUS_IGNORED", null, "/schemas",
                    Map.of("count", Integer.toString(visualProposal.schemas().size()))
            ));
        }

        var basePathSet = Set.copyOf(basePaths.values());
        var ignoredSchemas = providerGraph.byPath().keySet().stream()
                .filter(path -> !basePathSet.contains(path))
                .count();
        ignoredSchemas += providerGraph.unreachableSchemaCount();
        if (ignoredSchemas > 0) {
            problems.add(problem(
                    "VISUAL_SCHEMA_ADDITION_IGNORED", null, "/schemas",
                    Map.of("count", Long.toString(ignoredSchemas))
            ));
        }

        return new CandidateProfileResult(
                new CandidateBundle(
                        CandidateBundle.CONTRACT_VERSION,
                        base.candidate().rootCandidateSchemaId(),
                        schemas
                ),
                problems
        );
    }

    private static CandidateSchema composeSchema(
            UUID runId,
            String schemaPath,
            CandidateSchema base,
            CandidateSchema proposal,
            Set<String> imageArtifactIds,
            int threshold,
            List<CandidateProblem> problems
    ) {
        var schemaImages = proposal == null
                ? List.<CandidateEvidence>of()
                : validImageEvidence(proposal.assessment().evidence(), imageArtifactIds);
        var proposalFields = uniqueFields(proposal);
        var baseKeys = new LinkedHashSet<String>();
        var fields = new ArrayList<CandidateField>();
        for (var baseField : base.fields()) {
            baseKeys.add(baseField.proposedFieldKey());
            var proposalField = baseField.proposedFieldKey() == null
                    ? null
                    : proposalFields.get(baseField.proposedFieldKey());
            fields.add(composeBaseField(
                    baseField,
                    proposalField,
                    imageArtifactIds,
                    threshold,
                    problems
            ));
        }
        if (proposal != null) {
            for (var proposalField : proposal.fields()) {
                if (proposalField.assessment().resolution() == CandidateResolution.REMOVED
                        || baseKeys.contains(proposalField.proposedFieldKey())) {
                    continue;
                }
                var addition = visualOnlyField(
                        runId, schemaPath, proposalField, imageArtifactIds, threshold
                );
                if (addition == null) {
                    problems.add(problem(
                            "VISUAL_FIELD_ADDITION_UNSUPPORTED",
                            null,
                            fieldPointer(schemaPath, proposalField.proposedFieldKey()),
                            Map.of()
                    ));
                } else if (fields.stream().noneMatch(field ->
                        Objects.equals(field.proposedFieldKey(), addition.proposedFieldKey()))) {
                    fields.add(addition);
                }
            }
        }
        fields.sort(Comparator.comparing(
                CandidateField::proposedFieldKey,
                Comparator.nullsLast(Comparator.naturalOrder())
        ));

        return new CandidateSchema(
                base.candidateSchemaId(),
                base.proposedSchemaKey(),
                proposal != null && !schemaImages.isEmpty() && text(proposal.displayName())
                        ? proposal.displayName() : base.displayName(),
                CandidateSource.AI,
                assessment(
                        base.assessment().confidenceBps(),
                        baseValueResolution(base.assessment().confidenceBps(), null, threshold),
                        append(base.assessment().evidence(), schemaImages)
                ),
                fields
        );
    }

    private static CandidateField composeBaseField(
            CandidateField base,
            CandidateField proposal,
            Set<String> imageArtifactIds,
            int threshold,
            List<CandidateProblem> problems
    ) {
        var images = proposal == null
                ? List.<CandidateEvidence>of()
                : validImageEvidence(proposal.assessment().evidence(), imageArtifactIds);
        var value = base.value();
        var confidence = base.assessment().confidenceBps();
        if (proposal != null && !images.isEmpty()) {
            var refinement = refinement(base.value(), proposal.value(), proposal.assessment(), threshold);
            if (refinement != null) {
                value = refinement;
                confidence = Math.min(confidence, proposal.assessment().confidenceBps());
            } else if (!sameShape(base.value(), proposal.value())) {
                problems.add(problem(
                        "VISUAL_TYPE_CONFLICT_IGNORED",
                        base.candidateFieldId(),
                        "/fields/" + escape(base.proposedFieldKey()) + "/value",
                        Map.of("jsonKind", describe(base.value()), "visualKind", describe(proposal.value()))
                ));
            }
        }
        return new CandidateField(
                base.candidateFieldId(),
                base.proposedFieldKey(),
                proposal != null && !images.isEmpty() && text(proposal.displayName())
                        ? proposal.displayName() : base.displayName(),
                false,
                value,
                CandidateSource.AI,
                assessment(
                        confidence,
                        fieldResolution(base.proposedFieldKey(), confidence, value, threshold),
                        append(base.assessment().evidence(), images)
                )
        );
    }

    private static CandidateField visualOnlyField(
            UUID runId,
            String schemaPath,
            CandidateField proposal,
            Set<String> imageArtifactIds,
            int threshold
    ) {
        if (!validFieldKey(proposal.proposedFieldKey())
                || proposal.assessment().confidenceBps() == null
                || proposal.assessment().confidenceBps() < 0
                || proposal.assessment().confidenceBps() > 10_000) {
            return null;
        }
        var images = validImageEvidence(proposal.assessment().evidence(), imageArtifactIds);
        var value = safeVisualValue(proposal.value());
        if (images.isEmpty() || value == null) return null;
        var confidence = proposal.assessment().confidenceBps();
        return new CandidateField(
                CandidateIds.field(runId, schemaPath, proposal.proposedFieldKey()),
                proposal.proposedFieldKey(),
                text(proposal.displayName()) ? proposal.displayName() : proposal.proposedFieldKey(),
                false,
                value,
                CandidateSource.AI,
                assessment(
                        confidence,
                        baseValueResolution(confidence, value, threshold),
                        images
                )
        );
    }

    private static CandidateValue safeVisualValue(CandidateValue value) {
        if (value == null) return null;
        if (supportedScalar(value.kind())) return CandidateValue.scalar(value.kind());
        if (value.kind() == CandidateValueKind.ARRAY && value.items() != null
                && supportedScalar(value.items().kind())) {
            return CandidateValue.array(CandidateValue.scalar(value.items().kind()));
        }
        return null;
    }

    private static CandidateValue refinement(
            CandidateValue base,
            CandidateValue proposal,
            CandidateAssessment assessment,
            int threshold
    ) {
        if (base == null || proposal == null || assessment.confidenceBps() == null
                || assessment.confidenceBps() < threshold) {
            return null;
        }
        if (base.kind() == CandidateValueKind.TEXT
                && (proposal.kind() == CandidateValueKind.DATE
                || proposal.kind() == CandidateValueKind.TIME)) {
            return CandidateValue.scalar(proposal.kind());
        }
        return null;
    }

    private static CandidateResolution baseValueResolution(
            int confidence,
            CandidateValue value,
            int threshold
    ) {
        if (confidence < threshold || uncertain(value)) return CandidateResolution.UNRESOLVED;
        return CandidateResolution.NOT_REQUIRED;
    }

    private static CandidateResolution fieldResolution(
            String fieldKey,
            int confidence,
            CandidateValue value,
            int threshold
    ) {
        return fieldKey == null
                ? CandidateResolution.UNRESOLVED
                : baseValueResolution(confidence, value, threshold);
    }

    private static boolean uncertain(CandidateValue value) {
        if (value == null) return false;
        if (value.kind() == CandidateValueKind.UNRESOLVED || value.kind() == CandidateValueKind.CONFLICT) {
            return true;
        }
        return value.kind() == CandidateValueKind.ARRAY && uncertain(value.items());
    }

    private static CandidateAssessment assessment(
            int confidence,
            CandidateResolution resolution,
            List<CandidateEvidence> evidence
    ) {
        return CandidateAssessment.ai(confidence, true, resolution, evidence);
    }

    private static Map<String, CandidateField> uniqueFields(CandidateSchema schema) {
        if (schema == null) return Map.of();
        var counts = new HashMap<String, Integer>();
        for (var field : schema.fields()) {
            if (validFieldKey(field.proposedFieldKey())) {
                counts.merge(field.proposedFieldKey(), 1, Integer::sum);
            }
        }
        var result = new LinkedHashMap<String, CandidateField>();
        for (var field : schema.fields()) {
            if (field.assessment().resolution() != CandidateResolution.REMOVED
                    && validFieldKey(field.proposedFieldKey())
                    && counts.getOrDefault(field.proposedFieldKey(), 0) == 1) {
                result.put(field.proposedFieldKey(), field);
            }
        }
        return Map.copyOf(result);
    }

    private static List<CandidateEvidence> validImageEvidence(
            List<CandidateEvidence> evidence,
            Set<String> artifactIds
    ) {
        if (evidence == null) return List.of();
        var result = new LinkedHashSet<CandidateEvidence>();
        for (var item : evidence) {
            if (item == null || item.kind() != CandidateEvidenceKind.IMAGE
                    || !artifactIds.contains(item.artifactId())
                    || item.boundingBox() == null || item.sampleIndex() != null || item.jsonPointer() != null) {
                continue;
            }
            var box = item.boundingBox();
            if (box.left() < 0 || box.top() < 0 || box.right() > 10_000 || box.bottom() > 10_000
                    || box.left() >= box.right() || box.top() >= box.bottom()) {
                continue;
            }
            result.add(item);
        }
        return List.copyOf(result);
    }

    private static List<CandidateEvidence> append(
            List<CandidateEvidence> base,
            List<CandidateEvidence> additions
    ) {
        var result = new LinkedHashSet<CandidateEvidence>(base);
        result.addAll(additions);
        return List.copyOf(result);
    }

    private static Map<UUID, String> canonicalPaths(CandidateBundle candidate) {
        var schemas = new LinkedHashMap<UUID, CandidateSchema>();
        for (var schema : candidate.schemas()) schemas.put(schema.candidateSchemaId(), schema);
        var paths = new LinkedHashMap<UUID, String>();
        if (!schemas.containsKey(candidate.rootCandidateSchemaId())) return paths;
        paths.put(candidate.rootCandidateSchemaId(), "/");
        var queue = new ArrayDeque<UUID>();
        queue.add(candidate.rootCandidateSchemaId());
        while (!queue.isEmpty()) {
            var sourceId = queue.removeFirst();
            var source = schemas.get(sourceId);
            for (var field : source.fields()) {
                var target = target(field.value());
                if (target == null || !schemas.containsKey(target) || paths.containsKey(target)
                        || !validFieldKey(field.proposedFieldKey())) {
                    continue;
                }
                paths.put(target, childPath(paths.get(sourceId), field.proposedFieldKey()));
                queue.addLast(target);
            }
        }
        return Map.copyOf(paths);
    }

    private static UUID target(CandidateValue value) {
        if (value == null) return null;
        if (!value.observedKinds().isEmpty() || !value.constraints().isEmpty()) return null;
        if (value.kind() == CandidateValueKind.ARRAY) {
            if (value.reference() != null || value.items() == null) return null;
            return directCandidateTarget(value.items());
        }
        return directCandidateTarget(value);
    }

    private static UUID directCandidateTarget(CandidateValue value) {
        if (value == null || value.kind() != CandidateValueKind.REFERENCE
                || value.items() != null || value.reference() == null
                || !value.observedKinds().isEmpty() || !value.constraints().isEmpty()) {
            return null;
        }
        var reference = value.reference();
        if (reference.kind() != CandidateReferenceKind.CANDIDATE_SCHEMA
                || reference.candidateSchemaId() == null
                || reference.schemaKey() != null || reference.versionTag() != null) {
            return null;
        }
        return reference.candidateSchemaId();
    }

    private static String childPath(String parent, String fieldKey) {
        return "/".equals(parent) ? "/" + escape(fieldKey) : parent + "/" + escape(fieldKey);
    }

    private static boolean sameShape(CandidateValue left, CandidateValue right) {
        return describe(left).equals(describe(right));
    }

    private static String describe(CandidateValue value) {
        if (value == null) return "INVALID";
        if (value.kind() == CandidateValueKind.ARRAY) return "ARRAY:" + describe(value.items());
        return value.kind().name();
    }

    private static boolean supportedScalar(CandidateValueKind kind) {
        return kind == CandidateValueKind.TEXT || kind == CandidateValueKind.DECIMAL
                || kind == CandidateValueKind.DATE || kind == CandidateValueKind.TIME
                || kind == CandidateValueKind.BOOLEAN;
    }

    private static String requireSchemaKey(String value) {
        SchemaKey.userProvided(value);
        return value;
    }

    private static boolean validFieldKey(String value) {
        try {
            FieldKey.of(value);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank() && value.length() <= 128;
    }

    private static String requireText(String value, String name) {
        if (!text(value)) throw new IllegalArgumentException(name + " is invalid");
        return value;
    }

    private static String fieldPointer(String schemaPath, String key) {
        return schemaPath + "/fields/" + escape(key == null ? "@invalid" : key);
    }

    private static String escape(String value) {
        return value == null ? "@invalid" : value.replace("~", "~0").replace("/", "~1");
    }

    private static CandidateProblem problem(
            String code,
            UUID itemId,
            String pointer,
            Map<String, String> details
    ) {
        return new CandidateProblem(code, CandidateProblemSeverity.WARNING, itemId, pointer, details);
    }

    private record ProviderGraph(
            Map<String, CandidateSchema> byPath,
            long unreachableSchemaCount,
            boolean ambiguous
    ) {
        private ProviderGraph {
            byPath = Map.copyOf(byPath);
            if (unreachableSchemaCount < 0) throw new IllegalArgumentException("count is invalid");
        }

        static ProviderGraph from(CandidateBundle candidate) {
            var unique = new LinkedHashMap<UUID, CandidateSchema>();
            var duplicateIds = new LinkedHashSet<UUID>();
            for (var schema : candidate.schemas()) {
                if (unique.putIfAbsent(schema.candidateSchemaId(), schema) != null) {
                    duplicateIds.add(schema.candidateSchemaId());
                }
            }
            duplicateIds.forEach(unique::remove);
            var root = unique.get(candidate.rootCandidateSchemaId());
            if (root == null || !duplicateIds.isEmpty()) {
                return ambiguous(candidate);
            }
            var byPath = new LinkedHashMap<String, CandidateSchema>();
            var parentByTarget = new LinkedHashMap<UUID, String>();
            var visited = new LinkedHashSet<UUID>();
            var queue = new ArrayDeque<PathNode>();
            parentByTarget.put(root.candidateSchemaId(), "@root");
            queue.add(new PathNode("/", root));
            while (!queue.isEmpty()) {
                var current = queue.removeFirst();
                if (!visited.add(current.schema().candidateSchemaId())) return ambiguous(candidate);
                byPath.put(current.path(), current.schema());
                var activeFields = current.schema().fields().stream()
                        .filter(field -> field.assessment().resolution() != CandidateResolution.REMOVED)
                        .toList();
                var fieldKeys = new LinkedHashSet<String>();
                for (var field : activeFields) {
                    if (!validFieldKey(field.proposedFieldKey())
                            || !fieldKeys.add(field.proposedFieldKey())) {
                        return ambiguous(candidate);
                    }
                    var targetId = target(field.value());
                    if (targetId == null) {
                        if (referenceShaped(field.value())) return ambiguous(candidate);
                        continue;
                    }
                    var target = unique.get(targetId);
                    if (target == null) return ambiguous(candidate);
                    var edge = current.schema().candidateSchemaId() + "|" + field.proposedFieldKey();
                    if (parentByTarget.putIfAbsent(targetId, edge) != null) {
                        return ambiguous(candidate);
                    }
                    queue.add(new PathNode(childPath(current.path(), field.proposedFieldKey()), target));
                }
            }
            return new ProviderGraph(
                    byPath, candidate.schemas().size() - visited.size(), false
            );
        }

        private static ProviderGraph ambiguous(CandidateBundle candidate) {
            return new ProviderGraph(Map.of(), candidate.schemas().size(), true);
        }
    }

    private static boolean referenceShaped(CandidateValue value) {
        if (value == null) return false;
        if (value.kind() == CandidateValueKind.REFERENCE || value.reference() != null) return true;
        return value.items() != null && referenceShaped(value.items());
    }

    private record PathNode(String path, CandidateSchema schema) { }
}
