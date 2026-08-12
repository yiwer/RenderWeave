package cn.hbads.renderweave.inference.eval.visual;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Closed, versioned gold contract for the layered synthetic/CC0 evaluation corpus.
 * Text in this type is repository-controlled gold; runtime OCR text uses
 * {@link LayeredVisualPrediction} and never crosses the persistent record boundary.
 */
public record LayeredVisualAnnotation(
        String annotationVersion,
        String caseId,
        String renderIdentity,
        SourceLicense sourceLicense,
        List<OcrLine> ocrLines,
        List<OcrToken> ocrTokens,
        List<Region> regions,
        List<Evidence> evidence,
        List<PrecedenceEdge> precedenceEdges,
        List<RepeatGroup> repeatGroups,
        List<Entity> entities,
        List<Relationship> relationships,
        List<Binding> bindings,
        CandidateGold candidate,
        Abstention abstention
) {
    public static final String VERSION = "renderweave-layered-annotation/1.0";

    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{0,127}$");
    private static final Pattern KEY = Pattern.compile("^[a-z][a-zA-Z0-9-]{0,127}$");
    private static final Pattern IDENTITY = Pattern.compile("^[a-z][a-z0-9._+/-]{1,127}:[0-9a-f]{64}$");

    public LayeredVisualAnnotation {
        if (!VERSION.equals(annotationVersion)) {
            throw invalid("ANNOTATION_VERSION_INVALID");
        }
        caseId = requireId(caseId, "CASE_ID_INVALID");
        renderIdentity = requireIdentity(renderIdentity, "RENDER_IDENTITY_INVALID");
        Objects.requireNonNull(sourceLicense, "sourceLicense");
        ocrLines = immutable(ocrLines, 512, "OCR_LINE_COUNT_INVALID");
        ocrTokens = immutable(ocrTokens, 4_096, "OCR_TOKEN_COUNT_INVALID");
        regions = immutable(regions, 2_048, "REGION_COUNT_INVALID");
        evidence = immutable(evidence, 4_096, "EVIDENCE_COUNT_INVALID");
        precedenceEdges = immutable(precedenceEdges, 4_096, "PRECEDENCE_COUNT_INVALID");
        repeatGroups = immutable(repeatGroups, 256, "REPEAT_GROUP_COUNT_INVALID");
        entities = immutable(entities, 256, "ENTITY_COUNT_INVALID");
        relationships = immutable(relationships, 512, "RELATIONSHIP_COUNT_INVALID");
        bindings = immutable(bindings, 2_048, "BINDING_COUNT_INVALID");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(abstention, "abstention");
        validateClosure(ocrLines, ocrTokens, regions, evidence, precedenceEdges, repeatGroups,
                entities, relationships, bindings, candidate, abstention);
    }

    @Override
    public String toString() {
        return "LayeredVisualAnnotation[annotationVersion=" + annotationVersion
                + ", caseId=" + caseId + ", renderIdentity=" + renderIdentity
                + ", sourceLicense=" + sourceLicense + ", ocrLines=" + ocrLines.size()
                + ", ocrTokens=" + ocrTokens.size() + ", regions=" + regions.size()
                + ", evidence=" + evidence.size() + ", entities=" + entities.size()
                + ", relationships=" + relationships.size() + ", bindings=" + bindings.size() + "]";
    }

    public enum SourceLicense { SYNTHETIC, CC0 }

    public enum RegionKind { TITLE, SLOT, GROUP, REPEATED_GROUP, ITEM }

    public enum OwnerKind { OCR_LINE, OCR_TOKEN, REGION, ENTITY, RELATIONSHIP, BINDING, CANDIDATE_FIELD }

    public enum Multiplicity { ONE, MANY }

    public enum ValueKind { TEXT, DECIMAL, DATE, TIME, BOOLEAN, REFERENCE, ARRAY, UNRESOLVED }

    public record Point(int x, int y) {
        public Point {
            if (x < 0 || x > 10_000 || y < 0 || y > 10_000) {
                throw invalid("POINT_OUT_OF_RANGE");
            }
        }
    }

    public record Box(int left, int top, int right, int bottom) {
        public Box {
            if (left < 0 || top < 0 || right > 10_000 || bottom > 10_000
                    || left >= right || top >= bottom) {
                throw invalid("BOX_INVALID");
            }
        }

        public boolean contains(Box other) {
            Objects.requireNonNull(other, "other");
            return left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom;
        }
    }

    /** Exactly one of box or polygon is present. */
    public record Geometry(Box box, List<Point> polygon) {
        public Geometry {
            if ((box == null) == (polygon == null)) {
                throw invalid("GEOMETRY_SHAPE_INVALID");
            }
            if (polygon != null) {
                polygon = immutable(polygon, 32, "POLYGON_POINT_COUNT_INVALID");
                if (polygon.size() < 3 || new HashSet<>(polygon).size() < 3) {
                    throw invalid("POLYGON_POINT_COUNT_INVALID");
                }
                boundsOf(polygon);
            }
        }

        public static Geometry box(int left, int top, int right, int bottom) {
            return new Geometry(new Box(left, top, right, bottom), null);
        }

        public static Geometry polygon(List<Point> points) {
            return new Geometry(null, points);
        }

        public Box bounds() {
            return box != null ? box : boundsOf(polygon);
        }

        private static Box boundsOf(List<Point> points) {
            var left = points.stream().mapToInt(Point::x).min().orElseThrow();
            var top = points.stream().mapToInt(Point::y).min().orElseThrow();
            var right = points.stream().mapToInt(Point::x).max().orElseThrow();
            var bottom = points.stream().mapToInt(Point::y).max().orElseThrow();
            return new Box(left, top, right, bottom);
        }
    }

    public record OcrLine(String lineId, String text, List<String> tokenIds, Geometry geometry) {
        public OcrLine {
            lineId = requireId(lineId, "OCR_LINE_ID_INVALID");
            text = requireText(text, 2_048, "OCR_LINE_TEXT_INVALID");
            tokenIds = requireIds(tokenIds, 256, "OCR_LINE_TOKEN_IDS_INVALID");
            Objects.requireNonNull(geometry, "geometry");
        }
    }

    public record OcrToken(String tokenId, String lineId, String text, Geometry geometry) {
        public OcrToken {
            tokenId = requireId(tokenId, "OCR_TOKEN_ID_INVALID");
            lineId = requireId(lineId, "OCR_TOKEN_LINE_ID_INVALID");
            text = requireText(text, 256, "OCR_TOKEN_TEXT_INVALID");
            Objects.requireNonNull(geometry, "geometry");
        }
    }

    public record Region(String regionId, RegionKind kind, Geometry geometry) {
        public Region {
            regionId = requireId(regionId, "REGION_ID_INVALID");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(geometry, "geometry");
        }
    }

    public record Evidence(String evidenceId, OwnerKind ownerKind, String ownerId, Geometry geometry) {
        public Evidence {
            evidenceId = requireId(evidenceId, "EVIDENCE_ID_INVALID");
            Objects.requireNonNull(ownerKind, "ownerKind");
            ownerId = requireId(ownerId, "EVIDENCE_OWNER_ID_INVALID");
            Objects.requireNonNull(geometry, "geometry");
        }
    }

    public record PrecedenceEdge(String beforeRegionId, String afterRegionId) {
        public PrecedenceEdge {
            beforeRegionId = requireId(beforeRegionId, "PRECEDENCE_BEFORE_ID_INVALID");
            afterRegionId = requireId(afterRegionId, "PRECEDENCE_AFTER_ID_INVALID");
            if (beforeRegionId.equals(afterRegionId)) throw invalid("PRECEDENCE_SELF_EDGE");
        }
    }

    public record RepeatItem(String itemRegionId, List<String> memberRegionIds) {
        public RepeatItem {
            itemRegionId = requireId(itemRegionId, "REPEAT_ITEM_ID_INVALID");
            memberRegionIds = requireIds(memberRegionIds, 256, "REPEAT_MEMBERS_INVALID");
            if (memberRegionIds.isEmpty()) throw invalid("REPEAT_MEMBERS_EMPTY");
        }
    }

    public record RepeatGroup(String groupRegionId, int expectedItemCount, List<RepeatItem> items) {
        public RepeatGroup {
            groupRegionId = requireId(groupRegionId, "REPEAT_GROUP_ID_INVALID");
            items = immutable(items, 256, "REPEAT_ITEM_COUNT_INVALID");
            if (expectedItemCount < 1 || expectedItemCount != items.size()) {
                throw invalid("REPEAT_ITEM_COUNT_INVALID");
            }
        }
    }

    public record Entity(String entityId, String schemaKey, List<String> supportingRegionIds) {
        public Entity {
            entityId = requireId(entityId, "ENTITY_ID_INVALID");
            schemaKey = requireKey(schemaKey, "SCHEMA_KEY_INVALID");
            supportingRegionIds = requireIds(supportingRegionIds, 64, "ENTITY_SUPPORT_INVALID");
            if (supportingRegionIds.isEmpty()) throw invalid("ENTITY_SUPPORT_EMPTY");
        }
    }

    public record Relationship(
            String relationshipId,
            String parentEntityId,
            String childEntityId,
            String fieldKey,
            Multiplicity cardinality,
            List<String> supportingRegionIds
    ) {
        public Relationship {
            relationshipId = requireId(relationshipId, "RELATIONSHIP_ID_INVALID");
            parentEntityId = requireId(parentEntityId, "RELATIONSHIP_PARENT_INVALID");
            childEntityId = requireId(childEntityId, "RELATIONSHIP_CHILD_INVALID");
            if (parentEntityId.equals(childEntityId)) throw invalid("RELATIONSHIP_SELF_EDGE");
            fieldKey = requireKey(fieldKey, "RELATIONSHIP_FIELD_KEY_INVALID");
            Objects.requireNonNull(cardinality, "cardinality");
            supportingRegionIds = requireIds(supportingRegionIds, 64, "RELATIONSHIP_SUPPORT_INVALID");
            if (supportingRegionIds.isEmpty()) throw invalid("RELATIONSHIP_SUPPORT_EMPTY");
        }
    }

    public record Binding(String bindingId, String regionId, String entityId, String fieldKey) {
        public Binding {
            bindingId = requireId(bindingId, "BINDING_ID_INVALID");
            regionId = requireId(regionId, "BINDING_REGION_ID_INVALID");
            entityId = requireId(entityId, "BINDING_ENTITY_ID_INVALID");
            fieldKey = requireKey(fieldKey, "BINDING_FIELD_KEY_INVALID");
        }
    }

    public record CandidateField(
            String fieldId,
            String entityId,
            String fieldKey,
            ValueKind valueKind,
            String bindingId
    ) {
        public CandidateField {
            fieldId = requireId(fieldId, "CANDIDATE_FIELD_ID_INVALID");
            entityId = requireId(entityId, "CANDIDATE_FIELD_ENTITY_INVALID");
            fieldKey = requireKey(fieldKey, "CANDIDATE_FIELD_KEY_INVALID");
            Objects.requireNonNull(valueKind, "valueKind");
            bindingId = requireId(bindingId, "CANDIDATE_FIELD_BINDING_INVALID");
        }
    }

    public record CandidateGold(
            String rootEntityId,
            List<CandidateField> fields,
            List<String> relationshipIds,
            boolean topologyRequired
    ) {
        public CandidateGold {
            rootEntityId = requireId(rootEntityId, "CANDIDATE_ROOT_INVALID");
            fields = immutable(fields, 2_048, "CANDIDATE_FIELD_COUNT_INVALID");
            relationshipIds = requireIds(relationshipIds, 512, "CANDIDATE_RELATIONSHIPS_INVALID");
        }
    }

    public record Abstention(List<String> expectedUnresolvedOwnerIds) {
        public Abstention {
            expectedUnresolvedOwnerIds = requireIds(expectedUnresolvedOwnerIds, 2_048,
                    "ABSTENTION_OWNER_IDS_INVALID");
        }
    }

    private static void validateClosure(
            List<OcrLine> lines,
            List<OcrToken> tokens,
            List<Region> regions,
            List<Evidence> evidence,
            List<PrecedenceEdge> precedence,
            List<RepeatGroup> repeats,
            List<Entity> entities,
            List<Relationship> relationships,
            List<Binding> bindings,
            CandidateGold candidate,
            Abstention abstention
    ) {
        var lineById = index(lines, OcrLine::lineId, "DUPLICATE_OCR_LINE");
        var tokenById = index(tokens, OcrToken::tokenId, "DUPLICATE_OCR_TOKEN");
        var regionById = index(regions, Region::regionId, "DUPLICATE_REGION");
        index(evidence, Evidence::evidenceId, "DUPLICATE_EVIDENCE");
        var entityById = index(entities, Entity::entityId, "DUPLICATE_ENTITY");
        var relationshipById = index(relationships, Relationship::relationshipId,
                "DUPLICATE_RELATIONSHIP");
        var bindingById = index(bindings, Binding::bindingId, "DUPLICATE_BINDING");
        var candidateFieldById = index(candidate.fields(), CandidateField::fieldId,
                "DUPLICATE_CANDIDATE_FIELD");

        for (var line : lines) {
            for (var tokenId : line.tokenIds()) {
                var token = tokenById.get(tokenId);
                if (token == null || !token.lineId().equals(line.lineId())
                        || !line.geometry().bounds().contains(token.geometry().bounds())) {
                    throw invalid("OCR_TOKEN_CLOSURE_INVALID");
                }
            }
        }
        for (var token : tokens) {
            var line = lineById.get(token.lineId());
            if (line == null || !line.tokenIds().contains(token.tokenId())) {
                throw invalid("OCR_LINE_CLOSURE_INVALID");
            }
        }

        var ownerIds = new HashMap<OwnerKind, Set<String>>();
        ownerIds.put(OwnerKind.OCR_LINE, lineById.keySet());
        ownerIds.put(OwnerKind.OCR_TOKEN, tokenById.keySet());
        ownerIds.put(OwnerKind.REGION, regionById.keySet());
        ownerIds.put(OwnerKind.ENTITY, entityById.keySet());
        ownerIds.put(OwnerKind.RELATIONSHIP, relationshipById.keySet());
        ownerIds.put(OwnerKind.BINDING, bindingById.keySet());
        ownerIds.put(OwnerKind.CANDIDATE_FIELD, candidateFieldById.keySet());
        for (var item : evidence) {
            if (!ownerIds.get(item.ownerKind()).contains(item.ownerId())) {
                throw invalid("EVIDENCE_OWNER_DANGLING");
            }
        }

        var precedenceKeys = new HashSet<String>();
        var precedenceGraph = new HashMap<String, List<String>>();
        for (var edge : precedence) {
            if (!regionById.containsKey(edge.beforeRegionId()) || !regionById.containsKey(edge.afterRegionId())) {
                throw invalid("PRECEDENCE_EDGE_DANGLING");
            }
            if (!precedenceKeys.add(edge.beforeRegionId() + ">" + edge.afterRegionId())) {
                throw invalid("DUPLICATE_PRECEDENCE_EDGE");
            }
            precedenceGraph.computeIfAbsent(edge.beforeRegionId(), ignored -> new ArrayList<>())
                    .add(edge.afterRegionId());
        }
        requireAcyclic(regionById.keySet(), precedenceGraph, "PRECEDENCE_CYCLE");

        var repeatedGroupIds = new HashSet<String>();
        var repeatItemIds = new HashSet<String>();
        var repeatMemberships = new HashSet<String>();
        for (var repeat : repeats) {
            var group = regionById.get(repeat.groupRegionId());
            if (group == null || group.kind() != RegionKind.REPEATED_GROUP
                    || !repeatedGroupIds.add(repeat.groupRegionId())) {
                throw invalid("REPEAT_GROUP_CLOSURE_INVALID");
            }
            for (var item : repeat.items()) {
                var itemRegion = regionById.get(item.itemRegionId());
                if (itemRegion == null || itemRegion.kind() != RegionKind.ITEM
                        || !repeatItemIds.add(item.itemRegionId())
                        || !group.geometry().bounds().contains(itemRegion.geometry().bounds())) {
                    throw invalid("REPEAT_ITEM_CLOSURE_INVALID");
                }
                for (var memberId : item.memberRegionIds()) {
                    var member = regionById.get(memberId);
                    if (member == null || member.kind() == RegionKind.ITEM
                            || !itemRegion.geometry().bounds().contains(member.geometry().bounds())
                            || !repeatMemberships.add(repeat.groupRegionId() + ">" + item.itemRegionId()
                            + ">" + memberId)) {
                        throw invalid("REPEAT_MEMBERSHIP_CLOSURE_INVALID");
                    }
                }
            }
        }

        for (var entity : entities) requireMembers(entity.supportingRegionIds(), regionById,
                "ENTITY_SUPPORT_DANGLING");
        var entityGraph = new HashMap<String, List<String>>();
        var semanticEdgeKeys = new HashSet<String>();
        for (var relationship : relationships) {
            if (!entityById.containsKey(relationship.parentEntityId())
                    || !entityById.containsKey(relationship.childEntityId())) {
                throw invalid("RELATIONSHIP_ENTITY_DANGLING");
            }
            requireMembers(relationship.supportingRegionIds(), regionById, "RELATIONSHIP_SUPPORT_DANGLING");
            if (!semanticEdgeKeys.add(relationship.parentEntityId() + ">" + relationship.childEntityId())) {
                throw invalid("DUPLICATE_ENTITY_EDGE");
            }
            entityGraph.computeIfAbsent(relationship.parentEntityId(), ignored -> new ArrayList<>())
                    .add(relationship.childEntityId());
        }
        requireAcyclic(entityById.keySet(), entityGraph, "ENTITY_GRAPH_CYCLE");

        var bindingPairs = new HashSet<String>();
        for (var binding : bindings) {
            if (!regionById.containsKey(binding.regionId()) || !entityById.containsKey(binding.entityId())
                    || !bindingPairs.add(binding.regionId() + ">" + binding.entityId() + ">" + binding.fieldKey())) {
                throw invalid("BINDING_CLOSURE_INVALID");
            }
        }
        if (!entityById.containsKey(candidate.rootEntityId())) throw invalid("CANDIDATE_ROOT_DANGLING");
        requireMembers(candidate.relationshipIds(), relationshipById, "CANDIDATE_RELATIONSHIP_DANGLING");
        for (var field : candidate.fields()) {
            var binding = bindingById.get(field.bindingId());
            if (!entityById.containsKey(field.entityId()) || binding == null
                    || !binding.entityId().equals(field.entityId()) || !binding.fieldKey().equals(field.fieldKey())) {
                throw invalid("CANDIDATE_FIELD_CLOSURE_INVALID");
            }
        }
        for (var ownerId : abstention.expectedUnresolvedOwnerIds()) {
            var field = candidateFieldById.get(ownerId);
            if (field == null || field.valueKind() != ValueKind.UNRESOLVED) {
                throw invalid("ABSTENTION_OWNER_INVALID");
            }
        }
    }

    private static <T> Map<String, T> index(List<T> items, Function<T, String> id, String code) {
        var result = new LinkedHashMap<String, T>();
        for (var item : items) if (result.putIfAbsent(id.apply(item), item) != null) throw invalid(code);
        return Map.copyOf(result);
    }

    private static <T> void requireMembers(List<String> ids, Map<String, T> index, String code) {
        if (ids.stream().anyMatch(id -> !index.containsKey(id))) throw invalid(code);
    }

    private static void requireAcyclic(Set<String> nodes, Map<String, List<String>> graph, String code) {
        var indegree = new HashMap<String, Integer>();
        nodes.forEach(node -> indegree.put(node, 0));
        graph.values().forEach(children -> children.forEach(child -> indegree.merge(child, 1, Integer::sum)));
        var queue = new ArrayDeque<String>();
        indegree.forEach((node, degree) -> { if (degree == 0) queue.add(node); });
        var visited = 0;
        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            visited++;
            for (var child : graph.getOrDefault(current, List.of())) {
                var remaining = indegree.merge(child, -1, Integer::sum);
                if (remaining == 0) queue.addLast(child);
            }
        }
        if (visited != nodes.size()) throw invalid(code);
    }

    static String requireId(String value, String code) {
        if (value == null || !ID.matcher(value).matches()) throw invalid(code);
        return value;
    }

    static String requireKey(String value, String code) {
        if (value == null || !KEY.matcher(value).matches()) throw invalid(code);
        return value;
    }

    static String requireIdentity(String value, String code) {
        if (value == null || !IDENTITY.matcher(value).matches()) throw invalid(code);
        return value;
    }

    static String requireText(String value, int maximumBytes, String code) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes
                || value.codePoints().anyMatch(point -> Character.getType(point) == Character.CONTROL)) {
            throw invalid(code);
        }
        return value;
    }

    static List<String> requireIds(List<String> values, int maximum, String code) {
        var result = immutable(values, maximum, code);
        var unique = new HashSet<String>();
        for (var value : result) if (!unique.add(requireId(value, code))) throw invalid(code);
        return result;
    }

    static <T> List<T> immutable(List<T> values, int maximum, String code) {
        var result = List.copyOf(Objects.requireNonNull(values, code));
        if (result.size() > maximum) throw invalid(code);
        return result;
    }

    static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
