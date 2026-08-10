package cn.hbads.renderweave.inference.run;

import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.InferenceStorageException;
import cn.hbads.renderweave.inference.input.InputNormalizer;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class InferenceRunService {
    private final InputNormalizer inputNormalizer;
    private final InferenceRunStore runStore;
    private final BlobStore blobStore;
    private final Clock clock;
    private final Supplier<UUID> runIds;

    public InferenceRunService(
            InputNormalizer inputNormalizer,
            InferenceRunStore runStore,
            BlobStore blobStore,
            Clock clock,
            Supplier<UUID> runIds
    ) {
        this.inputNormalizer = Objects.requireNonNull(inputNormalizer, "inputNormalizer");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runIds = Objects.requireNonNull(runIds, "runIds");
    }

    public synchronized InferenceRunStore.CreationResult create(
            String idempotencyKey,
            InferenceInput input,
            String profileSnapshotJson
    ) {
        return create(idempotencyKey, input, profileSnapshotJson, null);
    }

    public synchronized InferenceRunStore.CreationResult create(
            String idempotencyKey,
            InferenceInput input,
            String profileSnapshotJson,
            Long costLimitMicrosCny
    ) {
        var normalized = inputNormalizer.normalize(input);
        try {
            return runStore.create(NewInferenceRun.initial(
                    runIds.get(), idempotencyKey, normalized, profileSnapshotJson,
                    costLimitMicrosCny, clock.instant()
            ));
        } catch (RuntimeException primary) {
            cleanupLocators(normalized.newlyCreatedLocators(), primary);
            throw primary;
        }
    }

    public synchronized InferenceRunStore.CreationResult retry(UUID sourceRunId, String idempotencyKey) {
        return runStore.retry(sourceRunId, runIds.get(), idempotencyKey, clock.instant());
    }

    public synchronized InferenceRunSnapshot cancel(UUID runId) {
        return runStore.requestCancellation(runId, clock.instant());
    }

    public synchronized void delete(UUID runId) {
        deleteArtifacts(runStore.delete(runId));
    }

    public synchronized int drainPendingArtifactDeletions(int limit) {
        var pending = runStore.pendingArtifactDeletions(limit);
        deleteArtifacts(pending);
        return pending.size();
    }

    private void deleteArtifacts(List<InferenceArtifactDeletion> artifacts) {
        InferenceStorageException aggregate = null;
        for (var artifact : artifacts) {
            try {
                blobStore.delete(artifact.locator());
                runStore.confirmArtifactDeletion(artifact.artifactId());
            } catch (RuntimeException failure) {
                if (aggregate == null) {
                    aggregate = new InferenceStorageException(
                            "STORAGE_ARTIFACT_CLEANUP_PENDING",
                            "The run was deleted, but one or more normalized artifacts remain queued for cleanup",
                            failure
                    );
                } else {
                    aggregate.addSuppressed(failure);
                }
            }
        }
        if (aggregate != null) throw aggregate;
    }

    private void cleanupLocators(List<String> locators, RuntimeException primary) {
        for (var index = locators.size() - 1; index >= 0; index--) {
            try {
                blobStore.delete(locators.get(index));
            } catch (RuntimeException cleanupFailure) {
                primary.addSuppressed(cleanupFailure);
            }
        }
    }
}
