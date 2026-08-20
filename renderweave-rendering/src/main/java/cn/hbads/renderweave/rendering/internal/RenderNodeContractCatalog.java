package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ArrayNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Bool;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.DesignNodeValue;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.NumberToken;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.ObjectNode;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rendering-owned machine authority for the exact DesignDSL-to-RenderDSL lowering edge.
 *
 * <p>The catalog owns every materializable default. Author-written members always win and a
 * materialized default never becomes a Binding target because this expansion happens only after
 * Binding overlay. Semantic absence (for example no fill, stroke, maxLines, or min/max clamp)
 * remains member omission; JSON {@code null} is never manufactured.
 */
final class RenderNodeContractCatalog {

    static final String CATALOG_VERSION = "renderweave-render-node-contract-v1/2";
    static final String RENDER_DSL_VERSION = "renderweave-render/1.0";
    private static final String RESOURCE =
            "/cn/hbads/renderweave/rendering/render-node-contract-v1.json";
    private static final RenderNodeContractCatalog INSTANCE = load();

    private final ObjectNode commonNodeDefaults;
    private final Map<String, ObjectNode> objectDefaults;
    private final Map<String, ObjectNode> placementDefaults;
    private final Map<String, KindContract> kinds;
    private final Map<String, String> resourceLowering;

    private RenderNodeContractCatalog(
            ObjectNode commonNodeDefaults,
            Map<String, ObjectNode> objectDefaults,
            Map<String, ObjectNode> placementDefaults,
            Map<String, KindContract> kinds,
            Map<String, String> resourceLowering) {
        this.commonNodeDefaults = commonNodeDefaults;
        this.objectDefaults = Map.copyOf(objectDefaults);
        this.placementDefaults = Map.copyOf(placementDefaults);
        this.kinds = Map.copyOf(kinds);
        this.resourceLowering = Map.copyOf(resourceLowering);
    }

    static RenderNodeContractCatalog instance() {
        return INSTANCE;
    }

    ObjectNode expandNodeDefaults(String kind, ObjectNode authored) {
        var contract = kinds.get(kind);
        if (contract == null) {
            throw new IllegalStateException("unknown RenderDSL node kind");
        }
        var expanded = new LinkedHashMap<String, DesignNodeValue>();
        if (!"canvas".equals(kind)) {
            expanded.putAll(commonNodeDefaults.members());
        }
        expanded.putAll(contract.defaults().members());
        for (var objectName : contract.defaultObjects()) {
            var defaultObject = objectDefaults.get(objectName);
            if (defaultObject == null) {
                throw new IllegalStateException("unknown RenderDSL default object");
            }
            expanded.put(objectName, defaultObject);
        }
        expanded.putAll(authored.members());
        return new ObjectNode(expanded);
    }

    ObjectNode expandPlacementDefaults(
            ObjectNode authored,
            String parentKind,
            ObjectNode expandedParent) {
        var type = text(authored, "type");
        if (type == null || "PACK".equals(type)) {
            throw new IllegalStateException("RenderDSL placement must be ABSOLUTE, STACK, or GRID");
        }
        var defaults = placementDefaults.get(type);
        if (defaults == null) {
            throw new IllegalStateException("unknown RenderDSL placement type");
        }
        var expanded = new LinkedHashMap<String, DesignNodeValue>(defaults.members());
        if ("ABSOLUTE".equals(type)) {
            if ("FILL".equals(text(authored, "widthMode"))) {
                expanded.put("rightInsetMm", new NumberToken("0"));
            }
            if ("FILL".equals(text(authored, "heightMode"))) {
                expanded.put("bottomInsetMm", new NumberToken("0"));
            }
        } else if ("STACK".equals(type)) {
            if (!"stack".equals(parentKind)) {
                throw new IllegalStateException("STACK placement requires a Stack parent");
            }
            var parentAlignment = text(expandedParent, "alignItems");
            expanded.put("alignSelf", new Text(
                    parentAlignment == null ? "START" : parentAlignment));
            var direction = text(expandedParent, "direction");
            var mainModeMember = "ROW".equals(direction) ? "widthMode" : "heightMode";
            if ("FILL".equals(text(authored, mainModeMember))) {
                expanded.put("fillWeight", new NumberToken("1"));
            }
        } else if (!"grid".equals(parentKind)) {
            throw new IllegalStateException("GRID placement requires a Grid parent");
        }
        expanded.putAll(authored.members());
        return new ObjectNode(expanded);
    }

    String loweredResourceMember(String authoredMember) {
        return resourceLowering.get(authoredMember);
    }

    boolean isContainer(String kind) {
        var contract = kinds.get(kind);
        if (contract == null) {
            throw new IllegalStateException("unknown RenderDSL node kind");
        }
        return contract.container();
    }

