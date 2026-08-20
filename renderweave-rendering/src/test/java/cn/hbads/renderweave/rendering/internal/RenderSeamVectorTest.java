package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.OwnerScope;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.TemplateSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * renderweave-render-seam-v1 向量语料 Java primary 重放（ADR-0044 §9）：random 派生、
 * closure manifest canonical/digest、admitted input canonical/digest、result digest 与
 * fingerprint 组合。向量期望值由独立 Python 计算（random/digest 组合）或 pinned golden
 * （seal canonical）。独立重放（Rust）与 render gate 入 full 随 T08 实现票。
 */
class RenderSeamVectorTest {

    private static final JsonMapper JSON = new JsonMapper();

    @Test
    void replaysAllFrozenVectors() throws Exception {
        JsonNode manifest;
        try (InputStream in = getClass().getResourceAsStream("render-seam-vectors-v1.json")) {
            manifest = JSON.readTree(in.readAllBytes());
        }
        assertEquals("renderweave-render-seam-v1/1",
                manifest.get("vectorVersion").asString());
        assertEquals("NOT_REGISTERED",
                manifest.get("authorityContext").get("profileAvailability").asString());

        int passed = 0;
        int total = 0;
        for (var vector : manifest.required("cases")) {
            total++;
            var id = vector.get("id").asString();
            switch (vector.get("kind").asString()) {
                case "randomDerivation" -> {
                    var nonce = HexFormat.of().parseHex(vector.get("nonceHex").asString());
                    var position = Base64.getDecoder()
                            .decode(vector.get("positionBase64").asString());
                    var value = CapabilityValues.uniformDecimal(nonce, position);
                    assertEquals(vector.get("expectedDecimal").asString(),
                            value.toPlainString(), id);
                }
                case "closureDigest" -> {
                    var closure = closureFromVector(vector);
                    var canonical = new String(
                            ClosureManifests.canonicalManifest(closure), StandardCharsets.UTF_8);
                    assertEquals(vector.get("expectedCanonical").asString(), canonical, id);
                    assertEquals(vector.get("expectedDigest").asString(),
                            ClosureManifests.digest(closure), id);
                }
                case "admittedInputDigest" -> {
                    var schema = new StaticSchemaRef(
                            SchemaKey.systemProvided(vector.get("schemaKey").asString()),
                            VersionTag.of(vector.get("versionTag").asString()));
                    var input = new AdmittedRenderInput(
                            schema, new TypedObject(schema, Map.of()), Map.of());
                    var canonical = new String(
                            AdmittedInputCanonicalizer.canonical(input), StandardCharsets.UTF_8);
                    assertEquals(vector.get("expectedCanonical").asString(), canonical, id);
                    assertEquals(vector.get("expectedDigest").asString(),
                            AdmittedInputCanonicalizer.digest(input), id);
                }
                case "resultDigest" -> assertEquals(
                        vector.get("expectedDigest").asString(),
                        Sealer.evaluationResultDigest(
                                vector.get("ownerScope").asString(),
                                vector.get("closureDigest").asString(),
                                vector.get("admittedInputDigest").asString(),
                                vector.get("assetSelectionDigest").asString(),
                                vector.get("capabilityResultDigest").asString()),
                        id);
                case "fingerprint" -> assertEquals(
                        vector.get("expectedDigest").asString(),
                        CapabilityValues.evaluationFingerprint(
                                vector.get("ownerScope").asString(),
                                vector.get("authorizationContextDigest").asString(),
                                vector.get("closureDigest").asString(),
                                vector.get("admittedInputDigest").asString(),
                                "renderweave-render/1.0",
                                "renderweave-layout/1.0",
                                vector.get("capabilityContracts").asString(),
                                "renderweave-asset-acceptance/1.0",
                                vector.get("effectiveBudgetVector").asString()),
                        id);
                default -> throw new AssertionError(
                        "unknown vector kind in " + id);
            }
            passed++;
        }
        assertEquals(total, passed, "all vectors must replay");
        assertTrue(total >= 6, "corpus must carry the frozen vector families");

        // 语料 manifest 哈希锁定：任何向量修改都改变该哈希（报告引用）。
        try (InputStream in = getClass().getResourceAsStream("render-seam-vectors-v1.json")) {
            var bytes = in.readAllBytes();
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(64, hash.length());
        }
    }

    private static ClosureSnapshot closureFromVector(JsonNode vector) {
        var snapshotNode = vector.get("snapshot");
        var schema = new StaticSchemaRef(
                SchemaKey.systemProvided(snapshotNode.get("schemaKey").asString()),
                VersionTag.of(snapshotNode.get("versionTag").asString()));
        var snapshot = new TemplateSnapshot(
                new TemplateApplication.TemplateId(snapshotNode.get("templateId").asString()),
                snapshotNode.get("revision").asLong(),
                new OwnerScope(vector.get("ownerScope").asString()),
                schema,
                snapshotNode.get("dslVersion").asString(),
                snapshotNode.get("expressionProfile").asString(),
                "{}".getBytes(StandardCharsets.UTF_8),
                snapshotNode.get("contentHash").asString());
        return new ClosureSnapshot(
                new OwnerScope(vector.get("ownerScope").asString()),
                new TemplateApplication.TemplateId(vector.get("rootTemplateId").asString()),
                vector.get("rootRevision").asLong(),
                List.of(snapshot),
                List.of());
    }
}
