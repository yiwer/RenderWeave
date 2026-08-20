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
    private final cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime externalRuntime;
    private final List<DemandEntry> demands = new ArrayList<>();

    private CapabilityValues(CapabilityState state) {
        this.state = state;
        this.externalRuntime = null;
    }

    private CapabilityValues(
            cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime externalRuntime) {
        this.state = null;
        this.externalRuntime = externalRuntime;
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

    /** 包装外部 spi runtime：demand 记账与 result digest 留在 Rendering 内部。 */
    static CapabilityValues wrapping(
            cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime runtime) {
        return new CapabilityValues(runtime);
    }

    record DemandEntry(String capability, String operation, byte[] callPosition, DesignValue result) {
    }

    DefinitionEngine.CapabilityProvider provider() {
        return this::supply;
    }

    private EvalOutcome supply(String capability, String operation, byte[] callPosition) {
        if (externalRuntime != null) {
            var outcome = externalRuntime.supply(capability, operation, callPosition);
            if (outcome
                    instanceof cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.ProviderUnavailable) {
                return new EvalError(new RuntimeFailure(RuntimeFailureKind.TYPE_FAULT, null));
            }
            var value = ((cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.Supplied) outcome).value();
            return record(toDesignValue(value), capability, operation, callPosition);
        }
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

    private static DesignValue toDesignValue(
            cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.CapabilityValue value) {
        if (value instanceof cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.TextResult text) {
            return new DesignValue.Text(text.value());
        }
        if (value instanceof cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.DecimalResult decimal) {
            return new DesignValue.Decimal(decimal.value());
        }
        if (value instanceof cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.DateResult date) {
            return new DesignValue.Date(date.value());
        }
        if (value instanceof cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.TimeResult time) {
            return new DesignValue.Time(time.value());
        }
        throw new IllegalStateException("unknown capability value");
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

    /**
     * capability result digest（冻结票据 14 §91）：只有成功 Evaluation 才按实际 demand
     * encounter order，对每项 closed {capabilityContractId, operation, callPosition,
     * outputType, result} 以 canonical JSON 编码并前置 uint64be(entryBytes.length) 分帧，
     * domain-separated SHA-256。
     *
     * <p>T21 边界：callPosition 使用 positionVersion + 请求内 demand 位置字节的简化
     * canonical object；完整 OccurrencePath 语义（ROOT/TEMPLATE_USE/REPEAT 段）随后续
     * 求值硬化票物化，届时向量一同升级。
     */
    String capabilityResultDigest() {
        var framed = new java.io.ByteArrayOutputStream();
        for (var demand : demands) {
            var entry = canonicalEntry(demand).getBytes(StandardCharsets.UTF_8);
            framed.writeBytes(lengthFrame(entry.length));
            framed.writeBytes(entry);
        }
        return RenderingDigests.digestWithDomain(RESULTS_DOMAIN, framed.toByteArray());
    }

    List<DemandEntry> demands() {
        return List.copyOf(demands);
    }

    private static byte[] lengthFrame(int length) {
        var frame = new byte[8];
        var value = (long) length;
        for (int index = 7; index >= 0; index--) {
            frame[index] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return frame;
    }

    private static String canonicalEntry(DemandEntry demand) {
        var members = new java.util.TreeMap<String, String>();
        members.put("capabilityContractId", CanonicalJson.string(contractId(demand.capability())));
        members.put("operation", CanonicalJson.string(demand.operation()));
        members.put("callPosition", CanonicalJson.string(
                Base64.getEncoder().encodeToString(demand.callPosition())));
        members.put("outputType", CanonicalJson.string(outputType(demand.result())));
        members.put("result", resultCanonical(demand.result()));
        return CanonicalJson.object(members);
    }

    private static String contractId(String capability) {
        return switch (capability) {
            case "CLOCK" -> "renderweave-capability-clock/1.0";
            case "RANDOM" -> "renderweave-capability-random/1.0";
            default -> throw new IllegalStateException("unknown capability " + capability);
        };
    }

    private static String outputType(DesignValue value) {
        if (value instanceof DesignValue.Date) {
            return "date";
        }
        if (value instanceof DesignValue.Time) {
            return "time";
        }
        if (value instanceof DesignValue.Decimal) {
            return "decimal";
        }
        throw new IllegalStateException("capability results are date/time/decimal");
    }

    private static String resultCanonical(DesignValue value) {
        if (value instanceof DesignValue.Date date) {
            return CanonicalJson.string(date.value());
        }
        if (value instanceof DesignValue.Time time) {
            return CanonicalJson.string(time.value());
        }
        if (value instanceof DesignValue.Decimal decimal) {
            return CanonicalJson.decimal(decimal.value());
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
        var members = new java.util.TreeMap<String, String>();
        members.put("admittedInputDigest", CanonicalJson.string(admittedInputDigest));
        members.put("assetAcceptanceProfile", CanonicalJson.string(assetAcceptanceProfile));
        members.put("authorizationContextDigest", CanonicalJson.string(authorizationContextDigest));
        members.put("capabilityContracts", CanonicalJson.string(capabilityContracts));
        members.put("closureDigest", CanonicalJson.string(closureDigest));
        members.put("effectiveBudgetVector", CanonicalJson.string(effectiveBudgetVector));
        members.put("layoutProfile", CanonicalJson.string(layoutProfile));
        members.put("ownerScope", CanonicalJson.string(ownerScope));
        members.put("renderDslVersion", CanonicalJson.string(renderDslVersion));
        return RenderingDigests.digestWithDomain(
                FINGERPRINT_DOMAIN,
                CanonicalJson.object(members).getBytes(StandardCharsets.UTF_8));
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
