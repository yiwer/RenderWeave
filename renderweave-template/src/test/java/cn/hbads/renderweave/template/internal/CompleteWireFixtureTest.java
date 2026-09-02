package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CompleteWireFixtureTest {

    private static final String RESOURCE =
            "/cn/hbads/renderweave/template/complete-wire-v1/all-kinds.json";
    private static final Set<String> NODE_KINDS = Set.of(
            "canvas", "group", "frame", "stack", "grid", "repeat", "text", "image",
            "rect", "ellipse", "line", "polygon", "polyline", "path", "qrCode", "barcode",
            "templateUse", "conditional"
    );
    private static final Set<String> VALUE_SOURCE_KINDS = Set.of(
            "literal", "definition", "context", "loopIndex", "capability"
    );

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fixtureIsAdmittedAndCoversTheCompleteClosedWire() throws Exception {
        var source = readFixture();
        var admission = TemplateModule.designDslAuthority().admit(source);
        var admitted = assertInstanceOf(
                DesignDslAuthority.Admitted.class,
                admission,
                () -> "complete-wire admission failed: " + admission
        );
        assertArrayEquals(
                source,
                admitted.canonicalUtf8(),
                "the shared complete-wire fixture must stay exact canonical UTF-8"
        );
        var canonical = json.readTree(admitted.canonicalUtf8());

        var nodeKinds = new HashSet<String>();
        var placementKinds = new HashSet<String>();
        collectNodes(canonical.required("designRoot"), nodeKinds, placementKinds);
        assertEquals(NODE_KINDS, nodeKinds);
        assertEquals(Set.of("ABSOLUTE", "STACK", "GRID", "PACK"), placementKinds);

        var definitionKinds = new HashSet<String>();
        for (var definition : canonical.required("definitions")) {
            definitionKinds.add(definition.required("kind").asString());
        }
        assertEquals(Set.of("custom", "mapping", "expression"), definitionKinds);

        var valueSourceKinds = new HashSet<String>();
        collectValueSources(canonical, valueSourceKinds);
        assertEquals(VALUE_SOURCE_KINDS, valueSourceKinds);
    }

    private void collectNodes(
            JsonNode node,
            Set<String> nodeKinds,
            Set<String> placementKinds
    ) {
        nodeKinds.add(node.required("kind").asString());
        if (node.has("placement")) {
            placementKinds.add(node.required("placement").required("type").asString());
        }
        if (node.has("children")) {
            for (var child : node.required("children")) {
                collectNodes(child, nodeKinds, placementKinds);
            }
        }
    }

    private void collectValueSources(JsonNode value, Set<String> valueSourceKinds) {
        if (value.isObject()) {
            if (value.has("kind")) {
                var kind = value.required("kind").asString();
                if (VALUE_SOURCE_KINDS.contains(kind)) {
                    valueSourceKinds.add(kind);
                }
            }
            for (var property : value.properties()) {
                collectValueSources(property.getValue(), valueSourceKinds);
            }
            return;
        }
        if (value.isArray()) {
            for (var item : value) {
                collectValueSources(item, valueSourceKinds);
            }
        }
    }

    private byte[] readFixture() throws IOException {
        try (var input = CompleteWireFixtureTest.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing complete-wire fixture " + RESOURCE);
            }
            return input.readAllBytes();
        }
    }
}
