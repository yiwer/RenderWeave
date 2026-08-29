package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalError;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityValuesTest {

    private static final byte[] FIXED_NONCE = new byte[32];

    static {
        java.util.Arrays.fill(FIXED_NONCE, (byte) 1);
    }

    @Test
    void uniformDecimalMatchesFrozenHmacVector() {
        var value = CapabilityValues.uniformDecimal(
                FIXED_NONCE, "pos-1".getBytes(StandardCharsets.UTF_8));
        assertNotNull(value);
        assertEquals(0, new BigDecimal("0.737982028364641989").compareTo(value));
    }

    @Test
    void uniformDecimalIsDeterministicPerPosition() {
        var first = CapabilityValues.uniformDecimal(
                FIXED_NONCE, "pos-1".getBytes(StandardCharsets.UTF_8));
        var second = CapabilityValues.uniformDecimal(
                FIXED_NONCE, "pos-1".getBytes(StandardCharsets.UTF_8));
        var other = CapabilityValues.uniformDecimal(
                FIXED_NONCE, "pos-2".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, first.compareTo(second));
        assertNotEquals(0, first.compareTo(other));
    }

    @Test
    void uniformDecimalStaysWithinUnitInterval() {
        for (int index = 0; index < 20; index++) {
            var value = CapabilityValues.uniformDecimal(
                    FIXED_NONCE, ("pos-" + index).getBytes(StandardCharsets.UTF_8));
            assertNotNull(value);
            assertTrue(value.signum() >= 0);
            assertTrue(value.compareTo(BigDecimal.ONE) < 0);
        }
    }

    @Test
    void clockProjectionsUseFixedUtcWholeSecondFormats() {
        assertEquals("1970-01-01", CapabilityValues.utcDate(0));
        assertEquals("00:00:00", CapabilityValues.utcTime(0));
        assertEquals("2025-06-15", CapabilityValues.utcDate(1_750_000_000L));
        assertEquals("15:06:40", CapabilityValues.utcTime(1_750_000_000L));
    }

    @Test
    void establishTruncatesToWholeSecond() {
        var clock = Clock.fixed(
                Instant.ofEpochMilli(1_750_000_000_999L), ZoneOffset.UTC);
        var state = CapabilityValues.establish(clock, new SecureRandom());
        assertEquals(1_750_000_000L, state.clockEpochSecond());
        assertEquals(32, state.randomNonce().length);
    }

    @Test
    void providerSuppliesClockOperationsAndRejectsUnknown() {
        var values = CapabilityValues.forState(
                new CapabilityValues.CapabilityState(0, FIXED_NONCE));
        var provider = values.provider();

        var date = provider.supply("CLOCK", "UTC_DATE", new byte[] { 1 });
        assertEquals(new DesignValue.Date("1970-01-01"), ((EvalValue) date).value());

        var time = provider.supply("CLOCK", "UTC_TIME", new byte[] { 2 });
        assertEquals(new DesignValue.Time("00:00:00"), ((EvalValue) time).value());

        var random = provider.supply("RANDOM", "UNIFORM_DECIMAL_0_1", new byte[] { 3 });
        assertInstanceOf(EvalValue.class, random);

        var unknown = provider.supply("CLOCK", "NOPE", new byte[] { 4 });
        assertInstanceOf(EvalError.class, unknown);
    }

    @Test
    void demandLedgerFeedsResultDigest() {
        var values = CapabilityValues.forState(
                new CapabilityValues.CapabilityState(0, FIXED_NONCE));
        var provider = values.provider();
        var frame = CapabilityCallPosition.root(
                        "00000000-0000-4000-8000-0000000000a1", 1)
                .invocationFrame();
        provider.supply("CLOCK", "UTC_DATE", frame.canonicalBytes(
                "00000000-0000-4000-8000-0000000000d1",
                "date", "CLOCK", "UTC_DATE"));
        var afterOne = values.capabilityResultDigest();
        provider.supply("CLOCK", "UTC_TIME", frame.canonicalBytes(
                "00000000-0000-4000-8000-0000000000d1",
                "time", "CLOCK", "UTC_TIME"));
        var afterTwo = values.capabilityResultDigest();
        assertNotEquals(afterOne, afterTwo);
        assertTrue(afterTwo.startsWith("sha256:"));
        assertEquals(2, values.demands().size());
    }

    @Test
    void totalDemandTrackerAcceptsTheExactFrozenBoundaryThenFailsFirst() {
        var tracker = CapabilityBudget.frozen().newTracker();
        for (int index = 0; index < 4_096; index++) {
            assertNull(tracker.reserveDemand("CLOCK", 0));
        }
        for (int index = 0; index < 4_096; index++) {
            assertNull(tracker.reserveDemand("RANDOM", 0));
        }

        var exceeded = tracker.reserveDemand("CLOCK", 0);

        assertEquals("capabilityRuntime.totalDemands", exceeded.limitId());
    }

    @Test
    void clockDemandTrackerRejectsAtomicallyAtTheExactFrozenBoundary() {
        var tracker = CapabilityBudget.frozen().newTracker();
        for (int index = 0; index < 4_096; index++) {
            assertNull(tracker.reserveDemand("CLOCK", 0));
        }

        var clockExceeded = tracker.reserveDemand("CLOCK", 0);

        assertEquals("capabilityRuntime.clockDemands", clockExceeded.limitId());
        for (int index = 0; index < 4_096; index++) {
            assertNull(tracker.reserveDemand("RANDOM", 0));
        }
        assertEquals("capabilityRuntime.totalDemands",
                tracker.reserveDemand("RANDOM", 0).limitId());
    }

    @Test
    void randomDemandTrackerRejectsAtomicallyAtTheExactFrozenBoundary() {
        var tracker = CapabilityBudget.frozen().newTracker();
        for (int index = 0; index < 4_096; index++) {
            assertNull(tracker.reserveDemand("RANDOM", 0));
        }

        var randomExceeded = tracker.reserveDemand("RANDOM", 0);

        assertEquals("capabilityRuntime.randomDemands", randomExceeded.limitId());
        for (int index = 0; index < 4_096; index++) {
            assertNull(tracker.reserveDemand("CLOCK", 0));
        }
        assertEquals("capabilityRuntime.totalDemands",
                tracker.reserveDemand("CLOCK", 0).limitId());
    }

    @Test
    void positionBytesPerDemandRejectsWithoutPartiallyCommittingAnyCounter() {
        var tracker = CapabilityBudget.frozen().newTracker();

        var positionExceeded = tracker.reserveDemand("CLOCK", 2_049);

        assertEquals("capabilityRuntime.positionCanonicalBytesPerDemand",
                positionExceeded.limitId());
        for (int index = 0; index < 4_096; index++) {
            assertNull(tracker.reserveDemand("CLOCK", 2_048));
        }
        for (int index = 0; index < 4_096; index++) {
            assertNull(tracker.reserveDemand("RANDOM", 2_048));
        }
        assertEquals("capabilityRuntime.totalDemands",
                tracker.reserveDemand("RANDOM", 0).limitId());
    }

    @Test
    void positionBytesTotalRejectsWithoutPartiallyCommittingAnyCounter() {
        var tracker = CapabilityBudget.fromEffectiveVector(
                tightenedPositionTotalBudgetVector()).newTracker();
        assertNull(tracker.reserveDemand("CLOCK", 1));
        assertNull(tracker.reserveDemand("RANDOM", 2));

        var positionExceeded = tracker.reserveDemand("CLOCK", 1);

        assertEquals("capabilityRuntime.positionCanonicalBytesTotal",
                positionExceeded.limitId());
        assertNull(tracker.reserveDemand("CLOCK", 0));
        assertNull(tracker.reserveDemand("RANDOM", 0));
        assertEquals("capabilityRuntime.totalDemands",
                tracker.reserveDemand("RANDOM", 0).limitId());
    }

    @Test
    void resultDigestEmbedsCanonicalCallPositionObject() {
        var values = CapabilityValues.forState(
                new CapabilityValues.CapabilityState(0, FIXED_NONCE));
        var position = CapabilityCallPosition.root(
                        "00000000-0000-4000-8000-0000000000a1", 7)
                .invocationFrame()
                .canonicalBytes(
                        "00000000-0000-4000-8000-0000000000d1",
                        "today", "CLOCK", "UTC_DATE");

        values.provider().supply("CLOCK", "UTC_DATE", position);

        assertEquals("sha256:8b0960a385085e2a4d03cada5347867ea1193eec09e0128ff0c149501179d30a",
                values.capabilityResultDigest());
    }

    @Test
    void evaluationFingerprintIsStableAndSensitive() {
        var fingerprint = CapabilityValues.evaluationFingerprint(
                "owner-a", "auth-digest", "closure-digest", "input-digest",
                "renderweave-render/1.0", "renderweave-layout/1.0",
                "clock+random", "renderweave-asset-acceptance/1.0", "budget-vector");
        var same = CapabilityValues.evaluationFingerprint(
                "owner-a", "auth-digest", "closure-digest", "input-digest",
                "renderweave-render/1.0", "renderweave-layout/1.0",
                "clock+random", "renderweave-asset-acceptance/1.0", "budget-vector");
        var different = CapabilityValues.evaluationFingerprint(
                "owner-b", "auth-digest", "closure-digest", "input-digest",
                "renderweave-render/1.0", "renderweave-layout/1.0",
                "clock+random", "renderweave-asset-acceptance/1.0", "budget-vector");
        assertEquals(fingerprint, same);
        assertNotEquals(fingerprint, different);
        assertTrue(fingerprint.startsWith("sha256:"));
    }

    @Test
    void nonceIsNotExposedThroughStateCopies() {
        var state = new CapabilityValues.CapabilityState(5, FIXED_NONCE);
        var leaked = state.randomNonce();
        leaked[0] = 99;
        assertEquals((byte) 1, state.randomNonce()[0]);
        assertNull(null);
    }

    private static String tightenedPositionTotalBudgetVector() {
        return "{\"groups\":{\"capabilityRuntime\":{\"limits\":{"
                + "\"staticCapabilitySources\":4096,"
                + "\"totalDemands\":4,"
                + "\"clockDemands\":2,"
                + "\"randomDemands\":2,"
                + "\"positionCanonicalBytesPerDemand\":2048,"
                + "\"positionCanonicalBytesTotal\":3,"
                + "\"capabilityStateRecordBytes\":1048576,"
                + "\"resultDigestStreamingBytes\":16777216,"
                + "\"initializationAttempts\":3,"
                + "\"randomRejectionAttempts\":128}}}}";
    }
}
