package cn.hbads.renderweave.rendering.internal;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Request-local monotonic control for the Evaluation + document-seal phase. */
final class EvaluationStageControl {

    private static final EvaluationStageControl UNBOUNDED =
            new EvaluationStageControl(() -> 0L, 0L, false);

    private final LongSupplier monotonicNanos;
    private final long deadlineAtMonotonicNanos;
    private final boolean bounded;

    private EvaluationStageControl(
            LongSupplier monotonicNanos,
            long deadlineAtMonotonicNanos,
            boolean bounded
    ) {
        this.monotonicNanos = monotonicNanos;
        this.deadlineAtMonotonicNanos = deadlineAtMonotonicNanos;
        this.bounded = bounded;
    }

    static EvaluationStageControl start(
            LongSupplier monotonicNanos,
            long durationMillis
    ) {
        Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        if (durationMillis <= 0) {
            throw new IllegalArgumentException("durationMillis must be positive");
        }
        var durationNanos = Math.multiplyExact(durationMillis, 1_000_000L);
        return new EvaluationStageControl(
                monotonicNanos,
                monotonicNanos.getAsLong() + durationNanos,
                true);
    }

    static EvaluationStageControl unbounded() {
        return UNBOUNDED;
    }

    boolean deadlineExceeded() {
        // Signed subtraction is wrap-safe for System.nanoTime intervals below 2^63 ns.
        return bounded && deadlineAtMonotonicNanos - monotonicNanos.getAsLong() <= 0;
    }

    void checkpoint() {
        if (deadlineExceeded()) {
            throw DeadlineExceeded.INSTANCE;
        }
    }

    static final class DeadlineExceeded extends RuntimeException {
        private static final DeadlineExceeded INSTANCE = new DeadlineExceeded();

        private DeadlineExceeded() {
            super(null, null, false, false);
        }
    }
}
