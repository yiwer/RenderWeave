package cn.hbads.renderweave.rendering.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DiagnosticSidecarTest {

    private static final String OCCURRENCE_ID = "rwocc_0000000000000001";
    private static final String RESOURCE_ID = "rwres_" + "a".repeat(64);
    private static final String ASSET_ID = "00000000-0000-4000-8000-0000000000a3";
    private static final String BINDING_ID = "00000000-0000-4000-8000-0000000000b4";
    private static final String DEFINITION_ID = "00000000-0000-4000-8000-0000000000d5";

    @Test
    void full_occurrence_path_disambiguates_nested_repeat_and_template_use() {
        var projected = DiagnosticSidecar.project(
                sidecar(),
                Optional.of(OCCURRENCE_ID),
                Optional.of(RESOURCE_ID),
                templateId -> DiagnosticSidecar.TemplateSegmentDisclosure.READABLE,
                DiagnosticSidecar.AssetIdentityDisclosure.READABLE);

        var location = assertInstanceOf(DiagnosticSidecar.Projected.class, projected)
                .safeLocation().orElseThrow();
        assertEquals(
                "/templates/root~1template/revisions/7/repeats/rows/items/3/uses/use~0child"
                        + "/templates/child-template/revisions/2/nodes/text~1node~0a"
                        + "/bindings/" + BINDING_ID
                        + "/definitions/" + DEFINITION_ID
                        + "/properties/runs/0/fontRef/assets/" + ASSET_ID,
                location);
    }

    @Test
    void asset_identity_requires_explicit_asset_read_disclosure() {
        var projected = DiagnosticSidecar.project(
                sidecar(),
                Optional.of(OCCURRENCE_ID),
                Optional.of(RESOURCE_ID),
                templateId -> DiagnosticSidecar.TemplateSegmentDisclosure.READABLE,
                DiagnosticSidecar.AssetIdentityDisclosure.REDACTED);

        var location = assertInstanceOf(DiagnosticSidecar.Projected.class, projected)
                .safeLocation().orElseThrow();
        assertEquals(
                "/templates/root~1template/revisions/7/repeats/rows/items/3/uses/use~0child"
                        + "/templates/child-template/revisions/2/nodes/text~1node~0a"
                        + "/bindings/" + BINDING_ID
                        + "/definitions/" + DEFINITION_ID
                        + "/properties/runs/0/fontRef",
                location);
    }

    @Test
    void unreadable_child_segment_redacts_child_and_every_descendant_locator() {
        var projected = DiagnosticSidecar.project(
                sidecar(),
                Optional.of(OCCURRENCE_ID),
                Optional.of(RESOURCE_ID),
                templateId -> "child-template".equals(templateId)
                        ? DiagnosticSidecar.TemplateSegmentDisclosure.REDACTED
                        : DiagnosticSidecar.TemplateSegmentDisclosure.READABLE,
                DiagnosticSidecar.AssetIdentityDisclosure.READABLE);

        var location = assertInstanceOf(DiagnosticSidecar.Projected.class, projected)
                .safeLocation().orElseThrow();
        assertEquals(
                "/templates/root~1template/revisions/7/repeats/rows/items/3/uses/use~0child",
                location);
    }

    @Test
    void structurally_empty_occurrence_path_fails_closed() {
        var malformed = new String(sidecar(), StandardCharsets.UTF_8)
                .replace(occurrencePath(), "{}").getBytes(StandardCharsets.UTF_8);

        assertInstanceOf(
                DiagnosticSidecar.InvalidProjection.class,
                DiagnosticSidecar.project(
                        malformed,
                        Optional.of(OCCURRENCE_ID),
                        Optional.of(RESOURCE_ID),
                        templateId -> DiagnosticSidecar.TemplateSegmentDisclosure.READABLE));
    }

    private static byte[] sidecar() {
        return ("{\"occurrences\":[{\"occurrenceId\":\"" + OCCURRENCE_ID
                + "\",\"occurrencePath\":" + occurrencePath()
                + ",\"sourceNodeId\":\"text/node~a\"}],\"resources\":[{"
                + "\"assetId\":\"" + ASSET_ID + "\",\"bindingId\":\"" + BINDING_ID
                + "\",\"consumerPropertyRef\":{\"rootPropertyId\":\"runs\",\"selectors\":["
                + "{\"index\":0,\"kind\":\"INDEX\"},{\"kind\":\"MEMBER\","
                + "\"memberId\":\"fontRef\"}]},\"definitionId\":\"" + DEFINITION_ID
                + "\",\"occurrenceId\":\"" + OCCURRENCE_ID
                + "\",\"resourceId\":\"" + RESOURCE_ID + "\"}],"
                + "\"sidecarVersion\":\"renderweave-diagnostic-sidecar/1.0\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String occurrencePath() {
        return "{\"pathVersion\":\"renderweave-occurrence-path/1.0\",\"segments\":["
                + "{\"kind\":\"ROOT\",\"revision\":7,\"templateId\":\"root/template\"},"
                + "{\"inputIndex\":3,\"kind\":\"REPEAT\",\"loopId\":\"rows\"},"
                + "{\"kind\":\"TEMPLATE_USE\",\"revision\":2,"
                + "\"templateId\":\"child-template\",\"useId\":\"use~child\"},"
                + "{\"kind\":\"NODE\",\"nodeId\":\"text/node~a\","
                + "\"role\":\"source-node\"}]}";
    }
}
