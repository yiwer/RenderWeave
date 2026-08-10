package cn.hbads.renderweave.inference.profile;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualModelCapabilityRegistryTest {
    private static final String FLASH_RESOURCE =
            "inference-capabilities/dashscope-qwen37-flash-visual-v1.json";

    @Test
    void bindsOfficialPlusAndFlashLimitsButKeepsTheExactMaxAliasCanaryOnly() {
        var registry = new VisualModelCapabilityRegistry();

        assertEquals(Set.of("qwen3.7-flash", "qwen3.7-plus", "qwen3.8-max"), registry.models());
        var plus = registry.requireModel("qwen3.7-plus").capability();
        assertEquals(VisualModelCapability.VerificationBasis.OFFICIAL_DOCS_AND_N2_LIVE,
                plus.verificationBasis());
        assertEquals(250, plus.advertisedMaximumBase64Images());
        assertEquals(16_000_000, plus.advertisedMaximumImagePixels());
        assertEquals(64_000, plus.advertisedMaximumOutputTokens());
        assertEquals(16_000_000, plus.productMaximumNormalizedImagePixels());
        var max = registry.requireModel("qwen3.8-max").capability();
        assertEquals(VisualModelCapability.VerificationBasis.N2_EXACT_ALIAS_LIVE_ONLY,
                max.verificationBasis());
        assertNull(max.advertisedMaximumBase64Images());
        assertNull(max.advertisedMaximumImagePixels());
        assertNull(max.advertisedMaximumOutputTokens());
        assertTrue(max.sourceReferences().stream().anyMatch(value ->
                value.contains("visual-v4-baseline-qwen38-max-20260810")));
        assertThrows(IllegalArgumentException.class, () -> registry.requireModel(
                "qwen3.7-max-2026-06-08"
        ));
    }

    @Test
    void capabilityResourcesRejectUnknownDuplicateTrailingAndCoercedMembers() throws Exception {
        var original = new String(
                getClass().getClassLoader().getResourceAsStream(FLASH_RESOURCE).readAllBytes(),
                StandardCharsets.UTF_8
        );
        var invalid = java.util.List.of(
                original.replaceFirst("\\{", "{\"unknown\":true,"),
                original.replaceFirst(
                        "\"model\": \"qwen3.7-flash\"",
                        "\"model\": \"qwen3.7-flash\",\"model\":\"qwen3.7-plus\""
                ),
                original + "{}",
                original.replace("\"productMaximumImagesPerRequest\": 10",
                        "\"productMaximumImagesPerRequest\": \"10\""),
                original.replace("\"productMaximumNormalizedImagePixels\": 16000000",
                        "\"productMaximumNormalizedImagePixels\": 15999999"),
                original.replace("\"verificationBasis\": \"OFFICIAL_DOCS_AND_N2_LIVE\"",
                        "\"verificationBasis\": 0")
        );
        for (var value : invalid) {
            assertThrows(IllegalStateException.class, () ->
                    new VisualModelCapabilityRegistry(overriding(value))
            );
        }
    }

    private ClassLoader overriding(String flashJson) {
        var parent = getClass().getClassLoader();
        return new ClassLoader(parent) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (FLASH_RESOURCE.equals(name)) {
                    return new ByteArrayInputStream(flashJson.getBytes(StandardCharsets.UTF_8));
                }
                return super.getResourceAsStream(name);
            }
        };
    }
}
