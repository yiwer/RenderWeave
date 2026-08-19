package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Acceptance;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.FontDescriptor;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.ImageDescriptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcceptanceKernelVectorTest {

    private static final String VECTOR_VERSION = "renderweave-asset-acceptance-kernel-v1/1";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void replaysEveryFrozenVectorThroughThePublicInterface() throws Exception {
        var manifestBytes = AcceptanceKernelVectorTest.class
                .getResourceAsStream("/cn/hbads/renderweave/asset/acceptance-kernel-v1/vectors.json")
                .readAllBytes();
        var manifest = JSON.readTree(manifestBytes);
        assertEquals(VECTOR_VERSION, manifest.get("vectorVersion").asText());
        assertEquals(
                "NOT_REGISTERED",
                manifest.get("authorityContext").get("profileAvailability").asText()
        );

        var authority = new CanonicalAssetAcceptanceAuthority();
        List<Map<String, Object>> results = new ArrayList<>();
        int failed = 0;
        for (JsonNode vector : manifest.get("cases")) {
            var id = vector.get("id").asText();
            var input = vector.get("input");
            if (!"BASE64".equals(input.get("kind").asText())) {
                throw new AssertionError("unexpected input kind");
            }
            byte[] raw = Base64.getDecoder().decode(input.get("data").asText());
            var expected = vector.get("expected");
            AssetKind kind = AssetKind.valueOf(vector.get("assetKind").asText());
            var result = new LinkedHashMap<String, Object>();
            Acceptance outcome = authority.admit(raw, kind);
            if (outcome instanceof AssetAcceptanceAuthority.Admitted admitted) {
                result.put("outcome", "ADMITTED");
                result.put("kind", admitted.kind().name());
                result.put("byteLength", admitted.byteLength());
                result.put("sha256", admitted.sha256());
                result.put("acceptanceProfileId", admitted.acceptanceProfileId());
                result.put("descriptor", descriptor(admitted.descriptor()));
            } else if (outcome instanceof AssetAcceptanceAuthority.Rejected rejected) {
                result.put("outcome", "REJECTED");
                result.put("code", rejected.code().name());
                result.put("stage", rejected.stage().name());
                result.put("pointer", rejected.pointer());
                result.put("limit", rejected.limit().map(limit -> limit.id()).orElse(null));
            } else {
                throw new AssertionError("unknown outcome");
            }
            if (!matchesExpected(expected, result)) {
                failed++;
            }
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", id);
            entry.putAll(result);
            results.add(entry);
        }

        String vectorSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(manifestBytes)
        );
        String reportPath = System.getProperty("renderweave.asset.primaryReport");
        if (reportPath != null) {
            var report = new LinkedHashMap<String, Object>();
            report.put("reportVersion", "renderweave-asset-kernel-primary/1");
            report.put("engine", "java-primary");
            report.put("vectorVersion", VECTOR_VERSION);
            report.put("vectorSha256", vectorSha256);
            report.put("acceptanceProfileAvailability", "NOT_REGISTERED");
            report.put("cases", results.size());
            report.put("passed", results.size() - failed);
            report.put("failed", failed);
            report.put("results", results);
            var reportBytes = JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(report);
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of(reportPath),
                    new String(reportBytes, StandardCharsets.UTF_8) + "\n",
                    StandardCharsets.UTF_8
            );
        }
        assertEquals(0, failed, "frozen vectors drifted from the public interface");
    }

    private static boolean matchesExpected(JsonNode expected, Map<String, Object> actual) {
        return matches(expected, JSON.valueToTree(actual));
    }

    private static boolean matches(JsonNode expected, JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        }
        if (!expected.getNodeType().equals(actual.getNodeType())) {
            return false;
        }
        if (expected.isObject()) {
            for (var name : expected.propertyNames()) {
                var actualField = actual.get(name);
                if (actualField == null || !matches(expected.get(name), actualField)) {
                    return false;
                }
            }
            return expected.size() == actual.size();
        }
        if (expected.isArray()) {
            if (expected.size() != actual.size()) {
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!matches(expected.get(i), actual.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return expected.equals(actual);
    }

    private static Map<String, Object> descriptor(
            AssetAcceptanceAuthority.TechnicalDescriptor descriptor
    ) {
        var fields = new LinkedHashMap<String, Object>();
        if (descriptor instanceof ImageDescriptor image) {
            fields.put("type", "IMAGE");
            fields.put("encodedWidthPx", image.encodedWidthPx());
            fields.put("encodedHeightPx", image.encodedHeightPx());
            fields.put("orientation", image.orientation().name());
            fields.put("logicalWidthPx", image.logicalWidthPx());
            fields.put("logicalHeightPx", image.logicalHeightPx());
            fields.put("frameCount", image.frameCount());
            fields.put("colorEncoding", image.colorEncoding().name());
        } else if (descriptor instanceof FontDescriptor font) {
            fields.put("type", "FONT");
            fields.put("faceIndex", font.faceIndex());
            fields.put("flavor", font.flavor().name());
            fields.put("unitsPerEm", font.unitsPerEm());
        } else {
            throw new AssertionError("unknown descriptor");
        }
        return fields;
    }
}
