package cn.hbads.renderweave.app.coordination;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class PostgresAssetReferenceReservationsTest {
    private static final String ASSET_ID = "00000000-0000-4000-8000-0000000000aa";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PostgresAssetReferenceReservations reservations;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void sharedReservationsOverlapWhileExclusiveWaitsForTheirTransactions() throws Exception {
        var firstSharedHeld = new CountDownLatch(1);
        var releaseFirstShared = new CountDownLatch(1);
        var exclusiveAttempted = new CountDownLatch(1);
        var exclusiveAcquired = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> transaction().executeWithoutResult(status -> {
                reservations.acquireShared(List.of(ASSET_ID));
                firstSharedHeld.countDown();
                await(releaseFirstShared);
            }));
            assertThat(firstSharedHeld.await(5, TimeUnit.SECONDS)).isTrue();

            var secondShared = executor.submit(() -> transaction().executeWithoutResult(status ->
                    reservations.acquireShared(List.of(ASSET_ID))));
            secondShared.get(5, TimeUnit.SECONDS);

            var exclusive = executor.submit(() -> transaction().executeWithoutResult(status -> {
                exclusiveAttempted.countDown();
                reservations.acquireExclusive(ASSET_ID);
                exclusiveAcquired.countDown();
            }));
            assertThat(exclusiveAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(exclusiveAcquired.await(250, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirstShared.countDown();
            first.get(5, TimeUnit.SECONDS);
            exclusive.get(5, TimeUnit.SECONDS);
            assertThat(exclusiveAcquired.getCount()).isZero();
        }
    }

    @Test
    void reservationOutsideTransactionFailsClosed() {
        assertThatThrownBy(() -> reservations.acquireExclusive(ASSET_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a transaction");
    }

    private TransactionTemplate transaction() {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout((int) Duration.ofSeconds(5).toSeconds());
        return transaction;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("reservation test barrier timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("reservation test interrupted", interrupted);
        }
    }
}
