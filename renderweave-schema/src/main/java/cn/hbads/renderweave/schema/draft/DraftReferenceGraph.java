package cn.hbads.renderweave.schema.draft;

import cn.hbads.renderweave.schema.definition.InvalidSchemaDefinitionException;
import cn.hbads.renderweave.schema.definition.SchemaProblem;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure validation for the active live-Draft reference projection. */
public final class DraftReferenceGraph {

    public static final int MAX_DEPTH = 16;

    private DraftReferenceGraph() {
    }

    public static void validateReplacement(
            SchemaKey source,
            List<DraftReferenceTarget> proposedDraftReferences,
            List<StaticReferenceTarget> proposedStaticReferences,
            Set<SchemaKey> activeDrafts,
            Map<SchemaKey, Set<SchemaKey>> activeDraftEdges,
            Map<SchemaKey, Set<StaticSchemaRef>> activeStaticEdges,
            Map<StaticSchemaRef, Integer> staticDepths
    ) {
        var prospectiveDrafts = new HashSet<>(activeDrafts);
        prospectiveDrafts.add(source);

        var missing = new ArrayList<SchemaProblem>();
        proposedDraftReferences.stream()
                .filter(reference -> !prospectiveDrafts.contains(reference.schemaKey()))
                .map(reference -> new SchemaProblem(
                        "SCHEMA_REFERENCE_NOT_FOUND",
                        reference.pointer(),
                        "Referenced active Draft does not exist: " + reference.schemaKey().value()
                ))
                .forEach(missing::add);
        proposedStaticReferences.stream()
                .filter(reference -> !staticDepths.containsKey(reference.reference()))
                .map(reference -> new SchemaProblem(
                        "STATIC_SCHEMA_REFERENCE_NOT_FOUND",
                        reference.pointer(),
                        "Referenced StaticSchema does not exist: "
                                + reference.reference().schemaKey().value()
                                + "@" + reference.reference().versionTag().value()
                ))
                .forEach(missing::add);
        if (!missing.isEmpty()) {
            throw new InvalidSchemaDefinitionException(missing);
        }

        var prospectiveDraftEdges = copyDraftEdges(activeDraftEdges, prospectiveDrafts);
        prospectiveDraftEdges.put(source, proposedDraftReferences.stream()
                .map(DraftReferenceTarget::schemaKey)
                .collect(LinkedHashSet::new, Set::add, Set::addAll));
        var prospectiveStaticEdges = copyStaticEdges(activeStaticEdges, prospectiveDrafts);
        prospectiveStaticEdges.put(source, proposedStaticReferences.stream()
                .map(StaticReferenceTarget::reference)
                .collect(LinkedHashSet::new, Set::add, Set::addAll));

        var cycles = new ArrayList<SchemaProblem>();
        for (var reference : proposedDraftReferences) {
            if (reaches(reference.schemaKey(), source, prospectiveDraftEdges, new HashSet<>())) {
                cycles.add(new SchemaProblem(
                        "SCHEMA_REFERENCE_CYCLE",
                        reference.pointer(),
                        "Reference would create a cycle through " + reference.schemaKey().value()
                ));
            }
        }
        if (!cycles.isEmpty()) {
            throw new InvalidSchemaDefinitionException(cycles);
        }

        var memoizedDepth = new HashMap<SchemaKey, Integer>();
        var maximumDepth = prospectiveDrafts.stream()
                .mapToInt(node -> depth(
                        node,
                        prospectiveDraftEdges,
                        prospectiveStaticEdges,
                        staticDepths,
                        memoizedDepth
                ))
                .max()
                .orElse(1);
        if (maximumDepth > MAX_DEPTH) {
            var pointer = !proposedDraftReferences.isEmpty()
                    ? proposedDraftReferences.getFirst().pointer()
                    : proposedStaticReferences.isEmpty() ? "" : proposedStaticReferences.getFirst().pointer();
            throw new InvalidSchemaDefinitionException(List.of(new SchemaProblem(
                    "SCHEMA_REFERENCE_DEPTH_EXCEEDED",
                    pointer,
                    "Reference graph depth " + maximumDepth + " exceeds maximum " + MAX_DEPTH
            )));
        }
    }

    private static Map<SchemaKey, Set<SchemaKey>> copyDraftEdges(
            Map<SchemaKey, Set<SchemaKey>> activeEdges,
            Set<SchemaKey> activeDrafts
    ) {
        var result = new HashMap<SchemaKey, Set<SchemaKey>>();
        for (var entry : activeEdges.entrySet()) {
            if (!activeDrafts.contains(entry.getKey())) {
                continue;
            }
            var targets = new LinkedHashSet<SchemaKey>();
            for (var target : entry.getValue()) {
                if (activeDrafts.contains(target)) {
                    targets.add(target);
                }
            }
            result.put(entry.getKey(), targets);
        }
        return result;
    }

    private static Map<SchemaKey, Set<StaticSchemaRef>> copyStaticEdges(
            Map<SchemaKey, Set<StaticSchemaRef>> activeEdges,
            Set<SchemaKey> activeDrafts
    ) {
        var result = new HashMap<SchemaKey, Set<StaticSchemaRef>>();
        for (var entry : activeEdges.entrySet()) {
            if (activeDrafts.contains(entry.getKey())) {
                result.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
        }
        return result;
    }

    private static boolean reaches(
            SchemaKey current,
            SchemaKey target,
            Map<SchemaKey, Set<SchemaKey>> edges,
            Set<SchemaKey> visited
    ) {
        if (current.equals(target)) {
            return true;
        }
        if (!visited.add(current)) {
            return false;
        }
        for (var next : edges.getOrDefault(current, Set.of())) {
            if (reaches(next, target, edges, visited)) {
                return true;
            }
        }
        return false;
    }

    private static int depth(
            SchemaKey current,
            Map<SchemaKey, Set<SchemaKey>> draftEdges,
            Map<SchemaKey, Set<StaticSchemaRef>> staticEdges,
            Map<StaticSchemaRef, Integer> staticDepths,
            Map<SchemaKey, Integer> memoized
    ) {
        var known = memoized.get(current);
        if (known != null) {
            return known;
        }
        var result = 1;
        for (var next : draftEdges.getOrDefault(current, Set.of())) {
            result = Math.max(result, 1 + depth(
                    next, draftEdges, staticEdges, staticDepths, memoized
            ));
        }
        for (var next : staticEdges.getOrDefault(current, Set.of())) {
            var staticDepth = staticDepths.get(next);
            if (staticDepth == null) {
                throw new IllegalStateException("Validated StaticSchema depth disappeared: " + next);
            }
            result = Math.max(result, 1 + staticDepth);
        }
        memoized.put(current, result);
        return result;
    }
}
