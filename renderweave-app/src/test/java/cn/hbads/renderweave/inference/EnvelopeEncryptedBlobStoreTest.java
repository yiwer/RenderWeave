package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.InferenceStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest
class EnvelopeEncryptedBlobStoreTest {
    private static final Instant T0 = Instant.parse("2026-08-18T08:00:00Z");
    private static final String OLD_KEK = "artifact-kek-2026-08-a";
    private static final String NEW_KEK = "artifact-kek-2026-08-b";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private PostgresArtifactEnvelopeStore envelopes;

    @Autowired
    private JdbcClient jdbcClient;

    @TempDir
    Path temporaryDirectory;

    private FileSystemEncryptedCiphertextStore ciphertexts;

    @BeforeEach
    void clear() {
        jdbcClient.sql("truncate table inference_artifact_ingest_lease, inference_artifact_envelope").update();
        ciphertexts = new FileSystemEncryptedCiphertextStore(temporaryDirectory.resolve("ciphertext"));
    }

    @Test
    void writesOnlyCiphertextAndIdempotentlyRecoversExactPlaintext() throws Exception {
        var store = store(envelopes, ciphertexts, oldRing(), T0);
        var plaintext = bytes("synthetic-p2-plaintext-marker-001");
        var artifactId = sha256(plaintext);

        var created = store.write(artifactId, plaintext);
        var firstCiphertext = Files.readAllBytes(ciphertexts.pathForTesting(artifactId));
        var replayed = store.write(artifactId, plaintext);

        assertThat(created.created()).isTrue();
        assertThat(replayed.created()).isFalse();
        assertThat(replayed.locator()).isEqualTo(artifactId);
        assertThat(store.read(artifactId)).isEqualTo(plaintext);
        assertThat(firstCiphertext).isNotEqualTo(plaintext);
        assertThat(Files.readAllBytes(ciphertexts.pathForTesting(artifactId)))
                .isEqualTo(firstCiphertext);
        assertThat(scanFor(temporaryDirectory, "synthetic-p2-plaintext-marker-001")).isFalse();

        var envelope = envelopes.find(artifactId).orElseThrow();
        assertThat(envelope.envelopeVersion()).isEqualTo(ArtifactEnvelope.VERSION);
        assertThat(envelope.payloadAlgorithm()).isEqualTo(ArtifactEnvelope.ALGORITHM);
        assertThat(envelope.wrappingAlgorithm()).isEqualTo(ArtifactEnvelope.ALGORITHM);
        assertThat(envelope.kekId()).isEqualTo(OLD_KEK);
        assertThat(jdbcClient.sql("""
                        select count(*) from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'inference_artifact_envelope'
                          and column_name in ('plaintext', 'key_material', 'kek_bytes', 'dek')
                        """).query(Long.class).single()).isZero();
    }

    @Test
    void generatesIndependentPayloadAndWrappingNoncesPerArtifact() {
        var store = store(envelopes, ciphertexts, oldRing(), T0);
        var first = bytes("synthetic-nonce-case-a");
        var second = bytes("synthetic-nonce-case-b");
        var firstId = sha256(first);
        var secondId = sha256(second);

        store.write(firstId, first);
        store.write(secondId, second);

        var firstEnvelope = envelopes.find(firstId).orElseThrow();
        var secondEnvelope = envelopes.find(secondId).orElseThrow();
        assertThat(Arrays.equals(firstEnvelope.payloadNonce(), secondEnvelope.payloadNonce())).isFalse();
        assertThat(Arrays.equals(firstEnvelope.wrappingNonce(), secondEnvelope.wrappingNonce())).isFalse();
    }

