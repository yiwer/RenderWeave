package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceStorageException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Per-artifact random-DEK AEAD envelope storage backed by PostgreSQL metadata and opaque ciphertext. */
public class EnvelopeEncryptedBlobStore implements BlobStore {
    private final ArtifactEnvelopeStore envelopes;
    private final EncryptedCiphertextStore ciphertexts;
    private final ArtifactKekRing kekRing;
    private final SecureRandom random;
    private final Clock clock;

    EnvelopeEncryptedBlobStore(
            ArtifactEnvelopeStore envelopes,
            EncryptedCiphertextStore ciphertexts,
            ArtifactKekRing kekRing,
            SecureRandom random,
            Clock clock
    ) {
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.ciphertexts = Objects.requireNonNull(ciphertexts, "ciphertexts");
        this.kekRing = Objects.requireNonNull(kekRing, "kekRing");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public WriteReceipt write(String artifactId, byte[] plaintext) {
        validateArtifactId(artifactId);
        Objects.requireNonNull(plaintext, "plaintext");
        if (!artifactId.equals(sha256(plaintext))) {
            throw problem(
                    "STORAGE_ARTIFACT_DIGEST_MISMATCH",
                    "Artifact bytes do not match their content identity",
                    null
            );
        }
        return envelopes.withArtifactLock(artifactId, () -> {
            var existing = envelopes.find(artifactId);
            if (existing.isPresent()) {
                var recovered = decrypt(existing.orElseThrow());
                try {
                    if (!MessageDigest.isEqual(recovered, plaintext)) {
                        throw problem(
                                "STORAGE_ARTIFACT_IDENTITY_CONFLICT",
                                "Existing encrypted artifact has different plaintext",
                                null
                        );
                    }
                    protectForAdmission(artifactId);
                    return new WriteReceipt(artifactId, false);
                } finally {
                    Arrays.fill(recovered, (byte) 0);
                }
            }

            // A file without metadata is an unreferenced crash orphan. The PostgreSQL advisory lock
            // ensures no conforming writer can be committing metadata for this identity concurrently.
            if (ciphertexts.exists(artifactId)) ciphertexts.delete(artifactId);
            var prepared = encrypt(artifactId, plaintext);
            ciphertexts.write(
                    prepared.envelope().ciphertextLocator(),
                    prepared.ciphertext(),
                    prepared.envelope().ciphertextSha256()
            );
            envelopes.insert(prepared.envelope());
            protectForAdmission(artifactId);
            return new WriteReceipt(artifactId, true);
        });
    }

    @Override
    public byte[] read(String locator) {
        validateArtifactId(locator);
        var envelope = envelopes.find(locator).orElseThrow(() -> problem(
                "STORAGE_ARTIFACT_ENVELOPE_MISSING",
                "Encrypted artifact metadata is unavailable",
                null
        ));
        return decrypt(envelope);
    }

    @Override
    public void delete(String locator) {
        validateArtifactId(locator);
        envelopes.withArtifactLock(locator, () -> {
            // Deleting wrapped key metadata establishes crypto-erasure authority. If physical deletion
            // fails, the surrounding PostgreSQL transaction restores metadata for a safe retry.
            envelopes.releaseAdmissionProtection(locator);
            envelopes.delete(locator);
            ciphertexts.delete(locator);
            return null;
        });
    }

    void rewrap(String artifactId, String targetKekId) {
        validateArtifactId(artifactId);
        ArtifactEnvelope.requireKeyId(targetKekId);
        envelopes.withArtifactLock(artifactId, () -> {
            var current = envelopes.find(artifactId).orElseThrow(() -> problem(
                    "STORAGE_ARTIFACT_ENVELOPE_MISSING",
                    "Encrypted artifact metadata is unavailable",
                    null
            ));
            if (current.kekId().equals(targetKekId)) return null;
            var dek = EnvelopeCrypto.open(
                    kekRing.require(current.kekId()),
                    current.wrappingNonce(),
                    EnvelopeCrypto.wrappingAad(artifactId, current.kekId()),
                    current.wrappedDek(),
                    current.wrappingTag()
            );
            try {
                var nonce = random(EnvelopeCrypto.NONCE_BYTES);
                var wrapped = EnvelopeCrypto.seal(
                        kekRing.require(targetKekId), nonce,
                        EnvelopeCrypto.wrappingAad(artifactId, targetKekId), dek
                );
                var rewrappedAt = clock.instant();
                if (!rewrappedAt.isAfter(current.rewrappedAt())) {
                    rewrappedAt = current.rewrappedAt().plusNanos(1);
                }
                envelopes.updateWrappedKey(current.rewrapped(
                        targetKekId, wrapped.ciphertext(), nonce, wrapped.tag(), rewrappedAt
                ));
            } finally {
                Arrays.fill(dek, (byte) 0);
            }
            return null;
        });
    }

    long countKekReferences(String kekId) {
        ArtifactEnvelope.requireKeyId(kekId);
        return envelopes.countByKekId(kekId);
    }

    private void protectForAdmission(String artifactId) {
        var observedAt = clock.instant();
        envelopes.protectForAdmission(
                artifactId, observedAt, observedAt.plus(Duration.ofMinutes(15))
        );
    }

    private PreparedArtifact encrypt(String artifactId, byte[] plaintext) {
        var dekBytes = random(EnvelopeCrypto.KEY_BYTES);
        try {
            var payloadNonce = random(EnvelopeCrypto.NONCE_BYTES);
            var payload = EnvelopeCrypto.seal(
                    EnvelopeCrypto.dek(dekBytes), payloadNonce,
                    EnvelopeCrypto.payloadAad(artifactId), plaintext
            );
            var kekId = kekRing.currentKeyId();
            var wrappingNonce = random(EnvelopeCrypto.NONCE_BYTES);
            var wrapped = EnvelopeCrypto.seal(
                    kekRing.require(kekId), wrappingNonce,
                    EnvelopeCrypto.wrappingAad(artifactId, kekId), dekBytes
            );
            var ciphertext = payload.ciphertext();
            var now = clock.instant();
            return new PreparedArtifact(
                    new ArtifactEnvelope(
                            artifactId, ArtifactEnvelope.VERSION,
                            ArtifactEnvelope.ALGORITHM, ArtifactEnvelope.ALGORITHM,
                            artifactId, sha256(ciphertext), payloadNonce, payload.tag(),
                            kekId, wrapped.ciphertext(), wrappingNonce, wrapped.tag(), now, now
                    ),
                    ciphertext
            );
        } finally {
            Arrays.fill(dekBytes, (byte) 0);
        }
    }

    private byte[] decrypt(ArtifactEnvelope envelope) {
        var ciphertext = ciphertexts.read(envelope.ciphertextLocator());
        if (!envelope.ciphertextSha256().equals(sha256(ciphertext))) {
            throw problem(
                    "STORAGE_CIPHERTEXT_CORRUPTED",
                    "Encrypted artifact ciphertext failed its digest check",
                    null
            );
        }
        var dek = EnvelopeCrypto.open(
                kekRing.require(envelope.kekId()),
                envelope.wrappingNonce(),
                EnvelopeCrypto.wrappingAad(envelope.artifactId(), envelope.kekId()),
                envelope.wrappedDek(),
                envelope.wrappingTag()
        );
        try {
            var plaintext = EnvelopeCrypto.open(
                    EnvelopeCrypto.dek(dek), envelope.payloadNonce(),
                    EnvelopeCrypto.payloadAad(envelope.artifactId()),
                    ciphertext, envelope.payloadTag()
            );
            if (!envelope.artifactId().equals(sha256(plaintext))) {
                Arrays.fill(plaintext, (byte) 0);
                throw problem(
                        "STORAGE_ARTIFACT_DIGEST_MISMATCH",
                        "Decrypted artifact failed its content identity check",
                        null
                );
            }
            return plaintext;
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    private byte[] random(int length) {
        var bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }

    private static void validateArtifactId(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Artifact identity must be a SHA-256 hex digest");
        }
    }

    private static InferenceStorageException problem(String code, String message, Throwable cause) {
        return new InferenceStorageException(code, message, cause);
    }

    private record PreparedArtifact(ArtifactEnvelope envelope, byte[] ciphertext) {
        private PreparedArtifact {
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() { return ciphertext.clone(); }
    }
}
