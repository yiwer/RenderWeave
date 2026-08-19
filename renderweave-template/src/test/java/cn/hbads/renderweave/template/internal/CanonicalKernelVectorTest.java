package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalKernelVectorTest {

    private static final String RESOURCE =
            "/cn/hbads/renderweave/template/canonical-kernel-v1/vectors.json";
    private final ObjectMapper json = new ObjectMapper();
    private final DesignDslAuthority authority = new CanonicalDesignDslAuthority();

    @Test
    void replaysEveryFrozenVectorAndWritesThePrimaryReport() throws Exception {
        var manifestBytes = readManifest();
        var manifest = json.readTree(manifestBytes);
        assertEquals(
                "renderweave-template-canonical-kernel-v1/5",
                manifest.required("vectorVersion").asString()
        );
        assertEquals(
                "system-empty@v1",
                manifest.required("authorityContext").required("staticSchemaProfile").asString()
        );
        assertEquals(
                "NOT_REGISTERED",
                manifest.required("authorityContext").required("profileAvailability").asString()
        );
        assertEquals(152, manifest.required("cases").size());

        var results = new ArrayList<Map<String, Object>>();
        for (var vector : manifest.required("cases")) {
            results.add(replay(vector));
        }

        writeReport(manifest, manifestBytes, results);
    }

    private Map<String, Object> replay(JsonNode vector) {
        var id = vector.required("id").asString();
        var expected = vector.required("expected");
        var outcome = authority.admit(input(vector.required("input")));
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);

        if ("ADMITTED".equals(expected.required("outcome").asString())) {
            var admitted = assertInstanceOf(
                    DesignDslAuthority.Admitted.class,
                    outcome,
                    id
            );
            var canonical = admitted.canonicalUtf8();
            var canonicalSha256 = sha256(canonical);
            assertEquals(expected.required("canonicalBytes").asInt(), canonical.length, id);
            assertEquals(expected.required("canonicalSha256").asString(), canonicalSha256, id);
            assertEquals(expected.required("contentHash").asString(), admitted.contentHash(), id);
            if (expected.has("canonicalUtf8")) {
                assertEquals(
                        expected.required("canonicalUtf8").asString(),
                        new String(canonical, StandardCharsets.UTF_8),
                        id
                );
            }
            result.put("outcome", "ADMITTED");
            result.put("canonicalBytes", canonical.length);
            result.put("canonicalSha256", canonicalSha256);
            result.put("contentHash", admitted.contentHash());
            return result;
        }

        var rejected = assertInstanceOf(DesignDslAuthority.Rejected.class, outcome, id);
        var expectedPointer = expectedPointer(expected);
        var actualLimit = rejected.limit().map(DesignDslAuthority.Limit::id).orElse(null);
        assertEquals(expected.required("code").asString(), rejected.code().name(), id);
        assertEquals(expected.required("stage").asString(), rejected.stage().name(), id);
        assertEquals(expectedPointer, rejected.pointer(), id);
        assertEquals(nullableText(expected.required("limit")), actualLimit, id);
        result.put("outcome", "REJECTED");
        result.put("code", rejected.code().name());
        result.put("stage", rejected.stage().name());
        result.put("pointer", rejected.pointer());
        result.put("limit", actualLimit);
        return result;
    }

    private byte[] input(JsonNode spec) {
        return switch (spec.required("kind").asString()) {
            case "UTF8" -> spec.required("text").asString().getBytes(StandardCharsets.UTF_8);
            case "HEX" -> HexFormat.of().parseHex(spec.required("hex").asString());
            case "UTF8_BOM" -> withUtf8Bom(spec.required("text").asString());
            case "CANVAS" -> canvas(spec, "210");
            case "PADDED_CANVAS" -> paddedCanvas(spec.required("totalBytes").asInt());
            case "NESTED_ARRAY" -> nestedArray(spec.required("depth").asInt());
            case "OBJECT_MEMBERS" -> objectMembers(spec.required("count").asInt());
            case "ARRAY_ITEMS" -> arrayItems(spec.required("count").asInt());
            case "TOTAL_VALUES" -> totalValues(spec);
            case "STRING_BYTES" -> stringBytes(spec.required("count").asInt());
            case "MEMBER_NAME_BYTES" -> memberNameBytes(spec.required("count").asInt());
            case "CANVAS_WIDTH_DIGITS" -> canvas(
                    spec,
                    "1".repeat(spec.required("count").asInt())
            );
            case "CANVAS_WIDTH_EXPONENT" -> canvas(
                    spec,
                    "1e" + spec.required("exponent").asString()
            );
            default -> throw new IllegalArgumentException(
                    "Unknown vector input kind: " + spec.required("kind").asString()
            );
        };
    }

    private byte[] canvas(JsonNode spec, String widthToken) {
        var dslVersion = spec.path("dslVersion").asString("renderweave-design/1.0");
        var definitions = spec.path("definitions").asString("[]");
        var children = spec.path("children").asString("[]");
        var canvasPrefix = spec.path("canvasPrefix").asString("");
        var rootSuffix = spec.path("rootSuffix").asString("");
        var nodeKind = spec.path("nodeKind").asString("canvas");
        var raw = "{\"dslVersion\":\"" + dslVersion + "\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"Baseline\","
                + "\"definitions\":" + definitions + ","
                + "\"designRoot\":{" + canvasPrefix
                + "\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"" + nodeKind + "\",\"widthMm\":" + widthToken + ","
                + "\"heightMm\":297,\"bindings\":[],\"children\":" + children + "}"
                + rootSuffix + "}";
        return raw.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] paddedCanvas(int totalBytes) {
        var raw = canvas(json.createObjectNode(), "210");
        assertTrue(raw.length <= totalBytes, "padded canvas target is too small");
        var padded = Arrays.copyOf(raw, totalBytes);
        Arrays.fill(padded, raw.length, padded.length, (byte) ' ');
        return padded;
    }

    private byte[] nestedArray(int depth) {
        return ("[".repeat(depth) + "0" + "]".repeat(depth))
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] objectMembers(int count) {
        var raw = new StringBuilder(count * 10).append('{');
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                raw.append(',');
            }
            raw.append('"').append('m').append(index).append("\":0");
        }
        return raw.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] arrayItems(int count) {
        var raw = new StringBuilder(count * 2 + 2).append('[');
        appendZeros(raw, count);
        return raw.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] totalValues(JsonNode spec) {
        var fullArrays = spec.required("fullArrays").asInt();
        var fullArrayItems = spec.required("fullArrayItems").asInt();
        var lastArrayItems = spec.required("lastArrayItems").asInt();
        var raw = new StringBuilder(2_100_000).append('[');
        for (int index = 0; index < fullArrays; index++) {
            if (index > 0) {
                raw.append(',');
            }
            raw.append('[');
            appendZeros(raw, fullArrayItems);
            raw.append(']');
        }
        if (fullArrays > 0) {
            raw.append(',');
        }
        raw.append('[');
        appendZeros(raw, lastArrayItems);
        return raw.append("]]").toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendZeros(StringBuilder raw, int count) {
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                raw.append(',');
            }
            raw.append('0');
        }
    }

    private byte[] stringBytes(int count) {
        return ("\"" + "a".repeat(count) + "\"").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] memberNameBytes(int count) {
        return ("{\"" + "a".repeat(count) + "\":0}").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] withUtf8Bom(String text) {
        var content = text.getBytes(StandardCharsets.UTF_8);
        var raw = new byte[content.length + 3];
        raw[0] = (byte) 0xef;
        raw[1] = (byte) 0xbb;
        raw[2] = (byte) 0xbf;
        System.arraycopy(content, 0, raw, 3, content.length);
        return raw;
    }

    private String expectedPointer(JsonNode expected) {
        if (expected.has("pointer")) {
            return expected.required("pointer").asString();
        }
        var repeat = expected.required("pointerRepeat");
        return repeat.required("prefix").asString()
                + repeat.required("value").asString().repeat(repeat.required("count").asInt());
    }

    private String nullableText(JsonNode node) {
        return node.isNull() ? null : node.asString();
    }

    private byte[] readManifest() throws IOException {
        try (var input = CanonicalKernelVectorTest.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing vector resource " + RESOURCE);
            }
            return input.readAllBytes();
        }
    }

    private void writeReport(
            JsonNode manifest,
            byte[] manifestBytes,
            List<Map<String, Object>> results
    ) throws IOException {
        var configured = System.getProperty("renderweave.template.primaryReport", "").trim();
        if (configured.isEmpty()) {
            return;
        }
        var reportPath = Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(reportPath.getParent());
        var report = new LinkedHashMap<String, Object>();
        report.put("reportVersion", "renderweave-template-kernel-primary/1");
        report.put("engine", "java-primary");
        report.put("vectorVersion", manifest.required("vectorVersion").asString());
        report.put("vectorSha256", sha256(manifestBytes));
        report.put("profileAvailability", "NOT_REGISTERED");
        report.put("cases", results.size());
        report.put("passed", results.size());
        report.put("failed", 0);
        report.put("results", results);
        json.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