    @Test
    void rejectsTamperedTruncatedAndSwappedCiphertext() throws Exception {
        var store = store(envelopes, ciphertexts, oldRing(), T0);
        var first = bytes("synthetic-ciphertext-case-a");
        var second = bytes("synthetic-ciphertext-case-b");
        var firstId = sha256(first);
        var secondId = sha256(second);
        store.write(firstId, first);
        store.write(secondId, second);
        var firstPath = ciphertexts.pathForTesting(firstId);
        var secondPath = ciphertexts.pathForTesting(secondId);
        var original = Files.readAllBytes(firstPath);

        var tampered = original.clone();
        tampered[0] ^= 1;
        Files.write(firstPath, tampered);
        assertCode("STORAGE_CIPHERTEXT_CORRUPTED", () -> store.read(firstId));

        Files.write(firstPath, Arrays.copyOf(original, original.length - 1));
        assertCode("STORAGE_CIPHERTEXT_CORRUPTED", () -> store.read(firstId));

        Files.write(firstPath, Files.readAllBytes(secondPath));
        assertCode("STORAGE_CIPHERTEXT_CORRUPTED", () -> store.read(firstId));
    }

    @Test
    void databasePreventsPayloadMetadataSwapAndOnlyAllowsRewrapFields() {
        var store = store(envelopes, ciphertexts, oldRing(), T0);
        var first = bytes("synthetic-metadata-case-a");
        var second = bytes("synthetic-metadata-case-b");
        var firstId = sha256(first);
        var secondId = sha256(second);
        store.write(firstId, first);
        store.write(secondId, second);

        assertThatThrownBy(() -> jdbcClient.sql("""
                        update inference_artifact_envelope target
                        set payload_nonce = source.payload_nonce,
                            rewrapped_at = target.rewrapped_at + interval '1 second'
                        from inference_artifact_envelope source
                        where target.artifact_id = :target
                          and source.artifact_id = :source
                        """)
                .param("target", firstId)
                .param("source", secondId)
                .update())
                .hasStackTraceContaining("artifact payload envelope fields are immutable");
    }

    @Test
    void failsClosedWhenPostgresBlobOrRequiredKekIsMissing() {
        var store = store(envelopes, ciphertexts, oldRing(), T0);

        var missingMetadataBytes = bytes("synthetic-missing-metadata");
        var missingMetadataId = sha256(missingMetadataBytes);
        store.write(missingMetadataId, missingMetadataBytes);
        envelopes.delete(missingMetadataId);
        assertCode("STORAGE_ARTIFACT_ENVELOPE_MISSING", () -> store.read(missingMetadataId));

        var missingBlobBytes = bytes("synthetic-missing-ciphertext");
        var missingBlobId = sha256(missingBlobBytes);
        store.write(missingBlobId, missingBlobBytes);
        ciphertexts.delete(missingBlobId);
        assertCode("STORAGE_CIPHERTEXT_READ_FAILED", () -> store.read(missingBlobId));

        var missingKekBytes = bytes("synthetic-missing-kek");
        var missingKekId = sha256(missingKekBytes);
        store.write(missingKekId, missingKekBytes);
        var withoutOldKek = store(envelopes, ciphertexts, newOnlyRing(), T0.plusSeconds(1));
        assertCode("STORAGE_KEK_UNAVAILABLE", () -> withoutOldKek.read(missingKekId));
        assertCode("STORAGE_KEK_UNAVAILABLE", () -> withoutOldKek.rewrap(missingKekId, NEW_KEK));
    }

