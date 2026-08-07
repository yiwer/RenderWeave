package cn.hbads.renderweave.schema.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaIdentityTest {

    @Test
    void schemaKeyFollowsTheFrozenSyntaxAndReservesSystemPrefixForPresets() {
        assertEquals("product-card", SchemaKey.userProvided("product-card").value());
        assertEquals("system-text", SchemaKey.systemProvided("system-text").value());

        assertThrows(IllegalArgumentException.class, () -> SchemaKey.userProvided("Product"));
        assertThrows(IllegalArgumentException.class, () -> SchemaKey.userProvided("system-text"));
        assertThrows(IllegalArgumentException.class, () -> SchemaKey.userProvided("a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> SchemaKey.systemProvided("product-card"));
    }

    @Test
    void versionTagHasNoOrderingOrLatestSemantics() {
        assertEquals("2026.08_rc-1", VersionTag.of("2026.08_rc-1").value());
        assertThrows(IllegalArgumentException.class, () -> VersionTag.of("V1"));
        assertThrows(IllegalArgumentException.class, () -> VersionTag.of("a".repeat(65)));
    }

    @Test
    void fieldKeyUsesUtf8BytesAllowsPointerCharactersAndDoesNotNormalizeUnicode() {
        var key = FieldKey.of("商品/名称~原文");
        assertEquals("商品~1名称~0原文", key.jsonPointerSegment());

        var composed = FieldKey.of("é");
        var decomposed = FieldKey.of("e\u0301");
        assertNotEquals(composed, decomposed);

        assertThrows(IllegalArgumentException.class, () -> FieldKey.of(""));
        assertThrows(IllegalArgumentException.class, () -> FieldKey.of("a".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> FieldKey.of("汉".repeat(43)));
        assertThrows(IllegalArgumentException.class, () -> FieldKey.of("line\nfeed"));
    }
}
