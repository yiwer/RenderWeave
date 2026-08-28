package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.rendering.spi.AssetResolutionPort;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication.TemplateId;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.ClosureSnapshot;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.OwnerScope;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.TemplateSnapshot;
import cn.hbads.renderweave.template.internal.TemplateModule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AssetAdmissionTest {

    private static final String ROOT_ID = "00000000-0000-4000-8000-000000000001";
    private static final String IMAGE_ID = "00000000-0000-4000-8000-0000000000a1";
    private static final String FONT_ID = "00000000-0000-4000-8000-0000000000a2";
    private static final String PRIVATE_DEFAULT_ID = "00000000-0000-4000-8000-0000000000a3";
    private static final String OVERRIDE_IMAGE_1 = "00000000-0000-4000-8000-0000000000a4";
    private static final String OVERRIDE_IMAGE_2 = "00000000-0000-4000-8000-0000000000a5";
    private static final String OVERRIDE_FONT = "00000000-0000-4000-8000-0000000000a6";
    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));

    @Test
    void admitsTypedCustomDefaultsEvenWhenTheyAreNeverDemanded() {
        var dsl = "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"Assets\",\"definitions\":["
                + "{\"definitionId\":\"00000000-0000-4000-8000-0000000000d1\","
                + "\"kind\":\"custom\",\"displayName\":\"Logo\",\"exposure\":\"PRIVATE\","
                + "\"valueType\":\"imageRef\",\"defaultValue\":{\"assetId\":\"" + IMAGE_ID + "\"}},"
                + "{\"definitionId\":\"00000000-0000-4000-8000-0000000000d2\","
                + "\"kind\":\"custom\",\"displayName\":\"Font\",\"exposure\":\"PRIVATE\","
                + "\"valueType\":\"fontRef\",\"defaultValue\":{\"assetId\":\"" + FONT_ID + "\"}}],"
                + "\"designRoot\":{\"nodeId\":\"" + ROOT_ID + "\",\"kind\":\"canvas\","
                + "\"widthMm\":210,\"heightMm\":297,\"bindings\":[],\"children\":[]}}";
        var port = new RecordingAssetPort();

        var outcome = AssetAdmission.admit(
                closure(dsl),
                TemplateModule.designSemanticAuthority(),
                port,
                new AdmittedRenderInput(
                        SCHEMA, new TypedObject(SCHEMA, Map.of()), Map.of(), Map.of()));

        assertInstanceOf(AssetAdmission.Admitted.class, outcome);
        assertEquals(List.of(IMAGE_ID, FONT_ID), port.assetIds);
        assertEquals(List.of(AssetKind.IMAGE, AssetKind.FONT), port.kinds);
    }

    @Test
    void admitsOnlyActualExternalOverridesAndExpandsAssetLists() {
        var images = new DesignValue.ListValue("imageRef", List.of(
                new DesignValue.ImageRef(OVERRIDE_IMAGE_1),
                new DesignValue.ImageRef(OVERRIDE_IMAGE_2)));
        var font = new DesignValue.FontRef(OVERRIDE_FONT);
        var port = new RecordingAssetPort();

        var outcome = AssetAdmission.admit(
                closure(emptyDsl()),
                TemplateModule.designSemanticAuthority(),
                port,
                new AdmittedRenderInput(
                        SCHEMA,
                        new TypedObject(SCHEMA, Map.of()),
                        Map.of(
                                "private-default", new DesignValue.ImageRef(PRIVATE_DEFAULT_ID),
                                "images", images,
                                "font", font),
                        Map.of("images", images, "font", font)));

        assertInstanceOf(AssetAdmission.Admitted.class, outcome);
        assertEquals(List.of(OVERRIDE_FONT, OVERRIDE_IMAGE_1, OVERRIDE_IMAGE_2), port.assetIds);
        assertEquals(List.of(AssetKind.FONT, AssetKind.IMAGE, AssetKind.IMAGE), port.kinds);
    }

    private static String emptyDsl() {
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"Empty\",\"definitions\":[],"
                + "\"designRoot\":{\"nodeId\":\"" + ROOT_ID + "\",\"kind\":\"canvas\","
                + "\"widthMm\":210,\"heightMm\":297,\"bindings\":[],\"children\":[]}}";
    }

    private static ClosureSnapshot closure(String dsl) {
        var admission = TemplateModule.designDslAuthority()
                .admit(dsl.getBytes(StandardCharsets.UTF_8));
        var admitted = assertInstanceOf(DesignDslAuthority.Admitted.class, admission);
        var snapshot = new TemplateSnapshot(
                new TemplateId(ROOT_ID),
                1,
                new OwnerScope("owner-a"),
                SCHEMA,
                "renderweave-design/1.0",
                "renderweave-expression/1.0",
                admitted.canonicalUtf8(),
                admitted.contentHash());
        return new ClosureSnapshot(
                new OwnerScope("owner-a"), snapshot.templateId(), 1, List.of(snapshot), List.of());
    }

    private static final class RecordingAssetPort implements AssetResolutionPort {
        private final List<String> assetIds = new ArrayList<>();
        private final List<AssetKind> kinds = new ArrayList<>();

        @Override
        public PrecheckOutcome precheckAdmission(
                cn.hbads.renderweave.asset.api.AssetApplication.OwnerScope ownerScope,
                cn.hbads.renderweave.asset.api.AssetApplication.AssetId assetId,
                AssetKind expectedKind
        ) {
            assetIds.add(assetId.value());
            kinds.add(expectedKind);
            return new PrecheckOutcome.PrecheckPassed();
        }

        @Override
        public ResolveOutcome resolve(ResolveRequest request) {
            throw new AssertionError("asset resolve must not run during admission");
        }
    }
}
