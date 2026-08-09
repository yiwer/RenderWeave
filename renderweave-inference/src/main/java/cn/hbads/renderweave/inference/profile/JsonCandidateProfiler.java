package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
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
import cn.hbads.renderweave.schema.identity.FieldKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Applies the approved JSON-first structural rules without an LLM or sample values. */
public final class JsonCandidateProfiler {

    public CandidateProfileResult infer(
            UUID runId,
            String proposedRootSchemaKey,
            String rootDisplayName,
            JsonStructuralProfile profile
    ) {
        return infer(runId, proposedRootSchemaKey, rootDisplayName, profile, false);
    }

    /** Builds the deterministic JSON base for a live AI workflow. */
    public CandidateProfileResult inferLive(
            UUID runId,
            String proposedRootSchemaKey,
            String rootDisplayName,
            JsonStructuralProfile profile
    ) {
        var result = infer(runId, proposedRootSchemaKey, rootDisplayName, profile, true);
        var liveProblems = result.semanticProblems().stream().map(problem ->
                "NESTED_ARRAY_UNSUPPORTED".equals(problem.code())
                        ? new CandidateProblem(
                                "JSON_NESTED_ARRAY_OBSERVED",
                                CandidateProblemSeverity.WARNING,
                                problem.itemId(),
                                problem.pointer(),
                                problem.args()
                        )
                        : problem
        ).toList();
        return new CandidateProfileResult(result.candidate(), liveProblems);
    }

    private CandidateProfileResult infer(
            UUID runId,
            String proposedRootSchemaKey,
            String rootDisplayName,
            JsonStructuralProfile profile,
            boolean inferred
    ) {
        var nodes = new LinkedHashMap<String, JsonObservedNode>();
        profile.nodes().forEach(node -> nodes.put(node.pointer(), node));
        var schemaPaths = reachableSchemaPaths(nodes);
        var schemaIds = new LinkedHashMap<String, UUID>();
        schemaPaths.forEach(path -> schemaIds.put(path, CandidateIds.schema(runId, path)));

        var problems = new ArrayList<CandidateProblem>();
        var schemas = new ArrayList<CandidateSchema>();
        var usedSchemaKeys = new HashMap<String, String>();
        for (var schemaPath : schemaPaths) {
            var schemaNode = nodes.get(schemaPath);
            var schemaId = schemaIds.get(schemaPath);
            var fields = immediateChildren(schemaPath, nodes).stream()
                    .map(node -> field(runId, schemaPath, node, nodes, schemaIds, problems, inferred))
                    .toList();
            var proposedKey = schemaPath.isEmpty()
                    ? proposedRootSchemaKey
                    : childSchemaKey(proposedRootSchemaKey, schemaPath, usedSchemaKeys);
            usedSchemaKeys.put(proposedKey, schemaPath);
            var displayName = schemaPath.isEmpty() ? rootDisplayName : unescape(lastSegment(schemaPath));
            schemas.add(new CandidateSchema(
                    schemaId,
                    proposedKey,
                    displayName,
                    CandidateSource.AI,
                    assessment(9_400, evidence(schemaNode), inferred),
                    fields
            ));
        }
        return new CandidateProfileResult(
                new CandidateBundle(CandidateBundle.CONTRACT_VERSION, schemaIds.get(""), schemas),
                problems
        );
    }

    private static List<String> reachableSchemaPaths(Map<String, JsonObservedNode> nodes) {
        var result = new ArrayList<String>();
        var discovered = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        discovered.add("");
        queue.add("");
        while (!queue.isEmpty()) {
            var schemaPath = queue.removeFirst();
            result.add(schemaPath);
            for (var field : immediateChildren(schemaPath, nodes)) {
                String childPath = null;
                if (field.kinds().equals(Set.of("object"))) {
                    childPath = field.pointer();
                } else if (field.kinds().equals(Set.of("array"))) {
                    var item = nodes.get(field.pointer() + "/*");
                    if (item != null && item.kinds().equals(Set.of("object"))) childPath = item.pointer();
                }
                if (childPath != null && discovered.add(childPath)) queue.addLast(childPath);
            }
        }
        return List.copyOf(result);
    }

