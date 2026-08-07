package cn.hbads.renderweave.schema.compile;

import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonParser;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSchemaCompilerTest {

    private final SchemaDefinitionJsonParser parser = new SchemaDefinitionJsonParser();
    private final JsonSchemaCompiler compiler = new JsonSchemaCompiler();

    @Test
    void emitsStableCompactBytesInFieldAndRequiredOrder() {
        var definition = parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"商品","fields":[
                  {"fieldKey":"title","required":true,"value":{"type":"text","constraints":{
                    "minLength":1,"maxLength":40,"pattern":"^[A-Z]+$","const":"ABC"}}},
                  {"fieldKey":"amount","required":false,"value":{"type":"decimal","constraints":{
                    "min":0,"multipleOf":0.0100}}}
                ]}
                """);

        var compiled = compiler.compile(ref("product-card", "v1"), definition, unusedResolver());

        assertEquals("renderweave-json-schema/1.0", compiled.compilerVersion());
        assertEquals(compiled.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                compiled.utf8Bytes());
        assertEquals(
                "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                        + "\"type\":\"object\",\"properties\":{"
                        + "\"title\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":40,"
                        + "\"pattern\":\"^[A-Z]+$\",\"const\":\"ABC\",\"x-renderweave-type\":\"text\"},"
                        + "\"amount\":{\"type\":\"number\",\"minimum\":0,\"multipleOf\":0.01,"
                        + "\"x-renderweave-type\":\"decimal\"}},"
                        + "\"required\":[\"title\"],\"additionalProperties\":true,"
                        + "\"x-renderweave-static-schema-ref\":{\"schemaKey\":\"product-card\","
                        + "\"versionTag\":\"v1\"},"
                        + "\"x-renderweave-compiler-version\":\"renderweave-json-schema/1.0\"}",
                compiled.json()
        );
    }

    @Test
    void mapsDateTimeBooleanAndScalarArrayExtensions() throws Exception {
        var definition = parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"类型","fields":[
                  {"fieldKey":"day","required":false,"value":{"type":"date","constraints":{
                    "min":"2026-01-01","exclusiveMax":"2027-01-01","const":"2026-03-21"}}},
                  {"fieldKey":"clock","required":false,"value":{"type":"time","constraints":{
                    "exclusiveMin":"08:00:00","max":"18:00:00","const":"16:32:00"}}},
                  {"fieldKey":"enabled","required":false,"value":{"type":"boolean","constraints":{"const":true}}},
                  {"fieldKey":"tags","required":false,"value":{"type":"array","constraints":{
                    "minItems":1,"maxItems":10,"uniqueItems":true},"items":{
                      "type":"text","constraints":{"maxLength":20}
                    }}}
                ]}
                """);

        var root = JsonMapper.builder().build().readTree(
                compiler.compile(ref("types", "v1"), definition, unusedResolver()).json()
        );

        var day = root.path("properties").path("day");
        assertEquals(JsonSchemaCompiler.DATE_PATTERN, day.path("pattern").asText());
        assertEquals("date", day.path("format").asText());
        assertEquals("2026-01-01", day.path("x-renderweave-constraints").path("min").asText());
        assertEquals("2027-01-01", day.path("x-renderweave-constraints").path("exclusiveMax").asText());

        var clock = root.path("properties").path("clock");
        assertEquals(JsonSchemaCompiler.TIME_PATTERN, clock.path("pattern").asText());
        assertFalse(clock.has("format"));
        assertEquals("08:00:00", clock.path("x-renderweave-constraints").path("exclusiveMin").asText());
        assertTrue(root.path("properties").path("enabled").path("const").asBoolean());
        assertEquals("text", root.path("properties").path("tags")
                .path("items").path("x-renderweave-type").asText());
        assertTrue(root.path("properties").path("tags").path("uniqueItems").asBoolean());
    }

    @Test
    void embedsStoredChildBodiesAndPreservesMixedCompilerVersions() throws Exception {
        var childDefinition = parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"子","fields":[]}
                """);
        var childRef = ref("child", "legacy");
        var childArtifact = compiler.compile(childRef, childDefinition, unusedResolver()).json()
                .replace("renderweave-json-schema/1.0", "legacy-compiler/0.9");
        var parentDefinition = parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"父","fields":[
                  {"fieldKey":"child","required":true,"value":{"type":"reference","ref":{
                    "schemaKey":"child","versionTag":"legacy"}}},
                  {"fieldKey":"children","required":false,"value":{"type":"array","items":{
                    "type":"reference","ref":{"schemaKey":"child","versionTag":"legacy"}}}}
                ]}
                """);

        var parentArtifact = compiler.compile(
                ref("parent", "v1"),
                parentDefinition,
                requested -> new CompiledStaticArtifact(requested, childArtifact)
        );
        var root = JsonMapper.builder().build().readTree(parentArtifact.json());

        assertEquals("legacy-compiler/0.9", root.path("properties").path("child")
                .path("x-renderweave-compiler-version").asText());
        assertEquals("reference", root.path("properties").path("child")
                .path("x-renderweave-type").asText());
        assertFalse(root.path("properties").path("child").has("$schema"));
        assertEquals("legacy-compiler/0.9", root.path("properties").path("children")
                .path("items").path("x-renderweave-compiler-version").asText());
        assertEquals("renderweave-json-schema/1.0",
                root.path("x-renderweave-compiler-version").asText());
        assertEquals(1, occurrences(parentArtifact.json(), "\"$schema\""));
    }

    @Test
    void rejectsLiveDraftReferencesAndArtifactsBeyondTwoMebibytes() {
        var liveDefinition = parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"父","fields":[
                  {"fieldKey":"child","required":false,"value":{"type":"reference","ref":{
                    "schemaKey":"child"}}}
                ]}
                """);
        assertThrows(InvalidCompiledArtifactException.class,
                () -> compiler.compile(ref("parent", "v1"), liveDefinition, unusedResolver()));

        var staticDefinition = parser.parse("""
                {"dslVersion":"renderweave-schema/1.0","displayName":"父","fields":[
                  {"fieldKey":"child","required":false,"value":{"type":"reference","ref":{
                    "schemaKey":"child","versionTag":"v1"}}}
                ]}
                """);
        var hugeChild = "{\"$schema\":\"" + JsonSchemaCompiler.META_SCHEMA + "\","
                + "\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true,"
                + "\"x-renderweave-static-schema-ref\":{\"schemaKey\":\"child\",\"versionTag\":\"v1\"},"
                + "\"x-renderweave-compiler-version\":\"legacy\","
                + "\"padding\":\"" + "x".repeat(JsonSchemaCompiler.MAX_ARTIFACT_BYTES) + "\"}";

        var error = assertThrows(CompiledArtifactTooLargeException.class, () -> compiler.compile(
                ref("parent", "v1"),
                staticDefinition,
                requested -> new CompiledStaticArtifact(requested, hugeChild)
        ));
        assertTrue(error.actualBytes() > JsonSchemaCompiler.MAX_ARTIFACT_BYTES);
    }

    private static StaticArtifactResolver unusedResolver() {
        return reference -> {
            throw new AssertionError("No reference expected: " + reference);
        };
    }

    private static StaticSchemaRef ref(String schemaKey, String versionTag) {
        var key = schemaKey.startsWith("system-")
                ? SchemaKey.systemProvided(schemaKey)
                : SchemaKey.userProvided(schemaKey);
        return new StaticSchemaRef(key, VersionTag.of(versionTag));
    }

    private static int occurrences(String value, String needle) {
        var count = 0;
        var offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
