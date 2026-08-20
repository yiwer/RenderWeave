package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalError;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalOutcome;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.EvalValue;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.RuntimeFailure;
import cn.hbads.renderweave.rendering.internal.ExpressionEvaluator.RuntimeFailureKind;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluation Capabilities 物化（冻结票据 14）：单一 UTC 整秒 Clock snapshot、server-only
 * 256-bit Random nonce、exact HMAC/rejection 派生（
 * {@code HMAC-SHA-256(nonce, domain || u64be(|pos|) || pos || u32be(counter))}，
 * M=10^18，128 次 rejection 后 CAPABILITY_RESULT_INVALID）、demand 记账与
 * capability result digest。nonce 永不进入 RenderDocument/digest/日志。
 */
final class CapabilityValues {

    static final String RANDOM_DOMAIN = "renderweave-capability-random-uniform-decimal/1\0";
    static final String RESULTS_DOMAIN = "renderweave-capability-results/1\0";
    static final String FINGERPRINT_DOMAIN = "renderweave-evaluation-fingerprint/1";
    static final int MAX_REJECTION_ATTEMPTS = 128;

    private static final BigInteger M = BigInteger.TEN.pow(18);
    private static final BigInteger LIMIT = BigInteger.TWO.pow(256).divide(M).multiply(M);
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    /** 不可变 CapabilityState：请求级 Clock snapshot + server-only Random nonce。 */
    record CapabilityState(long clockEpochSecond, byte[] randomNonce) {
        CapabilityState {
            Objects.requireNonNull(randomNonce, "randomNonce");
            if (randomNonce.length != 32) {
                throw new IllegalArgumentException("random nonce must be 256-bit");
            }
            randomNonce = randomNonce.clone();
        }

        public byte[] randomNonce() {
            return randomNonce.clone();
        }
    }

    private final CapabilityState state;
    private final List<DemandEntry> demands = new ArrayList<>();

    private CapabilityValues(CapabilityState state) {
        this.state = state;
    }

    static CapabilityState establish(Clock clock, SecureRandom entropy) {
        var second = Instant.now(clock).getEpochSecond();
        var nonce = new byte[32];
        entropy.nextBytes(nonce);
        return new CapabilityState(second, nonce);
    }

    static CapabilityValues forState(CapabilityState state) {
        return new CapabilityValues(state);
    }

    record DemandEntry(String capability, String operation, byte[] callPosition, DesignValue result) {
    }

    DefinitionEngine.CapabilityProvider provider() {
        return this::supply;
    }

    private EvalOutcome supply(String capability, String operation, byte[] callPosition) {
        return switch (capability + "/" + operation) {
            case "CLOCK/UTC_DATE" -> record(new DesignValue.Date(utcDate(state.clockEpochSecond())),
                    capability, operation, callPosition);
            case "CLOCK/UTC_TIME" -> record(new DesignValue.Time(utcTime(state.clockEpochSecond())),
                    capability, operation, callPosition);
            case "RANDOM/UNIFORM_DECIMAL_0_1" -> {
                var derived = uniformDecimal(state.randomNonce(), callPosition);
                if (derived == null) {
                    yield new EvalError(new RuntimeFailure(
                            RuntimeFailureKind.DECIMAL_LIMIT_EXCEEDED,
                            "capabilityRuntime.randomRejectionAttempts"));
                }
                yield record(new DesignValue.Decimal(derived), capability, operation, callPosition);
            }
            default -> new EvalError(new RuntimeFailure(RuntimeFailureKind.TYPE_FAULT, null));
        };
    }

    private EvalOutcome record(DesignValue result, String capability, String operation,
                               byte[] callPosition) {
        demands.add(new DemandEntry(capability, operation, callPosition.clone(), result));
        return new EvalValue(result);
    }

    static String utcDate(long epochSecond) {
        return DATE_FORMAT.format(Instant.ofEpochSecond(epochSecond));
    }

    static String utcTime(long epochSecond) {
        return TIME_FORMAT.format(Instant.ofEpochSecond(epochSecond));
    }

