package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.live.LiveInferenceWorker;
import cn.hbads.renderweave.inference.replay.ReplayInferenceWorker;
import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-node, two-lane dispatcher shared by replay and live inference.
 *
 * <p>The PostgreSQL queue remains authoritative across restarts. Replay is always eligible and
 * zero-network; live dequeue remains independently closed unless both deployment gates are open.
 * Exact replay calls wait on the same fair permits so HTTP traffic cannot bypass the global v1
 * concurrency bound.</p>
 */
final class InferenceCoordinator {
    static final int MAX_PARALLEL_RUNS = 2;

    private static final Logger LOG = LoggerFactory.getLogger(InferenceCoordinator.class);

    private final ReplayInferenceWorker replayWorker;
    private final LiveInferenceWorker liveWorker;
    private final boolean liveEnabled;
    private final boolean liveUploadEnabled;
    private final Semaphore lanes = new Semaphore(MAX_PARALLEL_RUNS, true);
    private final Semaphore recoveryTasks = new Semaphore(MAX_PARALLEL_RUNS);
    private final AtomicLong workerSequence = new AtomicLong();
    private final AtomicLong routeSequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("inference-dispatch-", 0).factory()
    );

    InferenceCoordinator(
            ReplayInferenceWorker replayWorker,
            LiveInferenceWorker liveWorker,
            boolean liveEnabled,
            boolean liveUploadEnabled
    ) {
        this.replayWorker = Objects.requireNonNull(replayWorker, "replayWorker");
        this.liveWorker = Objects.requireNonNull(liveWorker, "liveWorker");
        this.liveEnabled = liveEnabled;
        this.liveUploadEnabled = liveUploadEnabled;
    }

    boolean liveEnabled() {
        return liveEnabled;
    }

    boolean liveDispatchEnabled() {
        return liveEnabled && liveUploadEnabled;
    }

    Optional<InferenceRunSnapshot> processReplay(UUID runId, String workerId) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(workerId, "workerId");
        if (closed.get()) throw new IllegalStateException("Inference coordinator is closed");
        try {
            return executor.submit(() -> withLane(() -> replayWorker.process(runId, workerId))).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for replay inference", interrupted);
        } catch (ExecutionException failed) {
            var cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Replay inference failed", cause);
        }
    }

    /** Coalesces queue wakeups to at most two recovery tasks. */
    void kick() {
        if (closed.get()) return;
        for (var index = 0; index < MAX_PARALLEL_RUNS; index++) {
            if (!recoveryTasks.tryAcquire()) return;
            try {
                executor.submit(this::recoverOneSafely);
            } catch (RuntimeException rejected) {
                recoveryTasks.release();
                if (!closed.get()) throw rejected;
                return;
            }
        }
    }

    private void recoverOneSafely() {
        var processed = false;
        try {
            processed = withLane(this::processOneQueuedRun);
        } catch (RuntimeException failure) {
            LOG.error("Durable inference recovery attempt failed", failure);
        } finally {
            recoveryTasks.release();
        }
        if (processed && !closed.get()) kick();
    }

    private boolean processOneQueuedRun() {
        var workerId = "inference-worker-" + workerSequence.incrementAndGet();
        var liveFirst = (routeSequence.getAndIncrement() & 1L) == 1L;
        if (liveFirst && processOneLive(workerId)) return true;
        if (replayWorker.processNext(workerId).isPresent()) return true;
        return !liveFirst && processOneLive(workerId);
    }

    private boolean processOneLive(String workerId) {
        return liveDispatchEnabled() && liveWorker.processNext(workerId).isPresent();
    }

    private <T> T withLane(InterruptibleSupplier<T> action) {
        var acquired = false;
        try {
            lanes.acquire();
            acquired = true;
            return action.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Inference dispatch was interrupted", interrupted);
        } finally {
            if (acquired) lanes.release();
        }
    }

    @PreDestroy
    void close() {
        if (closed.compareAndSet(false, true)) executor.shutdown();
    }

    @FunctionalInterface
    private interface InterruptibleSupplier<T> {
        T get() throws InterruptedException;
    }
}
