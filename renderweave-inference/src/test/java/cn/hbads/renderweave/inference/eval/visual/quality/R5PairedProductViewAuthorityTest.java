package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.live.R5ProductTransformEvaluation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class R5PairedProductViewAuthorityTest {
    @Test
    void locksTheApprovedSuccessorAgainstTheClosedHistoricalRoutes() {
        var authority = R5PairedProductViewAuthority.load();

        assertEquals("spec-sha256:650ad1632347592d1fc34325983744c02563b43d8a565b9b1cd24e1a805a892a",
                authority.approvedSpecIdentity());
        assertEquals("57be4d9b249c0aa06a1c0b32abc634c152a97234", authority.baselineRevision());
        assertEquals("renderweave-r5-product-transform-authority/1.1:"
                        + "a6ef7ee0820ea906cb371371d66a8eaef3ba77ac569ae24d6e4935e144ef4475",
                authority.oldR5AuthorityIdentity());
        assertEquals("R5P_AUTHORITY_LOCKED", authority.terminalCode());
        assertEquals(0, authority.externalProviderUsage().attempts());
        assertEquals(0, authority.externalProviderUsage().reservations());
        assertEquals(0, authority.externalProviderUsage().costMicrosCny());
        assertEquals(0, authority.apiKeyReads());
        assertDoesNotThrow(() -> authority.requireHistoricalState(authority.historicalState()));
        assertFalse(authority.allowsLiveOrJ1());
    }

    @Test
    void rejectsEveryHistoricalStateDriftAndIdentityReuse() {
        var authority = R5PairedProductViewAuthority.load();
        var exact = authority.historicalState();

        assertCode("R5P_N7_AUTHORITY_STATE_DRIFT", () -> authority.requireHistoricalState(
                exact.withN704Decision(R5PairedProductViewAuthority.N7Decision.PASS)));
        assertCode("R5P_N7_AUTHORITY_STATE_DRIFT", () -> authority.requireHistoricalState(
                exact.withN704AuthorizationStatus(R5PairedProductViewAuthority.AuthorizationStatus.OPEN)));
        assertCode("R5P_OLD_R5_AUTHORITY_DRIFT", () -> authority.requireHistoricalState(
                exact.withOldR5AuthoritySha256("0".repeat(64))));
        assertCode("R5P_OLD_R5_RUNNER_REOPENED", () -> authority.requireHistoricalState(
                exact.withOldR5RunnerDisposition("OPEN")));
        assertCode("R5P_OLD_R5_RUNNER_REOPENED", () -> authority.requireHistoricalState(
                exact.withOldR5RunnerSourceSha256("0".repeat(64))));

        for (var identity : authority.prohibitedIdentityValues()) {
            assertCode("R5P_HISTORICAL_IDENTITY_REUSED",
                    () -> authority.requireFreshSuccessorIdentity(identity));
        }
        assertDoesNotThrow(() -> authority.requireFreshSuccessorIdentity("R5P-01"));
    }

    @Test
    void theOldRunnerRemainsClosedBeforeAnyAcquisition() {
        var acquisitions = new AtomicInteger();

        var failure = assertThrows(IllegalStateException.class,
                () -> new R5ProductTransformEvaluation().evaluate(runOrdinal -> {
                    acquisitions.incrementAndGet();
                    throw new AssertionError("closed runner must not acquire");
                }));

        assertEquals("R5_PRODUCT_TRANSFORM_ROUTE_CLOSED", failure.getMessage());
        assertEquals(0, acquisitions.get());
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(IllegalArgumentException.class, action);
        assertEquals(code, failure.getMessage());
    }
}
