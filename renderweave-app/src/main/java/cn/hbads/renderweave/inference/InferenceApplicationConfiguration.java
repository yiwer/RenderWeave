package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InputNormalizer;
import cn.hbads.renderweave.inference.candidate.CandidateReviewService;
import cn.hbads.renderweave.inference.candidate.CandidateApplyService;
import cn.hbads.renderweave.inference.candidate.CandidateApplyStore;
import cn.hbads.renderweave.inference.run.InferenceRunService;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.replay.ReplayInferenceWorker;
import cn.hbads.renderweave.inference.dashscope.DashScopeInferenceProvider;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.profile.InferencePromptRegistry;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProviderBudgetStore;
import cn.hbads.renderweave.inference.live.LiveInferenceWorker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class InferenceApplicationConfiguration {

    @Bean
    InferenceProfileRegistry inferenceProfileRegistry() {
        return new InferenceProfileRegistry();
    }

    @Bean
    InferencePromptRegistry inferencePromptRegistry() {
        return new InferencePromptRegistry();
    }

    @Bean
    InferenceProvider dashScopeInferenceProvider(
            ObjectMapper json,
            @Value("${DASHSCOPE_API_KEY:}") String directApiKey,
            @Value("${DASHSCOPE_API_KEY_FILE:}") String apiKeyFile
    ) {
        return DashScopeInferenceProvider.fromConfiguration(directApiKey, apiKeyFile, json);
    }

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

    @Bean
    LiveInferenceWorker liveInferenceWorker(
            InferenceRunStore runStore,
            InferenceReplayStore replayStore,
            ProviderBudgetStore budgetStore,
            InferenceProvider provider,
            BlobStore blobStore,
            Clock inferenceClock,
            @Value("${renderweave.inference.live-lease-seconds:600}") long leaseSeconds
    ) {
        return new LiveInferenceWorker(
                runStore, replayStore, budgetStore, provider, blobStore,
                inferenceClock, Duration.ofSeconds(leaseSeconds)
        );
    }

    @Bean
    InferenceCoordinator inferenceCoordinator(
            ReplayInferenceWorker replayWorker,
            LiveInferenceWorker liveWorker,
            @Value("${renderweave.inference.live-enabled:false}") boolean enabled,
            @Value("${renderweave.inference.live-upload-enabled:false}") boolean uploadEnabled
    ) {
        return new InferenceCoordinator(replayWorker, liveWorker, enabled, uploadEnabled);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "renderweave.inference",
            name = "recovery-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    InferenceRecoveryScheduler inferenceRecoveryScheduler(InferenceCoordinator coordinator) {
        return new InferenceRecoveryScheduler(coordinator);
    }

    @Bean
    CandidateReviewService candidateReviewService(
            InferenceRunStore runStore,
            InferenceReplayStore replayStore,
            Clock inferenceClock,
            BlobStore blobStore
    ) {
        return new CandidateReviewService(runStore, replayStore, inferenceClock, blobStore);
    }

    @Bean
    CandidateApplyService candidateApplyService(
            CandidateReviewService reviews,
            CandidateApplyStore applyStore,
            Clock inferenceClock
    ) {
        return new CandidateApplyService(reviews, applyStore, inferenceClock);
    }
}
