package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateApplyService;
import cn.hbads.renderweave.inference.candidate.CandidateReviewService;
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProviderBudgetStore;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.replay.ReplayInferenceWorker;
import cn.hbads.renderweave.inference.run.InferenceRunService;
import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InferenceControllerPolicyTest {
    private static final UUID SOURCE_RUN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void workerUploadAndCredentialAreIndependentFailClosedGates() {
        assertGate(controller(false, false, false), "LIVE_INFERENCE_DISABLED");
        assertGate(controller(true, false, true), "LIVE_UPLOAD_NOT_AUTHORIZED");
        assertGate(controller(true, true, false), "DASHSCOPE_NOT_CONFIGURED");
    }

    @Test
    void liveRetryCannotBypassAnyOfTheThreeAuthorizationGates() {
        assertRetryGate(controllerWithLiveSource(false, false, false), "LIVE_INFERENCE_DISABLED");
        assertRetryGate(controllerWithLiveSource(true, false, true), "LIVE_UPLOAD_NOT_AUTHORIZED");
        assertRetryGate(controllerWithLiveSource(true, true, false), "DASHSCOPE_NOT_CONFIGURED");
    }

    @Test
    void coordinatorCannotRecoverRetainedLiveInputsWhileUploadAuthorizationIsClosed() {
        var worker = mock(cn.hbads.renderweave.inference.live.LiveInferenceWorker.class);
        var coordinator = new LiveInferenceCoordinator(worker, true, false);

        assertThat(coordinator.enabled()).isTrue();
        assertThat(coordinator.dispatchEnabled()).isFalse();
        coordinator.kick();

        org.mockito.Mockito.verifyNoInteractions(worker);
    }

    private static void assertGate(InferenceController controller, String expectedCode) {
        assertThatThrownBy(() -> controller.createLive("policy-test", null, null, null))
                .isInstanceOfSatisfying(LiveInferenceUnavailableException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expectedCode));
    }

    private static void assertRetryGate(InferenceController controller, String expectedCode) {
        assertThatThrownBy(() -> controller.retry(SOURCE_RUN_ID, "policy-retry-test"))
                .isInstanceOfSatisfying(LiveInferenceUnavailableException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expectedCode));
    }

    private static InferenceController controllerWithLiveSource(
            boolean workerEnabled,
            boolean uploadEnabled,
            boolean providerConfigured
    ) {
        var runStore = mock(InferenceRunStore.class);
        var source = mock(InferenceRunSnapshot.class);
        var snapshot = new InferenceProfileRegistry()
                .require("dashscope-qwen37-flash-v1").snapshotJson();
        when(source.profileSnapshotJson()).thenReturn(snapshot);
        when(runStore.find(SOURCE_RUN_ID)).thenReturn(Optional.of(source));
        return controller(workerEnabled, uploadEnabled, providerConfigured, runStore);
    }

    private static InferenceController controller(
            boolean workerEnabled,
            boolean uploadEnabled,
            boolean providerConfigured
    ) {
        return controller(
                workerEnabled, uploadEnabled, providerConfigured, mock(InferenceRunStore.class)
        );
    }

    private static InferenceController controller(
            boolean workerEnabled,
            boolean uploadEnabled,
            boolean providerConfigured,
            InferenceRunStore runStore
    ) {
        var coordinator = mock(LiveInferenceCoordinator.class);
        when(coordinator.enabled()).thenReturn(workerEnabled);
        var provider = new InferenceProvider() {
            @Override
            public ProviderInferenceResponse complete(ProviderInferenceRequest request) {
                throw new AssertionError("Policy rejection must happen before provider invocation");
            }

            @Override
            public boolean configured() {
                return providerConfigured;
            }
        };
        return new InferenceController(
                mock(InferenceRunService.class),
                runStore,
                mock(InferenceReplayStore.class),
                mock(ReplayInferenceWorker.class),
                coordinator,
                provider,
                mock(ProviderBudgetStore.class),
                mock(CandidateReviewService.class),
                mock(CandidateApplyService.class),
                mock(ReplayFixtureInputFactory.class),
                mock(BlobStore.class),
                mock(ObjectMapper.class),
                uploadEnabled
        );
    }
}
