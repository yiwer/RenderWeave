package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.live.LiveInferenceWorker;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded two-lane dispatcher; the durable queue remains the source of truth across restarts. */
final class LiveInferenceCoordinator {
    private final LiveInferenceWorker worker;
    private final boolean enabled;
    private final Semaphore lanes = new Semaphore(2);
    private final AtomicLong workerSequence = new AtomicLong();

    LiveInferenceCoordinator(LiveInferenceWorker worker, boolean enabled) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.enabled = enabled;
    }

    boolean enabled() {
        return enabled;
    }

    void kick() {
        if (!enabled || !lanes.tryAcquire()) return;
        Thread.startVirtualThread(() -> {
            try {
                var workerId = "live-worker-" + workerSequence.incrementAndGet();
                while (worker.processNext(workerId).isPresent()) {
                    // Drain one durable run at a time while this bounded lane is held.
                }
            } finally {
                lanes.release();
            }
        });
    }

    @Scheduled(fixedDelayString = "${renderweave.inference.live-poll-millis:2000}")
    void recoverQueuedWork() {
        kick();
    }
}
