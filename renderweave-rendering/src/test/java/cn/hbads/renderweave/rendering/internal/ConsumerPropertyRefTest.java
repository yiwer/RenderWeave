package cn.hbads.renderweave.rendering.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsumerPropertyRefTest {

    @Test
    void imageRefUsesTheFrozenClosedRootShape() {
        var reference = ConsumerPropertyRef.root("imageRef");

        assertEquals(
                "{\"rootPropertyId\":\"imageRef\",\"selectors\":[]}",
                reference.canonicalJson());
    }

    @Test
    void firstTextRunFontUsesIndexThenMemberSelectors() {
        var reference = ConsumerPropertyRef.of(
                "runs",
                List.of(
                        new ConsumerPropertyRef.IndexSelector(0),
                        new ConsumerPropertyRef.MemberSelector("fontRef")));

        assertEquals(
                "{\"rootPropertyId\":\"runs\",\"selectors\":["
                        + "{\"index\":0,\"kind\":\"INDEX\"},"
                        + "{\"kind\":\"MEMBER\",\"memberId\":\"fontRef\"}]}",
                reference.canonicalJson());
    }
}
