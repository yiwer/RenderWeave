package cn.hbads.renderweave.rendering.internal;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Frozen Capability capacity policy parsed from the same effective budget vector bound into the
 * evaluation fingerprint. The module owns numeric admission and request-local reservations so
 * callers never coordinate counters or duplicate limit ordering.
 */
final class CapabilityBudget {

    private static final long MAX_STATIC_SOURCES = 4_096;
    private static final long MAX_TOTAL_DEMANDS = 8_192;
    private static final long MAX_CLOCK_DEMANDS = 4_096;
    private static final long MAX_RANDOM_DEMANDS = 4_096;
    private static final long MAX_POSITION_BYTES_PER_DEMAND = 2_048;
    private static final long MAX_POSITION_BYTES_TOTAL = 16_777_216;
    private static final long MAX_CAPABILITY_STATE_RECORD_BYTES = 1_048_576;
    private static final long MAX_RESULT_DIGEST_STREAMING_BYTES = 16_777_216;
    private static final long MAX_INITIALIZATION_ATTEMPTS = 3;
    private static final long MAX_RANDOM_REJECTION_ATTEMPTS = 128;

    private final Limits limits;

    private CapabilityBudget(Limits limits) {
        this.limits = limits;
    }

    static CapabilityBudget frozen() {
        return new CapabilityBudget(new Limits(
                MAX_STATIC_SOURCES,
                MAX_TOTAL_DEMANDS,
                MAX_CLOCK_DEMANDS,
                MAX_RANDOM_DEMANDS,
                MAX_POSITION_BYTES_PER_DEMAND,
                MAX_POSITION_BYTES_TOTAL,
                MAX_CAPABILITY_STATE_RECORD_BYTES,
                MAX_RESULT_DIGEST_STREAMING_BYTES,
                MAX_INITIALIZATION_ATTEMPTS));
    }

    static CapabilityBudget fromEffectiveVector(String effectiveBudgetVector) {
        Objects.requireNonNull(effectiveBudgetVector, "effectiveBudgetVector");
        var bytes = effectiveBudgetVector.getBytes(StandardCharsets.UTF_8);
        var parsed = RenderJsonParser.parse(bytes, new RenderJsonParser.JsonBudget(
                "effectiveBudgetVector", 4 * 1024 * 1024L, 32,
                1_024, 10_000, 100_000, 1_048_576, 256));
        if (!(parsed instanceof RenderJsonParser.Parsed document)
                || !(document.value() instanceof RenderJson.ObjectValue root)) {
            throw invalidVector();
        }
        var groups = objectMember(root, "groups");
        var capabilityRuntime = objectMember(groups, "capabilityRuntime");
        var limits = objectMember(capabilityRuntime, "limits");
        exactLimit(limits, "randomRejectionAttempts", MAX_RANDOM_REJECTION_ATTEMPTS);
        return new CapabilityBudget(new Limits(
                limit(limits, "staticCapabilitySources", MAX_STATIC_SOURCES),
                limit(limits, "totalDemands", MAX_TOTAL_DEMANDS),
                limit(limits, "clockDemands", MAX_CLOCK_DEMANDS),
                limit(limits, "randomDemands", MAX_RANDOM_DEMANDS),
                limit(limits, "positionCanonicalBytesPerDemand",
                        MAX_POSITION_BYTES_PER_DEMAND),
                limit(limits, "positionCanonicalBytesTotal", MAX_POSITION_BYTES_TOTAL),
                limit(limits, "capabilityStateRecordBytes",
                        MAX_CAPABILITY_STATE_RECORD_BYTES),
                limit(limits, "resultDigestStreamingBytes",
                        MAX_RESULT_DIGEST_STREAMING_BYTES),
                limit(limits, "initializationAttempts", MAX_INITIALIZATION_ATTEMPTS)));
    }

    LimitExceeded admitStaticSources(long sourceCount) {
        if (sourceCount > limits.staticSources()) {
            return new LimitExceeded("capabilityRuntime.staticCapabilitySources");
        }
        return null;
    }

    LimitExceeded admitStateRecord(long recordBytes) {
        if (recordBytes > limits.capabilityStateRecordBytes()) {
            return new LimitExceeded("capabilityRuntime.capabilityStateRecordBytes");
        }
        return null;
    }