    @Test
    void rewrapChangesOnlyWrappedDekMetadataAndDrainsOldKekReferences() throws Exception {
        var oldStore = store(envelopes, ciphertexts, oldRing(), T0);
        var plaintext = bytes("synthetic-rewrap-case");
        var artifactId = sha256(plaintext);
        oldStore.write(artifactId, plaintext);
        var before = envelopes.find(artifactId).orElseThrow();
        var ciphertextBefore = Files.readAllBytes(ciphertexts.pathForTesting(artifactId));

        var rotatingStore = store(envelopes, ciphertexts, rotatingRing(), T0.plusSeconds(1));
        rotatingStore.rewrap(artifactId, NEW_KEK);

        var after = envelopes.find(artifactId).orElseThrow();
        assertThat(after.kekId()).isEqualTo(NEW_KEK);
        assertThat(after.wrappedDek()).isNotEqualTo(before.wrappedDek());
        assertThat(after.wrappingNonce()).isNotEqualTo(before.wrappingNonce());
        assertThat(after.ciphertextSha256()).isEqualTo(before.ciphertextSha256());
        assertThat(after.payloadNonce()).isEqualTo(before.payloadNonce());
        assertThat(after.payloadTag()).isEqualTo(before.payloadTag());
        assertThat(Files.readAllBytes(ciphertexts.pathForTesting(artifactId)))
                .isEqualTo(ciphertextBefore);
        assertThat(rotatingStore.countKekReferences(OLD_KEK)).isZero();
        assertThat(rotatingStore.countKekReferences(NEW_KEK)).isEqualTo(1L);
        assertThat(rotatingStore.read(artifactId)).isEqualTo(plaintext);
    }

    @Test
    void deletionRemovesWrappedDekAndCiphertextAndMakesReadImpossible() {
        var store = store(envelopes, ciphertexts, oldRing(), T0);
        var plaintext = bytes("synthetic-crypto-erasure-case");
        var artifactId = sha256(plaintext);
        store.write(artifactId, plaintext);

        store.delete(artifactId);

        assertThat(envelopes.find(artifactId)).isEmpty();
        assertThat(ciphertexts.exists(artifactId)).isFalse();
        assertCode("STORAGE_ARTIFACT_ENVELOPE_MISSING", () -> store.read(artifactId));
    }

    @Test
    void retryReconcilesEncryptedOrphanAfterMetadataInsertCrash() throws Exception {
        var faultingEnvelopes = new FailFirstInsertEnvelopeStore(envelopes);
        var store = store(faultingEnvelopes, ciphertexts, oldRing(), T0);
        var plaintext = bytes("synthetic-crash-orphan-case");
        var artifactId = sha256(plaintext);

        assertCode("STORAGE_INJECTED_METADATA_FAILURE", () -> store.write(artifactId, plaintext));
        assertThat(envelopes.find(artifactId)).isEmpty();
        assertThat(ciphertexts.exists(artifactId)).isTrue();
        var orphan = Files.readAllBytes(ciphertexts.pathForTesting(artifactId));

        var recovered = store.write(artifactId, plaintext);

        assertThat(recovered.created()).isTrue();
        assertThat(envelopes.find(artifactId)).isPresent();
        assertThat(Files.readAllBytes(ciphertexts.pathForTesting(artifactId))).isNotEqualTo(orphan);
        assertThat(store.read(artifactId)).isEqualTo(plaintext);
    }

    @Test
    void retryAfterCommittedResponseLossReturnsTheOriginalArtifact() throws Exception {
        var responseLoss = new FailOnceAfterCommitEnvelopeStore(envelopes);
        var store = store(responseLoss, ciphertexts, oldRing(), T0);
        var plaintext = bytes("synthetic-response-loss-case");
        var artifactId = sha256(plaintext);

        assertCode("STORAGE_INJECTED_RESPONSE_LOSS", () -> store.write(artifactId, plaintext));
        var committed = envelopes.find(artifactId).orElseThrow();
        var ciphertext = Files.readAllBytes(ciphertexts.pathForTesting(artifactId));

        var replayed = store.write(artifactId, plaintext);

        assertThat(replayed.created()).isFalse();
        assertThat(envelopes.find(artifactId).orElseThrow())
                .usingRecursiveComparison()
                .isEqualTo(committed);
        assertThat(Files.readAllBytes(ciphertexts.pathForTesting(artifactId))).isEqualTo(ciphertext);
        assertThat(store.read(artifactId)).isEqualTo(plaintext);
    }

