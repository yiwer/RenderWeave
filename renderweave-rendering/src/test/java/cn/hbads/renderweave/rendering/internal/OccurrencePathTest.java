package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OccurrencePathTest {

    @Test
    void canonicalPathRecursivelyBindsInvocationRepeatUseAndSourceNodeRole() {
        var runtimePath = CapabilityCallPosition.root("template-root", 3)
                .enterRepeat("loop-a", 2)
                .enterTemplateUse("use-a", "template-child", 7);

        var path = OccurrencePath.sourceNode(
                runtimePath,
                "00000000-0000-4000-8000-000000000011",
                OccurrencePath.Role.SOURCE_NODE);

        assertEquals(
                "{\"pathVersion\":\"renderweave-occurrence-path/1.0\",\"segments\":["
                        + "{\"kind\":\"ROOT\",\"revision\":3,\"templateId\":\"template-root\"},"
                        + "{\"inputIndex\":2,\"kind\":\"REPEAT\",\"loopId\":\"loop-a\"},"
                        + "{\"kind\":\"TEMPLATE_USE\",\"revision\":7,"
                        + "\"templateId\":\"template-child\",\"useId\":\"use-a\"},"
                        + "{\"kind\":\"NODE\",\"nodeId\":"
                        + "\"00000000-0000-4000-8000-000000000011\","
                        + "\"role\":\"source-node\"}]}",
                path.canonicalJson());
    }

    @Test
    void repeatItemRoleHasNoInventedNodeIdentity() {
        var path = OccurrencePath.synthetic(
                CapabilityCallPosition.root("template-root", 3)
                        .enterRepeat("loop-a", 2),
                OccurrencePath.Role.REPEAT_ITEM);

        assertFalse(path.canonicalJson().contains("nodeId"));
        assertEquals("repeat-item", path.role().wire());
    }

    @Test
    void resourceIdentityIncludesExactSourceNodeAndConsumerProperty() {
        var runtimePath = CapabilityCallPosition.root("template-root", 3);
        var first = OccurrencePath.sourceNode(
                runtimePath,
                "00000000-0000-4000-8000-000000000011",
                OccurrencePath.Role.SOURCE_NODE);
        var second = OccurrencePath.sourceNode(
                runtimePath,
                "00000000-0000-4000-8000-000000000012",
                OccurrencePath.Role.SOURCE_NODE);

        assertNotEquals(
                new String(first.resourceIdentityBytes(runFont(0), AssetKind.FONT),
                        StandardCharsets.UTF_8),
                new String(second.resourceIdentityBytes(runFont(0), AssetKind.FONT),
                        StandardCharsets.UTF_8));
        assertNotEquals(
                new String(first.resourceIdentityBytes(runFont(0), AssetKind.FONT),
                        StandardCharsets.UTF_8),
                new String(first.resourceIdentityBytes(runFont(1), AssetKind.FONT),
                        StandardCharsets.UTF_8));
    }

    private static ConsumerPropertyRef runFont(int index) {
        return ConsumerPropertyRef.of("runs", java.util.List.of(
                new ConsumerPropertyRef.IndexSelector(index),
                new ConsumerPropertyRef.MemberSelector("fontRef")));
    }
}
