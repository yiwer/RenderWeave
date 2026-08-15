package cn.hbads.renderweave.inference.eval.visual.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class R5P2AuthorityTest {
    @Test
    void locksTheApprovedR5P2RouteToTheHistoricalNegativeTerminal() {
        var authority = R5P2Authority.load();

        assertEquals("spec-sha256:e33269e1faa04f21239a0e79d4346fc90439f142b26111b3764164f53ba7d902",
                authority.approvedSpecIdentity());
        assertEquals("4b756c52cbc2fd389d8ca34f4c4a65b1bc9615db", authority.baselineRevision());
        assertEquals("R5P_MEASUREMENT_INVALID", authority.history().effectiveTerminal());
        assertEquals("R5P2_AUTHORITY_LOCKED", authority.terminalCode());
        assertEquals(0, authority.externalProviderUsage().attempts());
        assertEquals(0, authority.externalProviderUsage().reservations());
        assertEquals(0, authority.externalProviderUsage().costMicrosCny());
        assertEquals(0, authority.apiKeyReads());
        assertDoesNotThrow(() -> authority.requireHistory(authority.history()));
        assertFalse(authority.allowsLiveOrJ1());
    }

    @Test
    void rejectsHistoricalEvidenceDriftAndOldIdentityReuse() {
        var authority = R5P2Authority.load();
        var exact = authority.history();

        assertCode("R5P2_HISTORICAL_TERMINAL_DRIFT",
                () -> authority.requireHistory(exact.withEffectiveTerminal("R5P_PAIRED_VIEW_NOT_QUALIFIED")));
        assertCode("R5P2_HISTORICAL_EVIDENCE_DRIFT",
                () -> authority.requireHistory(exact.withProducerReportSha256("0".repeat(64))));
        assertCode("R5P2_HISTORICAL_EVIDENCE_DRIFT",
                () -> authority.requireHistory(exact.withIndependentEvidenceSha256("0".repeat(64))));

        for (var identity : authority.prohibitedIdentityValues()) {
            assertCode("R5P2_HISTORICAL_IDENTITY_REUSED",
                    () -> authority.requireFreshR5P2Identity(identity));
        }
        assertDoesNotThrow(() -> authority.requireFreshR5P2Identity(
                "renderweave-r5p2-source-line-reconciliation/1.0"));
    }

    @Test
    void keepsTheHistoricalR5PAuthorityByteIdentityUntouched() {
        var historical = R5PairedProductViewAuthority.load();

        assertEquals("renderweave-r5p-authority/1.0:"
                        + "05958659a5ffc302e92f6cc6cda8b1efd868e2ec4fa7f92b0d63f821f843441d",
                historical.authorityIdentity());
        assertEquals("R5P_AUTHORITY_LOCKED", historical.terminalCode());
    }

    private static void assertCode(String expected, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(IllegalArgumentException.class, action);
        assertEquals(expected, failure.getMessage());
    }
}