    private EnvelopeEncryptedBlobStore store(
            ArtifactEnvelopeStore envelopeStore,
            EncryptedCiphertextStore ciphertextStore,
            ArtifactKekRing ring,
            Instant instant
    ) {
        return new EnvelopeEncryptedBlobStore(
                envelopeStore,
                ciphertextStore,
                ring,
                new SecureRandom(),
                Clock.fixed(instant, ZoneOffset.UTC)
        );
    }

    private static ArtifactKekRing oldRing() {
        return ArtifactKekRing.of(OLD_KEK, Map.of(OLD_KEK, key((byte) 0x41)));
    }

    private static ArtifactKekRing newOnlyRing() {
        return ArtifactKekRing.of(NEW_KEK, Map.of(NEW_KEK, key((byte) 0x42)));
    }

    private static ArtifactKekRing rotatingRing() {
        return ArtifactKekRing.of(NEW_KEK, Map.of(
                OLD_KEK, key((byte) 0x41),
                NEW_KEK, key((byte) 0x42)
        ));
    }

    private static byte[] key(byte value) {
        var result = new byte[32];
        Arrays.fill(result, value);
        return result;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean scanFor(Path root, String marker) throws Exception {
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                var value = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                if (value.contains(marker)) return true;
            }
        }
        return false;
    }

    private static void assertCode(String expected, Executable operation) {
        var failure = assertThrows(InferenceStorageException.class, operation);
        assertThat(failure.code()).isEqualTo(expected);
    }

    private abstract static class DelegatingEnvelopeStore implements ArtifactEnvelopeStore {
        final ArtifactEnvelopeStore delegate;

        DelegatingEnvelopeStore(ArtifactEnvelopeStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> T withArtifactLock(String artifactId, Supplier<T> work) {
            return delegate.withArtifactLock(artifactId, work);
        }

        @Override
        public Optional<ArtifactEnvelope> find(String artifactId) {
            return delegate.find(artifactId);
        }

        @Override
        public void insert(ArtifactEnvelope envelope) {
            delegate.insert(envelope);
        }

        @Override
        public void updateWrappedKey(ArtifactEnvelope envelope) {
            delegate.updateWrappedKey(envelope);
        }

        @Override
        public void protectForAdmission(String artifactId, Instant observedAt, Instant expiresAt) {
            delegate.protectForAdmission(artifactId, observedAt, expiresAt);
        }

        @Override
        public void releaseAdmissionProtection(String artifactId) {
            delegate.releaseAdmissionProtection(artifactId);
        }

        @Override
        public boolean delete(String artifactId) {
            return delegate.delete(artifactId);
        }

        @Override
        public long countByKekId(String kekId) {
            return delegate.countByKekId(kekId);
        }
    }

    private static final class FailFirstInsertEnvelopeStore extends DelegatingEnvelopeStore {
        private boolean armed = true;

        private FailFirstInsertEnvelopeStore(ArtifactEnvelopeStore delegate) {
            super(delegate);
        }

        @Override
        public void insert(ArtifactEnvelope envelope) {
            if (armed) {
                armed = false;
                throw new InferenceStorageException(
                        "STORAGE_INJECTED_METADATA_FAILURE", "Synthetic metadata failure", null
                );
            }
            super.insert(envelope);
        }
    }

    private static final class FailOnceAfterCommitEnvelopeStore extends DelegatingEnvelopeStore {
        private boolean armed = true;

        private FailOnceAfterCommitEnvelopeStore(ArtifactEnvelopeStore delegate) {
            super(delegate);
        }

        @Override
        public <T> T withArtifactLock(String artifactId, Supplier<T> work) {
            var result = super.withArtifactLock(artifactId, work);
            if (armed) {
                armed = false;
                throw new InferenceStorageException(
                        "STORAGE_INJECTED_RESPONSE_LOSS", "Synthetic response loss", null
                );
            }
            return result;
        }
    }
}
