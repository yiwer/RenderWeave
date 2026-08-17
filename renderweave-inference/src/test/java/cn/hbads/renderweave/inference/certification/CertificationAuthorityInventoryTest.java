package cn.hbads.renderweave.inference.certification;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertificationAuthorityInventoryTest {
    private static final Set<String> PROHIBITED = Set.of(
            "n7",
            "r5-v2",
            "r5p-v1",
            "r5p2",
            "historical-v45-j1",
            "historical-v45-ledger",
            "n7-closeout-evidence"
    );

    @Test
    void canonicalInventoryKeepsHistoricalAuthorityClosedAndProviderZero() {
        var inventory = CertificationAuthorityInventory.loadCanonical();

        assertEquals("ACTIVE_EXPERIMENTAL",
                inventory.require("dashscope-qwen38-max-product-v45-hybrid-generic").lifecycle());
        assertEquals(Set.of(
                "document-observation-ir/1.0",
                "n9-r1-evaluator/1.0",
                "production-admission-decisions-01-08-14",
                "trial-signal-2026-08-17"
        ), inventory.reusableReferenceIds());
        assertEquals(0, inventory.providerAccounting().attempts());
        assertEquals(0, inventory.providerAccounting().reservations());
        assertEquals(0, inventory.providerAccounting().costMicrosCny());
        assertEquals(0, inventory.providerAccounting().apiKeyReads());
        assertTrue(inventory.canonicalSha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void everyClosedHistoricalRouteFailsWithOneTypedReason() {
        var inventory = CertificationAuthorityInventory.loadCanonical();

        for (var referenceId : PROHIBITED) {
            var failure = assertThrows(CertificationAuthorityViolation.class,
                    () -> inventory.requireReusable(referenceId));
            assertEquals("IMAGE_ONLY_CERTIFICATION_REFERENCE_PROHIBITED", failure.reasonCode());
            assertEquals(referenceId, failure.referenceId());
            assertEquals("CLOSED", inventory.require(referenceId).lifecycle());
        }
    }

    @Test
    void strictParserRejectsUnknownPropertiesAndDuplicateKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> CertificationAuthorityInventory.parse("{\"version\":\"x\",\"unknown\":true}".getBytes()));
        assertThrows(IllegalArgumentException.class,
                () -> CertificationAuthorityInventory.parse("{\"version\":\"x\",\"version\":\"y\"}".getBytes()));
    }
}