    private static List<JsonObservedNode> immediateChildren(
            String schemaPath,
            Map<String, JsonObservedNode> nodes
    ) {
        return nodes.values().stream()
                .filter(node -> !node.pointer().isEmpty())
                .filter(node -> parent(node.pointer()).equals(schemaPath))
                .filter(node -> !lastSegment(node.pointer()).equals("*"))
                .sorted(Comparator.comparing(JsonObservedNode::pointer))
                .toList();
    }

    private static CandidateField field(
            UUID runId,
            String schemaPath,
            JsonObservedNode node,
            Map<String, JsonObservedNode> nodes,
            Map<String, UUID> schemaIds,
            List<CandidateProblem> problems,
            boolean inferredAssessment
    ) {
        var exactFieldKey = unescape(lastSegment(node.pointer()));
        var fieldId = CandidateIds.field(runId, schemaPath, exactFieldKey);
        var inferred = inferValue(node, nodes, schemaIds, fieldId, problems);
        var fieldKey = representable(exactFieldKey) ? exactFieldKey : null;
        var resolution = fieldKey == null || inferred.confidenceBps() < 8_000
                ? CandidateResolution.UNRESOLVED
                : CandidateResolution.NOT_REQUIRED;
        return new CandidateField(
                fieldId,
                fieldKey,
                fieldKey == null ? node.pointer() : fieldKey,
                false,
                inferred.value(),
                CandidateSource.AI,
                new CandidateAssessment(
                        inferred.confidenceBps(), inferredAssessment, resolution, evidence(node)
                )
        );
    }

