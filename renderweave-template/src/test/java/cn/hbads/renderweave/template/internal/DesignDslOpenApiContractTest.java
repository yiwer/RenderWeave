package cn.hbads.renderweave.template.internal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the public OpenAPI authoring projection to the Template-owned DesignDSL authorities.
 * The YAML is a projection, never a second validator: semantic type inference and binding
 * admission continue to execute only in the Java authority.
 */
class DesignDslOpenApiContractTest {

    private static Map<String, Object> schemas;

    @BeforeAll
    static void loadOpenApi() throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(8 * 1024 * 1024);
        var yaml = new Yaml(new SafeConstructor(options));
        try (Reader reader = Files.newBufferedReader(repositoryFile("openapi/renderweave-v1.yaml"))) {
            var document = map(yaml.load(reader), "document");
            schemas = map(map(document.get("components"), "components").get("schemas"),
                    "components.schemas");
        }
    }

    @Test
    void projectsEveryAdmittedNodeKindAndExactTopLevelPropertySet() {
        assertEquals(NodeContractCatalog.DESIGN_ROOT_MEMBERS,
                properties(schema("DesignDslKernel")).keySet());
        assertClosedNode("canvas", schema("DesignCanvasNode"),
                NodeContractCatalog.NodeKind.CANVAS);

        var nodeUnion = schema("DesignNode");
        var discriminator = map(nodeUnion.get("discriminator"), "DesignNode.discriminator");
        var mapping = stringMap(discriminator.get("mapping"), "DesignNode.discriminator.mapping");
        var expectedKinds = new HashSet<>(NodeContractCatalog.KIND_BY_NAME.keySet());
        expectedKinds.remove("canvas");
        assertEquals(expectedKinds, mapping.keySet());

        var oneOfRefs = list(nodeUnion.get("oneOf"), "DesignNode.oneOf").stream()
                .map(entry -> string(map(entry, "DesignNode.oneOf entry").get("$ref"), "$ref"))
                .collect(Collectors.toSet());
        assertEquals(new HashSet<>(mapping.values()), oneOfRefs);

        for (var entry : mapping.entrySet()) {
            var kind = NodeContractCatalog.KIND_BY_NAME.get(entry.getKey());
            assertNotNull(kind, "OpenAPI invented node kind " + entry.getKey());
            assertClosedNode(entry.getKey(), schema(refName(entry.getValue())), kind);
        }
    }

    @Test
    void projectsBindingPolicyWithoutOmissionsOrInventedTargets() {
        var projection = map(schema("DesignNode").get("x-renderweave-binding-policy"),
                "DesignNode.x-renderweave-binding-policy");
        var commonNonCanvas = stringSet(projection.get("commonNonCanvas"), "commonNonCanvas");
        var commonNonGroup = stringSet(projection.get("commonNonGroup"), "commonNonGroup");
        var byKind = map(projection.get("byKind"), "byKind");
        assertEquals(NodeContractCatalog.KIND_BY_NAME.keySet(), byKind.keySet());

        for (var kind : NodeContractCatalog.KIND_BY_NAME.keySet()) {
            var projected = new HashSet<>(stringSet(byKind.get(kind), "byKind." + kind));
            if (!"canvas".equals(kind)) {
                projected.addAll(commonNonCanvas);
                if (!"group".equals(kind)) {
                    projected.addAll(commonNonGroup);
                }
            }
            var expected = BindingPolicyCatalog.ENTRIES.stream()
                    .filter(entry -> kind.equals(entry.nodeKind()))
                    .map(BindingPolicyCatalog.Entry::propertyPathPattern)
                    .collect(Collectors.toSet());
            assertEquals(expected, projected, "binding targets for " + kind);
        }
    }

    @Test
    void projectsNestedNodePropertyIdentitiesFromTheCatalog() {
        assertClosedProperties("DesignBleed", NodeContractCatalog.BLEED_MEMBERS);
        assertClosedProperties("DesignTransform", NodeContractCatalog.TRANSFORM_MEMBERS);
        assertClosedProperties("DesignFill", NodeContractCatalog.FILL_MEMBERS);
        assertClosedProperties("DesignStrokeMm", NodeContractCatalog.STROKE_MM_MEMBERS);
        assertClosedProperties("DesignStrokePt", NodeContractCatalog.STROKE_PT_MEMBERS);
        assertClosedProperties("DesignPadding", NodeContractCatalog.PADDING_MEMBERS);
        assertClosedProperties("DesignCornerRadii", NodeContractCatalog.CORNER_RADII_MEMBERS);
        assertClosedProperties("DesignPointMm", NodeContractCatalog.POINT_MM_MEMBERS);
        assertClosedProperties("DesignAbsolutePlacement",
                NodeContractCatalog.ABSOLUTE_PLACEMENT_MEMBERS);
        assertClosedProperties("DesignStackPlacement",
                NodeContractCatalog.STACK_PLACEMENT_MEMBERS);
        assertClosedProperties("DesignGridPlacement",
                NodeContractCatalog.GRID_PLACEMENT_MEMBERS);
        assertClosedProperties("DesignPackPlacement",
                NodeContractCatalog.PACK_PLACEMENT_MEMBERS);
        assertClosedProperties("DesignTextRun", NodeContractCatalog.RUN_MEMBERS);
        assertUnionProperties("DesignLineHeight", NodeContractCatalog.LINE_HEIGHT_MEMBERS);
        assertClosedProperties("DesignMoveToCommand",
                NodeContractCatalog.MOVE_TO_COMMAND_MEMBERS);
        assertClosedProperties("DesignLineToCommand",
                NodeContractCatalog.LINE_TO_COMMAND_MEMBERS);
        assertClosedProperties("DesignQuadToCommand",
                NodeContractCatalog.QUAD_TO_COMMAND_MEMBERS);
        assertClosedProperties("DesignCubicToCommand",
                NodeContractCatalog.CUBIC_TO_COMMAND_MEMBERS);
        assertClosedProperties("DesignCloseCommand",
                NodeContractCatalog.CLOSE_COMMAND_MEMBERS);
        assertClosedProperties("DesignBinding", NodeContractCatalog.BINDING_MEMBERS);
        assertClosedProperties("DesignTargetPropertyRef",
                NodeContractCatalog.TARGET_PROPERTY_REF_MEMBERS);
        assertClosedProperties("DesignMemberSelector",
                NodeContractCatalog.MEMBER_SELECTOR_MEMBERS);
        assertClosedProperties("DesignIndexSelector",
                NodeContractCatalog.INDEX_SELECTOR_MEMBERS);
        assertClosedProperties("DesignTemplateRef", NodeContractCatalog.TEMPLATE_REF_MEMBERS);
        assertClosedProperties("DesignContextTemplateSelector",
                NodeContractCatalog.CONTEXT_SELECTOR_MEMBERS);
        assertClosedProperties("DesignEmptyTemplateSelector",
                NodeContractCatalog.EMPTY_SELECTOR_MEMBERS);
        assertClosedProperties("DesignTemplateUseFill", NodeContractCatalog.USE_FILL_MEMBERS);
        assertClosedProperties("DesignStackPackingSpec",
                NodeContractCatalog.STACK_PACKING_SPEC_MEMBERS);
        assertClosedProperties("DesignGridPackingSpec",
                NodeContractCatalog.GRID_PACKING_SPEC_MEMBERS);
    }

    @Test
    void projectsDefinitionAndValueSourceMemberShapesFromTheCatalog() {
        assertClosedProperties("DesignListValueType",
                DefinitionContractCatalog.LIST_VALUE_TYPE_MEMBERS);
        assertClosedProperties("DesignEnumValueType",
                DefinitionContractCatalog.ENUM_VALUE_TYPE_MEMBERS);
        assertClosedProperties("DesignCustomDefinition", union(
                DefinitionContractCatalog.COMMON_DEFINITION_MEMBERS,
                DefinitionContractCatalog.CUSTOM_MEMBERS));
        assertClosedProperties("DesignMappingDefinition", union(
                DefinitionContractCatalog.COMMON_DEFINITION_MEMBERS,
                DefinitionContractCatalog.MAPPING_MEMBERS));
        assertClosedProperties("DesignExpressionDefinition", union(
                DefinitionContractCatalog.COMMON_DEFINITION_MEMBERS,
                DefinitionContractCatalog.EXPRESSION_MEMBERS));
        assertClosedProperties("DesignLiteralSource",
                DefinitionContractCatalog.LITERAL_SOURCE_MEMBERS);
        assertClosedProperties("DesignContextSource",
                DefinitionContractCatalog.CONTEXT_SOURCE_MEMBERS);
        assertClosedProperties("DesignLoopIndexSource",
                DefinitionContractCatalog.LOOP_INDEX_SOURCE_MEMBERS);
        assertClosedProperties("DesignDefinitionSource",
                DefinitionContractCatalog.DEFINITION_SOURCE_MEMBERS);
        assertClosedProperties("DesignCapabilitySource",
                DefinitionContractCatalog.CAPABILITY_SOURCE_MEMBERS);
        assertClosedProperties("DesignMappingCase", DefinitionContractCatalog.CASE_MEMBERS);
        assertClosedProperties("DesignMappingOperand",
                DefinitionContractCatalog.OPERAND_MEMBERS);
        assertClosedProperties("DesignExpressionInput",
                DefinitionContractCatalog.EXPRESSION_INPUT_MEMBERS);
        assertClosedProperties("DesignLoopDomain", DefinitionContractCatalog.DOMAIN_LOOP_MEMBERS);
        assertClosedProperties("DesignAssetRef", DefinitionContractCatalog.ASSET_REF_MEMBERS);
        assertClosedProperties("DesignInvocationSelectorDomain",
                NodeContractCatalog.INVOCATION_SELECTOR_DOMAIN_MEMBERS);
        assertClosedProperties("DesignLoopSelectorDomain",
                NodeContractCatalog.LOOP_SELECTOR_DOMAIN_MEMBERS);
    }

    @Test
    void projectsClosedDefinitionsValueSourcesAndPlacements() {
        assertDiscriminatedUnion("DesignDefinition", "kind",
                DefinitionContractCatalog.DEFINITION_KINDS);
        assertDiscriminatedUnion("DesignValueSource", "kind",
                DefinitionContractCatalog.VALUE_SOURCE_KINDS);
        assertDiscriminatedUnion("DesignPlacement", "type",
                Set.of("ABSOLUTE", "STACK", "GRID", "PACK"));

        for (var kind : NodeContractCatalog.KIND_BY_NAME.keySet()) {
            var nodeSchema = "canvas".equals(kind)
                    ? schema("DesignCanvasNode")
                    : schema(refName(stringMap(
                    map(schema("DesignNode").get("discriminator"), "discriminator")
                            .get("mapping"), "mapping").get(kind)));
            var bindingItems = map(map(properties(nodeSchema).get("bindings"), "bindings")
                    .get("items"), "bindings.items");
            assertEquals("#/components/schemas/DesignBinding", bindingItems.get("$ref"));
        }
        assertAllSchemaReferencesResolve(schemas);
    }

    @Test
    void keepsEveryDesignEnumAndConstTokenAsAJsonString() {
        schemas.forEach((name, schema) -> {
            if (name.startsWith("Design")) {
                assertStringTokens(schema, "components.schemas." + name);
            }
        });
    }

    private static void assertClosedNode(
            String wireKind,
            Map<String, Object> nodeSchema,
            NodeContractCatalog.NodeKind kind
    ) {
        assertEquals(Boolean.FALSE, nodeSchema.get("additionalProperties"), wireKind);
        var properties = properties(nodeSchema);
        assertEquals(NodeContractCatalog.allowedMembers(kind), properties.keySet(), wireKind);
        assertEquals(wireKind, map(properties.get("kind"), wireKind + ".kind").get("const"));
        assertTrue(list(nodeSchema.get("required"), wireKind + ".required")
                .containsAll(List.of("nodeId", "kind", "bindings")), wireKind);
    }

    private static void assertClosedProperties(String schemaName, Set<String> expected) {
        var projected = schema(schemaName);
        assertEquals(Boolean.FALSE, projected.get("additionalProperties"), schemaName);
        assertEquals(expected, properties(projected).keySet(), schemaName);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        var result = new HashSet<>(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    private static void assertUnionProperties(String schemaName, Set<String> expected) {
        var union = schema(schemaName);
        var projected = list(union.get("oneOf"), schemaName + ".oneOf").stream()
                .map(entry -> string(map(entry, schemaName + " variant").get("$ref"), "$ref"))
                .map(DesignDslOpenApiContractTest::refName)
                .map(DesignDslOpenApiContractTest::schema)
                .map(DesignDslOpenApiContractTest::properties)
                .flatMap(properties -> properties.keySet().stream())
                .collect(Collectors.toSet());
        assertEquals(expected, projected, schemaName);
    }

    private static void assertDiscriminatedUnion(
            String schemaName,
            String propertyName,
            Set<String> expectedTokens
    ) {
        var union = schema(schemaName);
        var discriminator = map(union.get("discriminator"), schemaName + ".discriminator");
        assertEquals(propertyName, discriminator.get("propertyName"));
        var mapping = stringMap(discriminator.get("mapping"), schemaName + ".mapping");
        assertEquals(expectedTokens, mapping.keySet());
        var refs = list(union.get("oneOf"), schemaName + ".oneOf").stream()
                .map(entry -> string(map(entry, schemaName + " variant").get("$ref"), "$ref"))
                .collect(Collectors.toSet());
        assertEquals(new HashSet<>(mapping.values()), refs);
        for (var entry : mapping.entrySet()) {
            var variant = schema(refName(entry.getValue()));
            assertEquals(Boolean.FALSE, variant.get("additionalProperties"), entry.getKey());
            assertEquals(entry.getKey(), map(properties(variant).get(propertyName),
                    schemaName + "." + entry.getKey()).get("const"));
        }
    }

    private static void assertAllSchemaReferencesResolve(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if ("$ref".equals(entry.getKey()) && entry.getValue() instanceof String ref
                        && ref.startsWith("#/components/schemas/")) {
                    assertTrue(schemas.containsKey(refName(ref)), "missing schema " + ref);
                } else {
                    assertAllSchemaReferencesResolve(entry.getValue());
                }
            }
        } else if (value instanceof List<?> list) {
            list.forEach(DesignDslOpenApiContractTest::assertAllSchemaReferencesResolve);
        }
    }

    private static void assertStringTokens(Object value, String location) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                var childLocation = location + "." + entry.getKey();
                if ("const".equals(entry.getKey())) {
                    assertTrue(entry.getValue() instanceof String,
                            childLocation + " must stay a JSON string token");
                } else if ("enum".equals(entry.getKey())) {
                    for (var token : list(entry.getValue(), childLocation)) {
                        assertTrue(token instanceof String,
                                childLocation + " contains a non-string token: " + token);
                    }
                } else {
                    assertStringTokens(entry.getValue(), childLocation);
                }
            }
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                assertStringTokens(list.get(index), location + "[" + index + "]");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String location) {
        assertTrue(value instanceof Map<?, ?>, location + " must be an object");
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> schema(String name) {
        return map(schemas.get(name), "components.schemas." + name);
    }

    private static Map<String, Object> properties(Map<String, Object> schema) {
        return map(schema.get("properties"), "properties");
    }

    private static List<Object> list(Object value, String location) {
        assertTrue(value instanceof List<?>, location + " must be an array");
        return new ArrayList<>((List<?>) value);
    }

    private static String string(Object value, String location) {
        assertTrue(value instanceof String, location + " must be a string");
        return (String) value;
    }

    private static Set<String> stringSet(Object value, String location) {
        return list(value, location).stream()
                .map(entry -> string(entry, location + " entry"))
                .collect(Collectors.toSet());
    }

    private static Map<String, String> stringMap(Object value, String location) {
        var raw = map(value, location);
        var result = new LinkedHashMap<String, String>();
        raw.forEach((key, entry) -> result.put(key, string(entry, location + "." + key)));
        return result;
    }

    private static String refName(String ref) {
        var prefix = "#/components/schemas/";
        assertTrue(ref.startsWith(prefix), "not a local schema ref: " + ref);
        return ref.substring(prefix.length());
    }

    private static Path repositoryFile(String relative) {
        var current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            var candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository file not found: " + relative);
    }
}
