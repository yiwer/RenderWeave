package cn.hbads.renderweave.inference;

import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/** Production trigger for durable replay/live queue recovery. */
final class InferenceRecoveryScheduler {
    private final InferenceCoordinator coordinator;

    InferenceRecoveryScheduler(InferenceCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Scheduled(
            fixedDelayString = "${renderweave.inference.live-poll-millis:2000}",
            initialDelayString = "${renderweave.inference.poll-initial-delay-millis:2000}"
    )
    void recoverQueuedWork() {
        coordinator.kick();
    }
}
