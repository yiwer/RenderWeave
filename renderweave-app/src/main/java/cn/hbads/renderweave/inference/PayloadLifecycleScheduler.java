package cn.hbads.renderweave.inference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "renderweave.inference.payload-lifecycle",
        name = "enabled",
        havingValue = "true"
)
final class PayloadLifecycleScheduler {
    private final PostgresPayloadLifecycleStore payloads;
    private final int batchSize;

    PayloadLifecycleScheduler(
            PostgresPayloadLifecycleStore payloads,
            @Value("${renderweave.inference.payload-lifecycle.batch-size:100}") int batchSize
    ) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Payload lifecycle batch size must be 1..1000");
        }
        this.payloads = payloads;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${renderweave.inference.payload-lifecycle.interval-ms:5000}",
            initialDelayString = "${renderweave.inference.payload-lifecycle.initial-delay-ms:5000}"
    )
    void sweepAndDelete() {
        payloads.sweepDueRuns(batchSize);
        payloads.sweepExpiredIngestLeases(batchSize);
        payloads.drainDeletionTasks(batchSize);
    }
}
