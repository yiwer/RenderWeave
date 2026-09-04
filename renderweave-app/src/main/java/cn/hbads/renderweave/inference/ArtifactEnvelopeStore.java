package cn.hbads.renderweave.inference;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

interface ArtifactEnvelopeStore {
    <T> T withArtifactLock(String artifactId, Supplier<T> work);

    Optional<ArtifactEnvelope> find(String artifactId);

    void insert(ArtifactEnvelope envelope);

    void updateWrappedKey(ArtifactEnvelope envelope);

    void protectForAdmission(String artifactId, Instant observedAt, Instant expiresAt);

    void releaseAdmissionProtection(String artifactId);

    boolean delete(String artifactId);

    long countByKekId(String kekId);
}