    private static boolean representable(String fieldKey) {
        try {
            FieldKey.of(fieldKey);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static InferredValue inferValue(
            JsonObservedNode node,
            Map<String, JsonObservedNode> nodes,
            Map<String, UUID> schemaIds,
            UUID fieldId,
            List<CandidateProblem> problems
    ) {
        if (node.kinds().equals(Set.of("object"))) {
            return new InferredValue(
                    CandidateValue.reference(CandidateReference.candidate(schemaIds.get(node.pointer()))), 9_500
            );
        }
        if (node.kinds().equals(Set.of("array"))) {
            return inferArray(node, nodes.get(node.pointer() + "/*"), schemaIds, fieldId, problems);
        }
        if (node.kinds().contains("object") || node.kinds().contains("array")) {
            add(problems, "STRUCTURAL_TYPE_CONFLICT", CandidateProblemSeverity.BLOCKER,
                    fieldId, node.pointer(), node.kinds());
            return new InferredValue(CandidateValue.conflict(sorted(node.kinds())), 4_000);
        }
        return inferScalar(node.kinds(), fieldId, node.pointer(), false, problems);
    }

    private static InferredValue inferArray(
            JsonObservedNode array,
            JsonObservedNode item,
            Map<String, UUID> schemaIds,
            UUID fieldId,
            List<CandidateProblem> problems
    ) {
        if (item == null) {
            add(problems, "EMPTY_ARRAY_ITEM_UNRESOLVED", CandidateProblemSeverity.BLOCKER,
                    fieldId, array.pointer(), Set.of());
            return new InferredValue(CandidateValue.array(CandidateValue.unresolved("empty-array")), 4_000);
        }
        if (item.kinds().contains("array")) {
            add(problems, "NESTED_ARRAY_UNSUPPORTED", CandidateProblemSeverity.BLOCKER,
                    fieldId, item.pointer(), item.kinds());
            return new InferredValue(CandidateValue.array(CandidateValue.unresolved("nested-array")), 3_000);
        }
        if (item.kinds().equals(Set.of("object"))) {
            return new InferredValue(CandidateValue.array(CandidateValue.reference(
                    CandidateReference.candidate(schemaIds.get(item.pointer()))
            )), 9_500);
        }
        if (item.kinds().contains("object")) {
            add(problems, "HETEROGENEOUS_ARRAY", CandidateProblemSeverity.BLOCKER,
                    fieldId, item.pointer(), item.kinds());
            return new InferredValue(CandidateValue.array(CandidateValue.conflict(sorted(item.kinds()))), 3_500);
        }
        return inferScalar(item.kinds(), fieldId, item.pointer(), true, problems).insideArray();
    }

    private static InferredValue inferScalar(
            Set<String> observedKinds,
            UUID fieldId,
            String pointer,
            boolean arrayItem,
            List<CandidateProblem> problems
    ) {
        var concrete = new LinkedHashSet<>(observedKinds);
        var sawNull = concrete.remove("null");
        if (concrete.isEmpty()) {
            add(problems, "ALL_NULL_TYPE_UNRESOLVED", CandidateProblemSeverity.BLOCKER,
                    fieldId, pointer, observedKinds);
            return new InferredValue(CandidateValue.unresolved("null"), 3_000);
        }
        if (concrete.size() > 1) {
            if (arrayItem) {
                add(problems, "HETEROGENEOUS_ARRAY", CandidateProblemSeverity.BLOCKER,
                        fieldId, pointer, observedKinds);
                return new InferredValue(CandidateValue.conflict(sorted(observedKinds)), 3_500);
            }
            add(problems, "SCALAR_TYPE_DOWNGRADED_TO_TEXT", CandidateProblemSeverity.WARNING,
                    fieldId, pointer, observedKinds);
            return new InferredValue(CandidateValue.scalar(CandidateValueKind.TEXT), 6_500);
        }
        var value = switch (concrete.getFirst()) {
            case "text" -> CandidateValue.scalar(CandidateValueKind.TEXT);
            case "decimal" -> CandidateValue.scalar(CandidateValueKind.DECIMAL);
            case "boolean" -> CandidateValue.scalar(CandidateValueKind.BOOLEAN);
            default -> CandidateValue.unresolved(concrete.getFirst());
        };
        if (sawNull) {
            add(problems, "NULL_ADAPTATION_REQUIRED", CandidateProblemSeverity.WARNING,
                    fieldId, pointer, observedKinds);
            return new InferredValue(value, 7_000);
        }
        return new InferredValue(value, 9_600);
    }

    private static List<CandidateEvidence> evidence(JsonObservedNode node) {
        return node.evidence().stream()
                .map(location -> CandidateEvidence.json(location.sampleIndex(), location.jsonPointer()))
                .toList();
    }

    private static CandidateAssessment assessment(
            int confidenceBps,
            List<CandidateEvidence> evidence,
            boolean inferred
    ) {
        return CandidateAssessment.ai(
                confidenceBps, inferred, CandidateResolution.NOT_REQUIRED, evidence
        );
    }

    private static void add(
            List<CandidateProblem> problems,
            String code,
            CandidateProblemSeverity severity,
            UUID itemId,
            String pointer,
            Set<String> observedKinds
    ) {
        problems.add(new CandidateProblem(
                code, severity, itemId, pointer,
                observedKinds.isEmpty() ? Map.of() : Map.of("observedKinds", String.join(",", sortedList(observedKinds)))
        ));
    }

    private static String childSchemaKey(
            String rootKey,
            String schemaPath,
            Map<String, String> usedKeys
    ) {
        var pathSlug = schemaPath.substring(1).replace("/*", "")
                .replaceAll("~[01]", "-")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (pathSlug.isBlank()) pathSlug = "child";
        var candidate = rootKey + "-" + pathSlug;
        if (candidate.length() <= 63 && !usedKeys.containsKey(candidate)) return candidate;
        var suffix = sha256(schemaPath).substring(0, 8);
        var prefixLength = Math.max(1, 63 - suffix.length() - 1);
        var prefix = candidate.substring(0, Math.min(candidate.length(), prefixLength)).replaceAll("-$", "");
        return prefix + "-" + suffix;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String parent(String pointer) {
        var lastSlash = pointer.lastIndexOf('/');
        return lastSlash <= 0 ? "" : pointer.substring(0, lastSlash);
    }

    private static String lastSegment(String pointer) {
        return pointer.substring(pointer.lastIndexOf('/') + 1);
    }

    private static String unescape(String segment) {
        return segment.replace("~1", "/").replace("~0", "~");
    }

    private static String[] sorted(Set<String> values) {
        return sortedList(values).toArray(String[]::new);
    }

    private static List<String> sortedList(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private record InferredValue(CandidateValue value, int confidenceBps) {
        InferredValue insideArray() {
            return new InferredValue(CandidateValue.array(value), confidenceBps);
        }
    }
}
