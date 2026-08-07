package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InputNormalizer;
import cn.hbads.renderweave.inference.run.InferenceRunService;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.replay.ReplayInferenceWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
class InferenceApplicationConfiguration {

    @Bean
    BlobStore inferenceBlobStore(
            @Value("${renderweave.inference.blob-root:./var/renderweave/blobs}") String root
    ) {
        return new FileSystemBlobStore(Path.of(root));
    }

    @Bean
    Clock inferenceClock() {
        return Clock.systemUTC();
    }

    @Bean
    InferenceRunService inferenceRunService(
            InferenceRunStore runStore,
            BlobStore blobStore,
            Clock inferenceClock
    ) {
        return new InferenceRunService(
                new InputNormalizer(blobStore), runStore, blobStore, inferenceClock, UUID::randomUUID
        );
    }

    @Bean
    ReplayInferenceWorker replayInferenceWorker(
            InferenceRunStore runStore,
            InferenceReplayStore replayStore,
            BlobStore blobStore,
            Clock inferenceClock,
            @Value("${renderweave.inference.lease-seconds:30}") long leaseSeconds
    ) {
        return new ReplayInferenceWorker(
                runStore, replayStore, blobStore, inferenceClock, Duration.ofSeconds(leaseSeconds)
        );
    }
}
