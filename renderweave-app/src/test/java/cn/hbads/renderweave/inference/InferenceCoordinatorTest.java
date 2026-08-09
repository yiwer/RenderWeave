package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.live.LiveInferenceWorker;
import cn.hbads.renderweave.inference.replay.ReplayInferenceWorker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InferenceCoordinatorTest {

    @Test
    void replayAndRecoveryShareTwoLanesAndAThirdRunWaits() throws Exception {
        var replay = mock(ReplayInferenceWorker.class);
        var live = mock(LiveInferenceWorker.class);
        var firstRunId = UUID.randomUUID();
        var thirdRunId = UUID.randomUUID();
        var release = new CountDownLatch(1);
        var firstExactEntered = new CountDownLatch(1);
        var queuedEntered = new CountDownLatch(1);
        var thirdExactEntered = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximumActive = new AtomicInteger();

        when(replay.process(any(UUID.class), anyString())).thenAnswer(invocation -> {
            var runId = invocation.getArgument(0, UUID.class);
            enter(active, maximumActive);
            if (runId.equals(firstRunId)) firstExactEntered.countDown();
            if (runId.equals(thirdRunId)) thirdExactEntered.countDown();
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                return Optional.empty();
            } finally {
                active.decrementAndGet();
            }
        });
        when(replay.processNext(anyString())).thenAnswer(ignored -> {
            enter(active, maximumActive);
            queuedEntered.countDown();
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                return Optional.empty();
            } finally {
                active.decrementAndGet();
            }
        });
        when(live.processNext(anyString())).thenReturn(Optional.empty());

        var coordinator = new InferenceCoordinator(replay, live, true, true);
        try (var callers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var first = callers.submit(() -> coordinator.processReplay(firstRunId, "exact-first"));
            assertThat(firstExactEntered.await(10, TimeUnit.SECONDS)).isTrue();

            coordinator.kick();
            assertThat(queuedEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(maximumActive.get()).isEqualTo(2);

            var third = callers.submit(() -> coordinator.processReplay(thirdRunId, "exact-third"));
            assertThat(thirdExactEntered.await(300, TimeUnit.MILLISECONDS)).isFalse();

            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            third.get(10, TimeUnit.SECONDS);
            assertThat(thirdExactEntered.getCount()).isZero();
            assertThat(maximumActive.get()).isEqualTo(2);
        } finally {
            release.countDown();
            coordinator.close();
        }
    }

    @Test
    void failedDispatchReturnsItsPermit() throws Exception {
        var replay = mock(ReplayInferenceWorker.class);
        var live = mock(LiveInferenceWorker.class);
        var failedRunId = UUID.randomUUID();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(replay.process(any(UUID.class), anyString())).thenAnswer(invocation -> {
            var runId = invocation.getArgument(0, UUID.class);
            if (runId.equals(failedRunId)) throw new IllegalStateException("expected failure");
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return Optional.empty();
        });

        var coordinator = new InferenceCoordinator(replay, live, false, false);
        try {
            assertThatThrownBy(() -> coordinator.processReplay(failedRunId, "failed"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("expected failure");

            try (var callers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                futures.add(callers.submit(() -> coordinator.processReplay(UUID.randomUUID(), "one")));
                futures.add(callers.submit(() -> coordinator.processReplay(UUID.randomUUID(), "two")));
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                for (var future : futures) future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            coordinator.close();
        }
    }

    private static void enter(AtomicInteger active, AtomicInteger maximumActive) {
        var current = active.incrementAndGet();
        maximumActive.accumulateAndGet(current, Math::max);
    }
}
