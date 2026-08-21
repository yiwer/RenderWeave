package cn.hbads.renderweave.template.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base-registration invariants of the append-only BindingPolicyCatalog (ticket 09 §8).
 * Bindability consumption is the Binding atoms ticket (T16); this test only pins the
 * concrete per-kind entries and the never-bindable identities.
 */
class BindingPolicyCatalogTest {

    @Test
    void registersTheFrozenBaseEntries() {
        assertTrue(BindingPolicyCatalog.allows("canvas", "backgroundColor"));
        assertTrue(BindingPolicyCatalog.allows("frame", "fill.color"));
        assertTrue(BindingPolicyCatalog.allows("stack", "direction"));
        assertTrue(BindingPolicyCatalog.allows("grid", "rows[*].valueMm"));
        assertTrue(BindingPolicyCatalog.allows("repeat", "itemLayout.direction"));
        assertTrue(BindingPolicyCatalog.allows("text", "runs[*].text"));
        assertTrue(BindingPolicyCatalog.allows("text", "lineHeight.factor"));
        assertTrue(BindingPolicyCatalog.allows("text", "stroke.widthPt"));
        assertTrue(BindingPolicyCatalog.allows("image", "imageRef"));
        assertTrue(BindingPolicyCatalog.allows("line", "start.xMm"));
        assertTrue(BindingPolicyCatalog.allows("polygon", "points[*].yMm"));
        assertTrue(BindingPolicyCatalog.allows("path", "commands[*].c2yMm"));
        assertTrue(BindingPolicyCatalog.allows("qrCode", "content"));
        assertTrue(BindingPolicyCatalog.allows("barcode", "value"));
        assertTrue(BindingPolicyCatalog.allows("group", "render"));
        assertTrue(BindingPolicyCatalog.allows("group", "placement.xMm"));
        assertTrue(BindingPolicyCatalog.allows("templateUse", "placement.xMm"));
        assertTrue(BindingPolicyCatalog.allows("templateUse", "transform.rotationDeg"));
        assertTrue(BindingPolicyCatalog.allows("conditional", "placement.xMm"));
        assertFalse(BindingPolicyCatalog.allows("conditional", "condition"));
        assertFalse(BindingPolicyCatalog.allows("conditional", "absentPolicy"));
    }

    @Test
    void neverAuthorizesIdentityOrStructureTargets() {
        for (var kind : java.util.List.of("canvas", "group", "frame", "text", "image")) {
            assertFalse(BindingPolicyCatalog.allows(kind, "nodeId"));
            assertFalse(BindingPolicyCatalog.allows(kind, "kind"));
            assertFalse(BindingPolicyCatalog.allows(kind, "displayName"));
            assertFalse(BindingPolicyCatalog.allows(kind, "children"));
            assertFalse(BindingPolicyCatalog.allows(kind, "bindings"));
            assertFalse(BindingPolicyCatalog.allows(kind, "placement.type"));
            assertFalse(BindingPolicyCatalog.allows(kind, "placement.widthMode"));
            assertFalse(BindingPolicyCatalog.allows(kind, "placement.heightMode"));
        }
        assertFalse(BindingPolicyCatalog.allows("canvas", "widthMm"));
        assertFalse(BindingPolicyCatalog.allows("text", "fitMode"));
        assertFalse(BindingPolicyCatalog.allows("text", "lineHeight.type"));
        assertFalse(BindingPolicyCatalog.allows("repeat", "items"));
        assertFalse(BindingPolicyCatalog.allows("repeat", "loopId"));
        assertFalse(BindingPolicyCatalog.allows("repeat", "absentPolicy"));
        assertFalse(BindingPolicyCatalog.allows("group", "fill.color"));
        assertFalse(BindingPolicyCatalog.allows("text", "children"));
    }

    @Test
    void expandsPerKindWithoutWildcards() {
        // "every non-Canvas kind" common entries must exist per concrete kind.
        for (var kind : java.util.List.of(
                "group", "frame", "stack", "grid", "repeat",
                "text", "image", "rect", "ellipse", "line",
                "polygon", "polyline", "path", "qrCode", "barcode",
                "templateUse", "conditional")) {
            assertTrue(BindingPolicyCatalog.allows(kind, "render"), kind);
            assertTrue(BindingPolicyCatalog.allows(kind, "visible"), kind);
            assertTrue(BindingPolicyCatalog.allows(kind, "opacity"), kind);
            assertTrue(BindingPolicyCatalog.allows(kind, "transform.rotationDeg"), kind);
            assertTrue(BindingPolicyCatalog.allows(kind, "placement.xMm"), kind);
            assertTrue(BindingPolicyCatalog.allows(kind, "placement.marginTopMm"), kind);
            assertTrue(BindingPolicyCatalog.allows(kind, "placement.alignSelf"), kind);
            assertTrue(BindingPolicyCatalog.allows(kind, "placement.row"), kind);
            assertTrue(BindingPolicyCatalog.allows(kind, "placement.rowSpan"), kind);
        }
        // Group keeps common/placement leaves but has no appearance or box-size identities.
        assertTrue(BindingPolicyCatalog.allows("group", "placement.xMm"));
        assertFalse(BindingPolicyCatalog.allows("group", "placement.widthMm"));
        assertFalse(BindingPolicyCatalog.allows("group", "placement.minWidthMm"));
        assertFalse(BindingPolicyCatalog.allows("group", "placement.rightInsetMm"));
        assertFalse(BindingPolicyCatalog.allows("group", "fill.color"));
        assertFalse(BindingPolicyCatalog.allows("group", "clipContent"));
    }

    @Test
    void exposesTheClosedValueTypeForEveryAuthorizedIdentity() {
        assertEquals("boolean", BindingPolicyCatalog.valueType("frame", "visible"));
        assertEquals("decimal", BindingPolicyCatalog.valueType(
                "frame", "transform.rotationDeg"));
        assertEquals("color", BindingPolicyCatalog.valueType("frame", "fill.color"));
        assertEquals("fontRef", BindingPolicyCatalog.valueType(
                "text", "runs[*].fontRef"));
        assertEquals("imageRef", BindingPolicyCatalog.valueType("image", "imageRef"));
        assertEquals("text", BindingPolicyCatalog.valueType("barcode", "format"));
        assertEquals(null, BindingPolicyCatalog.valueType("conditional", "condition"));
        for (var entry : BindingPolicyCatalog.ENTRIES) {
            assertNotNull(BindingPolicyCatalog.valueType(
                    entry.nodeKind(), entry.propertyPathPattern()), entry.toString());
        }
    }
}
