package cn.hbads.renderweave.inference.run;

import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.InferenceStorageException;
import cn.hbads.renderweave.inference.input.InputNormalizer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceRunServiceTest {

    @Test
    void databaseFailureRemovesOnlyTheNormalizedArtifactsCreatedForTheAttempt() {
        var blobs = new TrackingBlobStore();
        var store = new UnsupportedRunStore() {
            @Override
            public CreationResult create(NewInferenceRun command) {
                throw new IllegalStateException("simulated database failure");
            }
        };
        var service = new InferenceRunService(
                new InputNormalizer(blobs), store, blobs,
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC),
                () -> UUID.fromString("00000000-0000-0000-0000-000000000001")
        );
        var input = new InferenceInput(
                InferenceMode.JSON_ONLY, "replay-v1", "fixture-01", true, List.of(),
                List.of(new InferenceInput.BinaryInput(
                        "sample.json", "application/json",
                        "{\"label\":\"must-not-reach-the-database\"}".getBytes(StandardCharsets.UTF_8)
                ))
        );

        assertThrows(IllegalStateException.class,
                () -> service.create("idem-failure", input, "{\"profileId\":\"replay-v1\"}"));

        assertEquals(1, blobs.deleted.size());
        assertTrue(blobs.bytes.isEmpty());
    }

    @Test
    void failedBlobDeletionStaysPendingAndIsNotConfirmedInTheDatabase() {
        var blobs = new TrackingBlobStore();
        blobs.failDelete = true;
        var artifactId = sha256("pending".getBytes(StandardCharsets.UTF_8));
        var store = new UnsupportedRunStore() {
            @Override
            public List<InferenceArtifactDeletion> delete(UUID runId) {
                return List.of(new InferenceArtifactDeletion(artifactId, artifactId));
            }

            @Override
            public boolean confirmArtifactDeletion(String ignored) {
                throw new AssertionError("failed filesystem deletion must not be confirmed");
            }
        };
        var service = new InferenceRunService(
                new InputNormalizer(blobs), store, blobs, Clock.systemUTC(), UUID::randomUUID
        );

        var error = assertThrows(InferenceStorageException.class, () -> service.delete(UUID.randomUUID()));
        assertEquals("STORAGE_ARTIFACT_CLEANUP_PENDING", error.code());
    }

    private static final class TrackingBlobStore implements BlobStore {
        private final java.util.Map<String, byte[]> bytes = new java.util.LinkedHashMap<>();
        private final List<String> deleted = new ArrayList<>();
        private boolean failDelete;

        @Override
        public WriteReceipt write(String artifactId, byte[] value) {
            var created = bytes.putIfAbsent(artifactId, value.clone()) == null;
            return new WriteReceipt(artifactId, created);
        }

        @Override
        public byte[] read(String locator) {
            return bytes.get(locator).clone();
        }

        @Override
        public void delete(String locator) {
            if (failDelete) throw new IllegalStateException("simulated filesystem failure");
            deleted.add(locator);
            bytes.remove(locator);
        }
    }

    private abstract static class UnsupportedRunStore implements InferenceRunStore {
        @Override
        public CreationResult create(NewInferenceRun command) {
            throw unsupported();
        }

        @Override
        public Optional<InferenceRunSnapshot> find(UUID runId) {
            throw unsupported();
        }

        @Override
        public Optional<InferenceRunSnapshot> claimNext(String workerId, Instant now, Duration leaseDuration) {
            throw unsupported();
        }

        @Override
        public Optional<InferenceRunSnapshot> claim(
                UUID runId,
                String workerId,
                Instant now,
                Duration leaseDuration
        ) {
            throw unsupported();
        }

        @Override
        public boolean renewLease(UUID runId, UUID leaseToken, Instant now, Duration leaseDuration) {
            throw unsupported();
        }

        @Override
        public InferenceRunSnapshot checkpoint(
                UUID runId, UUID leaseToken, InferenceStage expectedStage,
                InferenceStage nextStage, String checkpointJson, Instant now
        ) {
            throw unsupported();
        }

        @Override
        public InferenceRunSnapshot requestCancellation(UUID runId, Instant now) {
            throw unsupported();
        }

        @Override
        public InferenceRunSnapshot acknowledgeCancellation(UUID runId, UUID leaseToken, Instant now) {
            throw unsupported();
        }

        @Override
        public InferenceRunSnapshot fail(UUID runId, UUID leaseToken, String failureCode, Instant now) {
            throw unsupported();
        }

        @Override
        public CreationResult retry(UUID sourceRunId, UUID newRunId, String idempotencyKey, Instant now) {
            throw unsupported();
        }

        @Override
        public List<InferenceArtifactDeletion> delete(UUID runId) {
            throw unsupported();
        }

        @Override
        public List<InferenceArtifactDeletion> pendingArtifactDeletions(int limit) {
            throw unsupported();
        }

        @Override
        public boolean confirmArtifactDeletion(String artifactId) {
            throw unsupported();
        }

        @Override
        public List<InferenceRunEvent> eventsAfter(UUID runId, long sequenceExclusive, int limit) {
            throw unsupported();
        }

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by this test");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
