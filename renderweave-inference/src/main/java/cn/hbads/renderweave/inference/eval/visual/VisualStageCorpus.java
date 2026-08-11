package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.schema.identity.FieldKey;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Versioned IMAGE_ONLY stage-gold corpus. Twelve semantic scenes are expanded into five
 * deterministic visual variants without duplicating or weakening their gold graph.
 */
public final class VisualStageCorpus {
    public static final String VERSION = "renderweave-visual-stage-corpus/1.0";
    private static final String RESOURCE = "visual-eval/v1/scenes.json";
    private static final int EXPECTED_SCENE_COUNT = 12;
    private static final int VARIANTS_PER_SCENE = 5;
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    private final List<Scene> scenes;
    private final List<EvaluationCase> cases;
    private final Map<String, EvaluationCase> byCaseId;
    private final String sourceSha256;

    public VisualStageCorpus() {
        this(VisualStageCorpus.class.getClassLoader());
    }

    VisualStageCorpus(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        try (var input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing visual stage corpus resource");
            var bytes = input.readAllBytes();
            var document = JSON.readValue(bytes, Document.class);
            if (!VERSION.equals(document.corpusVersion()) || document.scenes() == null
                    || document.scenes().size() != EXPECTED_SCENE_COUNT) {
                throw new IllegalStateException("Visual stage corpus version or scene count is invalid");
            }
            scenes = validateScenes(document.scenes());
            cases = expandCases(scenes);
            byCaseId = indexCases(cases);
            sourceSha256 = sha256(bytes);
            validateCoverage(cases);
        } catch (IOException failure) {
            throw new IllegalStateException("Visual stage corpus cannot be loaded", failure);
        }
    }

    public List<Scene> scenes() {
        return scenes;
    }

    public List<EvaluationCase> cases() {
        return cases;
    }

    public EvaluationCase require(String caseId) {
        var result = byCaseId.get(caseId);
        if (result == null) throw new IllegalArgumentException("Unknown visual stage case: " + caseId);
        return result;
    }

    public String sourceSha256() {
        return sourceSha256;
    }

    private static List<Scene> validateScenes(List<Scene> source) {
        var result = new ArrayList<Scene>();
        var ids = new HashSet<String>();
        for (var scene : source) {
            if (!ids.add(scene.sceneId())) {
                throw new IllegalStateException("Duplicate visual scene " + scene.sceneId());
            }
            result.add(scene);
        }
        return List.copyOf(result);
    }

