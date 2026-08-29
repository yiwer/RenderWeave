package cn.hbads.renderweave.rendering.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapabilityCallPositionTest {

    private static final String ROOT = "00000000-0000-4000-8000-0000000000a1";
    private static final String CHILD = "00000000-0000-4000-8000-0000000000c1";
    private static final String DEFINITION = "00000000-0000-4000-8000-0000000000d1";
    private static final String PARENT_LOOP = "00000000-0000-4000-8000-0000000000b1";
    private static final String CHILD_LOOP = "00000000-0000-4000-8000-0000000000b2";
    private static final String USE = "00000000-0000-4000-8000-0000000000e1";

    @Test
    void rootPositionUsesExactClosedCanonicalObject() {
        var position = CapabilityCallPosition.root(ROOT, 7)
                .invocationFrame()
                .canonicalBytes(DEFINITION, "today", "CLOCK", "UTC_DATE");

        assertEquals("{\"capabilityContractId\":\"renderweave-capability-clock/1.0\","
                        + "\"definitionId\":\"" + DEFINITION + "\","
                        + "\"inputAlias\":\"today\",\"operation\":\"UTC_DATE\","
                        + "\"path\":[{\"kind\":\"ROOT\",\"revision\":7,"
                        + "\"templateId\":\"" + ROOT + "\"}],"
                        + "\"positionVersion\":\"renderweave-capability-call-position/1.0\"}",
                new String(position, StandardCharsets.UTF_8));
    }

    @Test
    void nestedPathKeepsRuntimeOrderAndStopsAtDeclarationDomain() {
        var runtime = CapabilityCallPosition.root(ROOT, 7)
                .enterRepeat(PARENT_LOOP, 2)
                .enterTemplateUse(USE, CHILD, 9)
                .enterRepeat(CHILD_LOOP, 4);

        var invocation = new String(runtime.invocationFrame().canonicalBytes(
                DEFINITION, "draw", "RANDOM", "UNIFORM_DECIMAL_0_1"),
                StandardCharsets.UTF_8);
        var loop = new String(runtime.loopFrame(CHILD_LOOP).canonicalBytes(
                DEFINITION, "draw", "RANDOM", "UNIFORM_DECIMAL_0_1"),
                StandardCharsets.UTF_8);

        assertEquals(positionWithPath("[{\"kind\":\"ROOT\",\"revision\":7,"
                + "\"templateId\":\"" + ROOT + "\"},"
                + "{\"inputIndex\":2,\"kind\":\"REPEAT\",\"loopId\":\""
                + PARENT_LOOP + "\"},"
                + "{\"kind\":\"TEMPLATE_USE\",\"revision\":9,\"templateId\":\""
                + CHILD + "\",\"useId\":\"" + USE + "\"}]"), invocation);
        assertEquals(positionWithPath("[{\"kind\":\"ROOT\",\"revision\":7,"
                + "\"templateId\":\"" + ROOT + "\"},"
                + "{\"inputIndex\":2,\"kind\":\"REPEAT\",\"loopId\":\""
                + PARENT_LOOP + "\"},"
                + "{\"kind\":\"TEMPLATE_USE\",\"revision\":9,\"templateId\":\""
                + CHILD + "\",\"useId\":\"" + USE + "\"},"
                + "{\"inputIndex\":4,\"kind\":\"REPEAT\",\"loopId\":\""
                + CHILD_LOOP + "\"}]"), loop);
    }

    @Test
    void memoIdentityFollowsDeclarationFrameRatherThanConsumerFrame() {
        var root = CapabilityCallPosition.root(ROOT, 7);
        assertEquals(
                root.enterRepeat(PARENT_LOOP, 0).invocationFrame().memoKey(),
                root.enterRepeat(PARENT_LOOP, 1).invocationFrame().memoKey());

        var firstChild = root.enterRepeat(PARENT_LOOP, 0)
                .enterTemplateUse(USE, CHILD, 9).invocationFrame().memoKey();
        var secondChild = root.enterRepeat(PARENT_LOOP, 1)
                .enterTemplateUse(USE, CHILD, 9).invocationFrame().memoKey();
        assertNotEquals(firstChild, secondChild);
        assertThrows(IllegalArgumentException.class,
                () -> root.loopFrame(CHILD_LOOP));
    }

    private static String positionWithPath(String path) {
        return "{\"capabilityContractId\":\"renderweave-capability-random/1.0\","
                + "\"definitionId\":\"" + DEFINITION + "\","
                + "\"inputAlias\":\"draw\","
                + "\"operation\":\"UNIFORM_DECIMAL_0_1\",\"path\":" + path + ","
                + "\"positionVersion\":\"renderweave-capability-call-position/1.0\"}";
    }
}