    Tracker newTracker() {
        return new Tracker(limits);
    }

    InitializationAttempts newInitializationAttempts() {
        return new InitializationAttempts(limits.initializationAttempts());
    }

    private static RenderJson.ObjectValue objectMember(
            RenderJson.ObjectValue parent, String member) {
        if (parent.members().get(member) instanceof RenderJson.ObjectValue object) {
            return object;
        }
        throw invalidVector();
    }

    private static long limit(RenderJson.ObjectValue limits, String member, long frozenMaximum) {
        if (!(limits.members().get(member) instanceof RenderJson.NumberValue number)) {
            throw invalidVector();
        }
        final long value;
        try {
            value = Long.parseLong(number.rawToken());
        } catch (NumberFormatException invalid) {
            throw invalidVector();
        }
        if (value < 0 || value > frozenMaximum) {
            throw invalidVector();
        }
        if (!Long.toString(value).equals(number.rawToken())) {
            throw invalidVector();
        }
        return value;
    }

    private static long exactLimit(
            RenderJson.ObjectValue limits,
            String member,
            long frozenValue
    ) {
        var value = limit(limits, member, frozenValue);
        if (value != frozenValue) {
            throw invalidVector();
        }
        return value;
    }

    private static IllegalArgumentException invalidVector() {
        return new IllegalArgumentException("effective capability budget vector is invalid");
    }

    record LimitExceeded(String limitId) {
        LimitExceeded {
            Objects.requireNonNull(limitId, "limitId");
        }
    }

    static final class Tracker {
        private final Limits limits;
        private long totalDemands;
        private long clockDemands;
        private long randomDemands;
        private long positionBytes;
        private long resultDigestBytes;

        private Tracker(Limits limits) {
            this.limits = limits;
        }

        synchronized LimitExceeded reserveDemand(String capability, long canonicalPositionBytes) {
            Objects.requireNonNull(capability, "capability");
            if (wouldExceed(totalDemands, 1, limits.totalDemands())) {
                return exceeded("totalDemands");
            }
            if ("CLOCK".equals(capability)
                    && wouldExceed(clockDemands, 1, limits.clockDemands())) {
                return exceeded("clockDemands");
            }
            if ("RANDOM".equals(capability)
                    && wouldExceed(randomDemands, 1, limits.randomDemands())) {
                return exceeded("randomDemands");
            }
            if (canonicalPositionBytes > limits.positionBytesPerDemand()) {
                return exceeded("positionCanonicalBytesPerDemand");
            }
            if (wouldExceed(positionBytes, canonicalPositionBytes, limits.positionBytesTotal())) {
                return exceeded("positionCanonicalBytesTotal");
            }
            totalDemands++;
            if ("CLOCK".equals(capability)) {
                clockDemands++;
            } else if ("RANDOM".equals(capability)) {
                randomDemands++;
            } else {
                throw new IllegalArgumentException("unknown capability");
            }
            positionBytes += canonicalPositionBytes;
            return null;
        }

        synchronized LimitExceeded reserveResultDigestBytes(long framedBytes) {
            if (wouldExceed(
                    resultDigestBytes, framedBytes, limits.resultDigestStreamingBytes())) {
                return exceeded("resultDigestStreamingBytes");
            }
            resultDigestBytes += framedBytes;
            return null;
        }

        private static boolean wouldExceed(long current, long increment, long maximum) {
            if (increment < 0) {
                throw new IllegalArgumentException("capacity increment must not be negative");
            }
            return current > maximum || increment > maximum - current;
        }

        private static LimitExceeded exceeded(String suffix) {
            return new LimitExceeded("capabilityRuntime." + suffix);
        }
    }

    static final class InitializationAttempts {
        private final long maximum;
        private long attempts;

        private InitializationAttempts(long maximum) {
            this.maximum = maximum;
        }

        synchronized LimitExceeded reserve() {
            if (attempts >= maximum) {
                return new LimitExceeded("capabilityRuntime.initializationAttempts");
            }
            attempts++;
            return null;
        }
    }

    private record Limits(
            long staticSources,
            long totalDemands,
            long clockDemands,
            long randomDemands,
            long positionBytesPerDemand,
            long positionBytesTotal,
            long capabilityStateRecordBytes,
            long resultDigestStreamingBytes,
            long initializationAttempts
    ) {
    }
}
