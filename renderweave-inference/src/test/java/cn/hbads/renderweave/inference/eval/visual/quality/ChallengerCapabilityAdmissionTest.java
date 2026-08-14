package cn.hbads.renderweave.inference.eval.visual.quality;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengerCapabilityAdmissionTest {
    private static final String RESOURCE =
            "visual-eval/quality-repair/challenger-capabilities-v1.json";

    @Test
    void recordsSeparateLicenseAndSupplyChainDispositionsBeforeAnyExecution() {
        var catalog = ChallengerCapabilityAdmission.load();

        assertEquals(List.of("pp-structurev3", "tesseract-tsv-hocr"),
                catalog.challengers().stream().map(
                        ChallengerCapabilityAdmission.Capability::challengerId).toList());
        assertEquals(ChallengerCapabilityAdmission.OptionalThirdChallenger.NONE,
                catalog.optionalThirdChallenger());

        for (var capability : catalog.challengers()) {
            assertEquals(ChallengerCapabilityAdmission.AdmissionDisposition.NOT_ADMITTED,
                    capability.admissionDisposition());
            assertEquals(ChallengerCapabilityAdmission.LicenseDecision.J0_PENDING,
                    capability.codeLicense().decision());
            assertEquals(ChallengerCapabilityAdmission.LicenseDecision.J0_PENDING,
                    capability.weightLicense().decision());
            assertNotEquals(capability.codeLicense().evidenceReference(),
                    capability.weightLicense().evidenceReference());
            assertEquals(ChallengerCapabilityAdmission.RuntimeNetworkPolicy.DENY_ALL,
                    capability.runtimeNetworkPolicy());
            assertFalse(capability.runtimeDownloadAllowed());
            assertFalse(capability.executable());
            assertTrue(capability.missingAdmissionDimensions().contains("LICENSE_J1"));
            assertTrue(capability.identity().matches(
                    "renderweave-challenger-capability/1\\.0:[0-9a-f]{64}"));
            assertEquals("CHALLENGER_NOT_ADMITTED", assertThrows(IllegalStateException.class,
                    () -> catalog.executionPlan(capability.challengerId())).getMessage());
        }

        var pp = catalog.require("pp-structurev3");
        assertEquals(1, pp.priority());
        assertEquals("PP_STRUCTURE_V3", pp.adapterKind());
        var tesseract = catalog.require("tesseract-tsv-hocr");
        assertEquals("INDEPENDENT_CPU_BASELINE", tesseract.role());
        assertEquals("CPU_ONLY", tesseract.backend());
    }

    @Test
    void manifestHashNetworkAndResourceDriftFailClosed() throws Exception {
        var original = new String(getClass().getClassLoader()
                .getResourceAsStream(RESOURCE).readAllBytes(), StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () ->
                ChallengerCapabilityAdmission.load(overriding(original.replace(
                        "\"runtimeNetworkPolicy\": \"DENY_ALL\"",
                        "\"runtimeNetworkPolicy\": \"ALLOW_MODEL_DOWNLOAD\""))));
        assertThrows(IllegalStateException.class, () ->
                ChallengerCapabilityAdmission.load(overriding(original.replace(
                        "\"maximumPeakRamMiB\": 12288",
                        "\"maximumPeakRamMiB\": 12289"))));
        assertThrows(IllegalStateException.class, () ->
                ChallengerCapabilityAdmission.load(overriding(original.replaceFirst(
                        "[0-9a-f]{64}", "0".repeat(64)))));
    }

    private ClassLoader overriding(String manifest) {
        var parent = getClass().getClassLoader();
        return new ClassLoader(parent) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (RESOURCE.equals(name)) {
                    return new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8));
                }
                return super.getResourceAsStream(name);
            }
        };
    }
}