    private static RenderNodeContractCatalog load() {
        try (var input = RenderNodeContractCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("RenderNodeContract catalog resource is absent");
            }
            var bytes = input.readAllBytes();
            var budget = new RenderJsonParser.JsonBudget(
                    "renderNodeContract", 1024 * 1024, 16, 128, 128,
                    4096, 64 * 1024, 128);
            var result = RenderJsonParser.parse(bytes, budget);
            if (!(result instanceof RenderJsonParser.Parsed parsed)
                    || !(parsed.value() instanceof RenderJson.ObjectValue root)) {
                throw new IllegalStateException("RenderNodeContract catalog is not strict JSON");
            }
            requireText(root, "catalogVersion", CATALOG_VERSION);
            requireText(root, "renderDslVersion", RENDER_DSL_VERSION);
            var common = designObject(requireObject(root, "commonNodeDefaults"));
            var objectDefaults = objectMap(requireObject(root, "objectDefaults"));
            var placementDefaults = objectMap(requireObject(root, "placementDefaults"));
            var resourceLowering = stringMap(requireObject(root, "resourceLowering"));
            var kinds = new LinkedHashMap<String, KindContract>();
            for (var entry : requireObject(root, "kinds").members().entrySet()) {
                if (!(entry.getValue() instanceof RenderJson.ObjectValue kind)) {
                    throw new IllegalStateException("RenderNodeContract kind must be an object");
                }
                var container = requireBoolean(kind, "container");
                var defaults = designObject(requireObject(kind, "defaults"));
                var defaultObjects = stringList(requireArray(kind, "defaultObjects"));
                kinds.put(entry.getKey(), new KindContract(
                        container, defaults, defaultObjects));
            }
            if (kinds.size() != 16 || !kinds.containsKey("canvas")
                    || !kinds.containsKey("compositionViewport")) {
                throw new IllegalStateException("RenderNodeContract kind inventory drifted");
            }
            return new RenderNodeContractCatalog(
                    common, objectDefaults, placementDefaults, kinds, resourceLowering);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Map<String, ObjectNode> objectMap(RenderJson.ObjectValue value) {
        var output = new LinkedHashMap<String, ObjectNode>();
        for (var entry : value.members().entrySet()) {
            if (!(entry.getValue() instanceof RenderJson.ObjectValue object)) {
                throw new IllegalStateException("RenderNodeContract object map value is not an object");
            }
            output.put(entry.getKey(), designObject(object));
        }
        return output;
    }

    private static Map<String, String> stringMap(RenderJson.ObjectValue value) {
        var output = new LinkedHashMap<String, String>();
        for (var entry : value.members().entrySet()) {
            if (!(entry.getValue() instanceof RenderJson.StringValue text)) {
                throw new IllegalStateException("RenderNodeContract string map value is not text");
            }
            output.put(entry.getKey(), text.value());
        }
        return output;
    }

    private static List<String> stringList(RenderJson.ArrayValue value) {
        var output = new ArrayList<String>(value.items().size());
        for (var item : value.items()) {
            if (!(item instanceof RenderJson.StringValue text)) {
                throw new IllegalStateException("RenderNodeContract string list value is not text");
            }
            output.add(text.value());
        }
        return List.copyOf(output);
    }

    private static ObjectNode designObject(RenderJson.ObjectValue value) {
        var output = new LinkedHashMap<String, DesignNodeValue>();
        for (var entry : value.members().entrySet()) {
            output.put(entry.getKey(), designValue(entry.getValue()));
        }
        return new ObjectNode(output);
    }

    private static DesignNodeValue designValue(RenderJson value) {
        if (value instanceof RenderJson.StringValue text) {
            return new Text(text.value());
        }
        if (value instanceof RenderJson.NumberValue number) {
            return new NumberToken(number.rawToken());
        }
        if (value instanceof RenderJson.BooleanValue bool) {
            return new Bool(bool.value());
        }
        if (value instanceof RenderJson.ObjectValue object) {
            return designObject(object);
        }
        if (value instanceof RenderJson.ArrayValue array) {
            var output = new ArrayList<DesignNodeValue>(array.items().size());
            for (var item : array.items()) {
                output.add(designValue(item));
            }
            return new ArrayNode(output);
        }
        throw new IllegalStateException("RenderNodeContract default cannot be null");
    }

    private static RenderJson.ObjectValue requireObject(
            RenderJson.ObjectValue parent, String member) {
        if (!(parent.members().get(member) instanceof RenderJson.ObjectValue object)) {
            throw new IllegalStateException("RenderNodeContract object member is absent");
        }
        return object;
    }

    private static RenderJson.ArrayValue requireArray(
            RenderJson.ObjectValue parent, String member) {
        if (!(parent.members().get(member) instanceof RenderJson.ArrayValue array)) {
            throw new IllegalStateException("RenderNodeContract array member is absent");
        }
        return array;
    }

    private static void requireText(
            RenderJson.ObjectValue parent, String member, String expected) {
        if (!(parent.members().get(member) instanceof RenderJson.StringValue text)
                || !expected.equals(text.value())) {
            throw new IllegalStateException("RenderNodeContract identity drifted");
        }
    }

    private static boolean requireBoolean(RenderJson.ObjectValue parent, String member) {
        if (!(parent.members().get(member) instanceof RenderJson.BooleanValue value)) {
            throw new IllegalStateException("RenderNodeContract boolean member is absent");
        }
        return value.value();
    }

    private static String text(ObjectNode object, String member) {
        return object.members().get(member) instanceof Text text ? text.value() : null;
    }

    private record KindContract(
            boolean container,
            ObjectNode defaults,
            List<String> defaultObjects) {
        private KindContract {
            Objects.requireNonNull(defaults, "defaults");
            defaultObjects = List.copyOf(defaultObjects);
        }
    }
}