    /** exact 派生：x &lt; limit 时 k = x mod M，结果 k/10^18；128 次 rejection 后返回 null。 */
    static BigDecimal uniformDecimal(byte[] nonce, byte[] positionBytes) {
        var domain = RANDOM_DOMAIN.getBytes(StandardCharsets.UTF_8);
        var lengthPrefix = BigInteger.valueOf(positionBytes.length).toByteArray();
        var lengthBytes = new byte[8];
        System.arraycopy(lengthPrefix, Math.max(0, lengthPrefix.length - 8), lengthBytes,
                8 - Math.min(8, lengthPrefix.length), Math.min(8, lengthPrefix.length));
        for (int counter = 0; counter < MAX_REJECTION_ATTEMPTS; counter++) {
            var counterBytes = new byte[] {
                    0, 0, (byte) (counter >>> 8), (byte) counter
            };
            var data = concat(domain, lengthBytes, positionBytes, counterBytes);
            var mac = RenderingDigests.hmacSha256(nonce, data);
            var x = new BigInteger(1, mac);
            if (x.compareTo(LIMIT) < 0) {
                var k = x.mod(M);
                return new BigDecimal(k, 18);
            }
        }
        return null;
    }

    /** capability result digest：demand 顺序 framed entries 的 domain-separated SHA-256。 */
    String capabilityResultDigest() {
        var framed = new java.io.ByteArrayOutputStream();
        for (var demand : demands) {
            var entry = canonicalEntry(demand);
            framed.writeBytes(entry.getBytes(StandardCharsets.UTF_8));
        }
        return RenderingDigests.digestWithDomain(RESULTS_DOMAIN, framed.toByteArray());
    }

    List<DemandEntry> demands() {
        return List.copyOf(demands);
    }

    private static String canonicalEntry(DemandEntry demand) {
        var resultWire = resultCanonical(demand.result());
        return "{\"callPosition\":\""
                + Base64.getEncoder().encodeToString(demand.callPosition())
                + "\",\"capability\":\"" + demand.capability()
                + "\",\"operation\":\"" + demand.operation()
                + "\",\"result\":" + resultWire + "}";
    }

    private static String resultCanonical(DesignValue value) {
        if (value instanceof DesignValue.Text text) {
            return "\"" + text.value() + "\"";
        }
        if (value instanceof DesignValue.Date date) {
            return "\"" + date.value() + "\"";
        }
        if (value instanceof DesignValue.Time time) {
            return "\"" + time.value() + "\"";
        }
        if (value instanceof DesignValue.Decimal decimal) {
            return decimal.value().toPlainString();
        }
        throw new IllegalStateException("capability results are date/time/decimal");
    }

    /**
     * pre-execution evaluationFingerprint：closed 输入集合的 canonical JSON 作 domain-separated
     * SHA-256（renderweave-evaluation-fingerprint/1）。
     */
    static String evaluationFingerprint(
            String ownerScope,
            String authorizationContextDigest,
            String closureDigest,
            String admittedInputDigest,
            String renderDslVersion,
            String layoutProfile,
            String capabilityContracts,
            String assetAcceptanceProfile,
            String effectiveBudgetVector
    ) {
        var members = new LinkedHashMap<String, String>();
        members.put("admittedInputDigest", admittedInputDigest);
        members.put("assetAcceptanceProfile", assetAcceptanceProfile);
        members.put("authorizationContextDigest", authorizationContextDigest);
        members.put("capabilityContracts", capabilityContracts);
        members.put("closureDigest", closureDigest);
        members.put("effectiveBudgetVector", effectiveBudgetVector);
        members.put("layoutProfile", layoutProfile);
        members.put("ownerScope", ownerScope);
        members.put("renderDslVersion", renderDslVersion);
        var builder = new StringBuilder("{");
        boolean first = true;
        for (var entry : members.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(entry.getKey()).append("\":\"")
                    .append(entry.getValue()).append('"');
        }
        builder.append('}');
        return RenderingDigests.digestWithDomain(
                FINGERPRINT_DOMAIN, builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (var part : parts) {
            length += part.length;
        }
        var out = new byte[length];
        int offset = 0;
        for (var part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