    private static List<EvaluationCase> expandCases(List<Scene> scenes) {
        var result = new ArrayList<EvaluationCase>();
        for (var sceneIndex = 0; sceneIndex < scenes.size(); sceneIndex++) {
            var scene = scenes.get(sceneIndex);
            for (var variant = 1; variant <= VARIANTS_PER_SCENE; variant++) {
                var holdout = variant == 5 || variant == 4 && sceneIndex < 3;
                result.add(EvaluationCase.from(scene, variant,
                        holdout ? Partition.HOLDOUT : Partition.DEV));
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, EvaluationCase> indexCases(List<EvaluationCase> source) {
        var result = new LinkedHashMap<String, EvaluationCase>();
        for (var item : source) {
            if (result.putIfAbsent(item.caseId(), item) != null) {
                throw new IllegalStateException("Duplicate visual stage case " + item.caseId());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static void validateCoverage(List<EvaluationCase> cases) {
        if (cases.size() != 60 || cases.stream().filter(item -> item.partition() == Partition.DEV).count() != 45
                || cases.stream().filter(item -> item.partition() == Partition.HOLDOUT).count() != 15) {
            throw new IllegalStateException("Visual stage corpus must contain 45 DEV and 15 HOLDOUT cases");
        }
        for (var style : Style.values()) {
            if (cases.stream().filter(item -> item.style() == style).count() != EXPECTED_SCENE_COUNT) {
                throw new IllegalStateException("Every visual style must cover all scenes");
            }
        }
        if (cases.stream().filter(item -> item.scene().domainPack() == DomainPack.TRANSIT_BOARD).count() < 5) {
            throw new IllegalStateException("Transit-board coverage is required");
        }
        if (cases.stream().noneMatch(item -> item.scene().maximumDepth() >= 3)) {
            throw new IllegalStateException("At least one three-level hierarchy is required");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requireId(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String requireText(String value, String name, int maximumBytes) {
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes
                || value.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.CONTROL)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private record Document(String corpusVersion, List<Scene> scenes) { }

    public enum Partition { DEV, HOLDOUT }

    public enum DomainPack { GENERIC, TRANSIT_BOARD }

    public enum ElementKind { SLOT, GROUP }

    public enum Multiplicity { ONE, MANY }

    public enum ValueHint { TEXT, DECIMAL, DATE, TIME, BOOLEAN, UNRESOLVED }

    public enum Style { WIDE_LIGHT, PORTRAIT_DARK, COMPACT_DENSE, LOW_CONTRAST, HOLDOUT_NOISY }

    public record Box(int left, int top, int right, int bottom) {
        public Box {
            if (left < 0 || top < 0 || right > 10_000 || bottom > 10_000
                    || left >= right || top >= bottom) {
                throw new IllegalArgumentException("Visual gold box must use canonical 0..10000 coordinates");
            }
        }

        static Box from(List<Integer> values) {
            if (values == null || values.size() != 4 || values.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Visual gold box must contain four integers");
            }
            return new Box(values.get(0), values.get(1), values.get(2), values.get(3));
        }
    }

    public record Element(
            String elementId,
            ElementKind kind,
            String proposedKey,
            String displayName,
            Multiplicity multiplicity,
            ValueHint valueHint,
            String sampleValue,
            List<Integer> boundingBox
    ) {
        public Element {
            elementId = requireId(elementId, "elementId");
            Objects.requireNonNull(kind, "kind");
            FieldKey.of(proposedKey);
            displayName = requireText(displayName, "displayName", 256);
            Objects.requireNonNull(multiplicity, "multiplicity");
            if (kind == ElementKind.SLOT) {
                Objects.requireNonNull(valueHint, "valueHint");
                sampleValue = requireText(sampleValue, "sampleValue", 512);
            } else if (valueHint != null || sampleValue != null) {
                throw new IllegalArgumentException("GROUP elements cannot carry scalar value data");
            }
            boundingBox = List.copyOf(Objects.requireNonNull(boundingBox, "boundingBox"));
            Box.from(boundingBox);
        }

        public Box box() {
            return Box.from(boundingBox);
        }
    }

    public record Entity(
            String entityId,
            String schemaKey,
            String displayName,
            List<String> supportingElementIds
    ) {
        public Entity {
            entityId = requireId(entityId, "entityId");
            SchemaKey.userProvided(schemaKey);
            displayName = requireText(displayName, "displayName", 256);
            supportingElementIds = requireIds(supportingElementIds, "supportingElementIds", 32);
        }
    }

    public record Relationship(
            String relationshipId,
            String parentEntityId,
            String childEntityId,
            String fieldKey,
            String displayName,
            Multiplicity cardinality,
            List<String> supportingElementIds
    ) {
        public Relationship {
            relationshipId = requireId(relationshipId, "relationshipId");
            parentEntityId = requireId(parentEntityId, "parentEntityId");
            childEntityId = requireId(childEntityId, "childEntityId");
            FieldKey.of(fieldKey);
            displayName = requireText(displayName, "displayName", 256);
            Objects.requireNonNull(cardinality, "cardinality");
            supportingElementIds = requireIds(supportingElementIds, "supportingElementIds", 16);
        }
    }

    public record Binding(String elementId, String entityId) {
        public Binding {
            elementId = requireId(elementId, "elementId");
            entityId = requireId(entityId, "entityId");
        }
    }

    public record Scene(
            String sceneId,
            DomainPack domainPack,
            String title,
            String rootEntityId,
            List<Element> elements,
            List<Entity> entities,
            List<Relationship> relationships,
            List<Binding> bindings
    ) {
        public Scene {
            sceneId = requireId(sceneId, "sceneId");
            Objects.requireNonNull(domainPack, "domainPack");
            title = requireText(title, "title", 256);
            rootEntityId = requireId(rootEntityId, "rootEntityId");
            elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
            relationships = List.copyOf(Objects.requireNonNull(relationships, "relationships"));
            bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
            validateGraph(rootEntityId, elements, entities, relationships, bindings);
        }

        public int maximumDepth() {
            var children = new HashMap<String, List<String>>();
            relationships.forEach(edge -> children.computeIfAbsent(edge.parentEntityId(), ignored -> new ArrayList<>())
                    .add(edge.childEntityId()));
            var maximum = 1;
            var queue = new ArrayDeque<NodeDepth>();
            queue.add(new NodeDepth(rootEntityId, 1));
            while (!queue.isEmpty()) {
                var current = queue.removeFirst();
                maximum = Math.max(maximum, current.depth());
                children.getOrDefault(current.entityId(), List.of()).forEach(child ->
                        queue.addLast(new NodeDepth(child, current.depth() + 1)));
            }
            return maximum;
        }

        public Map<String, String> entityPaths() {
            var result = new HashMap<String, String>();
            result.put(rootEntityId, "/");
            var pending = new ArrayDeque<Relationship>(relationships);
            while (!pending.isEmpty()) {
                var before = pending.size();
                for (var index = 0; index < before; index++) {
                    var edge = pending.removeFirst();
                    var parentPath = result.get(edge.parentEntityId());
                    if (parentPath == null) {
                        pending.addLast(edge);
                    } else {
                        result.put(edge.childEntityId(), childPath(parentPath, edge.fieldKey()));
                    }
                }
                if (pending.size() == before) throw new IllegalStateException("Visual scene graph cannot be traversed");
            }
            return Collections.unmodifiableMap(result);
        }

        public Map<String, Map<String, String>> expectedShapes() {
            var paths = entityPaths();
            var result = new TreeMap<String, Map<String, String>>();
            entities.forEach(entity -> result.put(paths.get(entity.entityId()), new TreeMap<>()));
            var elementsById = elements.stream().collect(java.util.stream.Collectors.toMap(
                    Element::elementId, item -> item
            ));
            for (var binding : bindings) {
                var element = elementsById.get(binding.elementId());
                var shape = element.valueHint().name();
                if (element.multiplicity() == Multiplicity.MANY) shape = "ARRAY:" + shape;
                result.get(paths.get(binding.entityId())).put(element.proposedKey(), shape);
            }
            for (var edge : relationships) {
                var shape = edge.cardinality() == Multiplicity.MANY ? "ARRAY:REFERENCE" : "REFERENCE";
                result.get(paths.get(edge.parentEntityId())).put(edge.fieldKey(), shape);
            }
            var immutable = new TreeMap<String, Map<String, String>>();
            result.forEach((path, fields) -> immutable.put(path,
                    Collections.unmodifiableMap(new TreeMap<>(fields))));
            return Collections.unmodifiableMap(immutable);
        }

        public Map<String, String> bindingEntityPaths() {
            var paths = entityPaths();
            var result = new TreeMap<String, String>();
            bindings.forEach(binding -> result.put(binding.elementId(), paths.get(binding.entityId())));
            return Collections.unmodifiableMap(result);
        }

        private record NodeDepth(String entityId, int depth) { }
    }

    public record EvaluationCase(
            String caseId,
            Scene scene,
            int variantOrdinal,
            Partition partition,
            Style style,
            int width,
            int height,
            int contrastBps,
            int distractorCount,
            long noiseSeed
    ) {
        public EvaluationCase {
            caseId = requireId(caseId, "caseId");
            Objects.requireNonNull(scene, "scene");
            if (variantOrdinal < 1 || variantOrdinal > VARIANTS_PER_SCENE) {
                throw new IllegalArgumentException("variantOrdinal is invalid");
            }
            Objects.requireNonNull(partition, "partition");
            Objects.requireNonNull(style, "style");
            if (width < 768 || width > 2400 || height < 640 || height > 2400
                    || contrastBps < 2500 || contrastBps > 10_000
                    || distractorCount < 0 || distractorCount > 24) {
                throw new IllegalArgumentException("Visual case rendering parameters are invalid");
            }
        }

        static EvaluationCase from(Scene scene, int variant, Partition partition) {
            return switch (variant) {
                case 1 -> new EvaluationCase(scene.sceneId() + "-v1", scene, variant, partition,
                        Style.WIDE_LIGHT, 1600, 1000, 10_000, 2, seed(scene.sceneId(), variant));
                case 2 -> new EvaluationCase(scene.sceneId() + "-v2", scene, variant, partition,
                        Style.PORTRAIT_DARK, 1000, 1600, 9_000, 4, seed(scene.sceneId(), variant));
                case 3 -> new EvaluationCase(scene.sceneId() + "-v3", scene, variant, partition,
                        Style.COMPACT_DENSE, 1024, 768, 8_000, 8, seed(scene.sceneId(), variant));
                case 4 -> new EvaluationCase(scene.sceneId() + "-v4", scene, variant, partition,
                        Style.LOW_CONTRAST, 1400, 900, 4_200, 6, seed(scene.sceneId(), variant));
                case 5 -> new EvaluationCase(scene.sceneId() + "-v5", scene, variant, partition,
                        Style.HOLDOUT_NOISY, 1800, 1200, 7_000, 12, seed(scene.sceneId(), variant));
                default -> throw new IllegalArgumentException("Unsupported visual case variant");
            };
        }

        public Map<String, Map<String, String>> expectedShapes() {
            return scene.expectedShapes();
        }

        private static long seed(String sceneId, int variant) {
            var value = sceneId + "#" + variant + "#" + VERSION;
            var bytes = value.getBytes(StandardCharsets.UTF_8);
            long result = 0xcbf29ce484222325L;
            for (var item : bytes) {
                result ^= item & 0xffL;
                result *= 0x100000001b3L;
            }
            return result;
        }
    }

    private static List<String> requireIds(List<String> values, String name, int maximum) {
        values = List.copyOf(Objects.requireNonNull(values, name));
        if (values.isEmpty() || values.size() > maximum) {
            throw new IllegalArgumentException(name + " count is invalid");
        }
        var unique = new HashSet<String>();
        for (var value : values) {
            requireId(value, name);
            if (!unique.add(value)) throw new IllegalArgumentException(name + " must be unique");
        }
        return values;
    }

    private static void validateGraph(
            String rootEntityId,
            List<Element> elements,
            List<Entity> entities,
            List<Relationship> relationships,
            List<Binding> bindings
    ) {
        if (elements.isEmpty() || elements.size() > 128 || entities.isEmpty() || entities.size() > 32
                || relationships.size() > 31 || bindings.isEmpty() || bindings.size() > 128) {
            throw new IllegalArgumentException("Visual scene graph size is invalid");
        }
        var elementsById = unique(elements, Element::elementId, "element");
        var entitiesById = unique(entities, Entity::entityId, "entity");
        if (!entitiesById.containsKey(rootEntityId)) throw new IllegalArgumentException("Visual scene root is missing");
        var relationshipIds = new HashSet<String>();
        var incoming = new HashMap<String, Integer>();
        var outgoing = new HashMap<String, List<String>>();
        var entityFields = new HashSet<String>();
        var usedRelationshipGroups = new HashSet<String>();
        for (var edge : relationships) {
            if (!relationshipIds.add(edge.relationshipId()) || !entitiesById.containsKey(edge.parentEntityId())
                    || !entitiesById.containsKey(edge.childEntityId())
                    || edge.parentEntityId().equals(edge.childEntityId())
                    || !entityFields.add(edge.parentEntityId() + "\0" + edge.fieldKey())) {
                throw new IllegalArgumentException("Visual scene relationship is invalid");
            }
            incoming.merge(edge.childEntityId(), 1, Integer::sum);
            outgoing.computeIfAbsent(edge.parentEntityId(), ignored -> new ArrayList<>()).add(edge.childEntityId());
            var cardinalitySupported = false;
            for (var elementId : edge.supportingElementIds()) {
                var element = elementsById.get(elementId);
                if (element == null || element.kind() != ElementKind.GROUP
                        || !usedRelationshipGroups.add(elementId)) {
                    throw new IllegalArgumentException("Visual relationship must use a unique GROUP element");
                }
                if (element.multiplicity() == edge.cardinality()) cardinalitySupported = true;
            }
            if (!cardinalitySupported) throw new IllegalArgumentException("Visual relationship cardinality is ungrounded");
        }
        if (incoming.getOrDefault(rootEntityId, 0) != 0) {
            throw new IllegalArgumentException("Visual scene root cannot have a parent");
        }
        for (var entity : entities) {
            if (!entity.entityId().equals(rootEntityId) && incoming.getOrDefault(entity.entityId(), 0) != 1) {
                throw new IllegalArgumentException("Every non-root visual entity needs exactly one parent");
            }
            if (entity.supportingElementIds().stream().anyMatch(id -> !elementsById.containsKey(id))) {
                throw new IllegalArgumentException("Visual entity support is unknown");
            }
        }
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(rootEntityId);
        while (!queue.isEmpty()) {
            var current = queue.removeFirst();
            if (!visited.add(current)) throw new IllegalArgumentException("Visual scene contains a cycle");
            queue.addAll(outgoing.getOrDefault(current, List.of()));
        }
        if (visited.size() != entities.size()) throw new IllegalArgumentException("Visual scene contains an orphan");

        var boundSlots = new HashSet<String>();
        for (var binding : bindings) {
            var element = elementsById.get(binding.elementId());
            if (element == null || element.kind() != ElementKind.SLOT || !entitiesById.containsKey(binding.entityId())
                    || !boundSlots.add(binding.elementId())
                    || !entityFields.add(binding.entityId() + "\0" + element.proposedKey())) {
                throw new IllegalArgumentException("Visual scene binding is invalid");
            }
        }
        var slots = elements.stream().filter(item -> item.kind() == ElementKind.SLOT)
                .map(Element::elementId).collect(java.util.stream.Collectors.toSet());
        if (!boundSlots.equals(slots)) throw new IllegalArgumentException("Every SLOT must be bound exactly once");
    }

    private static <T> Map<String, T> unique(
            List<T> values,
            java.util.function.Function<T, String> identity,
            String name
    ) {
        var result = new HashMap<String, T>();
        for (var value : values) {
            if (result.putIfAbsent(identity.apply(value), value) != null) {
                throw new IllegalArgumentException("Duplicate visual " + name);
            }
        }
        return result;
    }

    private static String childPath(String parentPath, String fieldKey) {
        var escaped = fieldKey.replace("~", "~0").replace("/", "~1");
        return "/".equals(parentPath) ? "/" + escaped : parentPath + "/" + escaped;
    }
}
