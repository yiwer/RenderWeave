package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.spi.CapabilityStateStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * CapabilityStateStore app Adapter（ADR-0044 §5）：closed 三操作 + 加密落盘。state 以
 * AES-GCM-256 加密，96-bit GCM nonce 由 HMAC-SHA256(key, id || fingerprint) 派生——
 * server-only 秘密，不明文入库；rendering 模块不碰 JDBC/加密实现。固定 TTL 过期由
 * 清扫器处理，不续期。
 */
public final class PostgresCapabilityStateStore implements CapabilityStateStore {

    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_NONCE_BYTES = 12;

    private final JdbcClient jdbc;
    private final SecretKey key;
    private final TransactionTemplate transactions;
    private final SecureRandom identityEntropy = new SecureRandom();

    public PostgresCapabilityStateStore(
            JdbcClient jdbc,
            SecretKey key,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.key = key;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public SaveOutcome save(SaveRequest request) {
        try {
            return transactions.execute(status -> saveLinearized(request));
        } catch (DataAccessException | StateSecurityFailure unavailable) {
            return new SaveOutcome.SaveUnavailable();
        }
    }

    private SaveOutcome saveLinearized(SaveRequest request) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:requestId, 125))")
                .param("requestId", request.renderRequestId().value())
                .query((rs, rowNumber) -> Boolean.TRUE)
                .single();
        try {
            var existing = jdbc.sql("""
                            select capability_state_id, evaluation_fingerprint, expires_at
                            from rendering_capability_state
                            where render_request_id = :renderRequestId
                            for update
                            """)
                    .param("renderRequestId", request.renderRequestId().value())
                    .query((rs, rowNum) -> new ExistingRecord(
                            rs.getString("capability_state_id"),
                            rs.getString("evaluation_fingerprint"),
                            rs.getLong("expires_at")))
                    .stream()
                    .findFirst();
            var nowMillis = System.currentTimeMillis();
            if (existing.isPresent()) {
                var record = existing.get();
                if (record.expiresAt() <= nowMillis) {
                    jdbc.sql("""
                            delete from rendering_capability_state
                            where capability_state_id = :id
                            """)
                            .param("id", record.id())
                            .update();
                } else if (record.fingerprint().equals(request.evaluationFingerprint())) {
                    return new SaveOutcome.Replayed(new CapabilityStateId(record.id()));
                } else {
                    return new SaveOutcome.FingerprintConflict();
                }
            }
            var id = newIdentity();
            var cipher = encrypt(id, request.evaluationFingerprint(), request.sealedState());
            jdbc.sql("""
                    insert into rendering_capability_state
                        (capability_state_id, render_request_id, evaluation_fingerprint,
                         state_cipher, issued_at, expires_at)
                    values (:id, :renderRequestId, :fingerprint, :cipher, :issuedAt, :expiresAt)
                    """)
                    .param("id", id)
                    .param("renderRequestId", request.renderRequestId().value())
                    .param("fingerprint", request.evaluationFingerprint())
                    .param("cipher", cipher)
                    .param("issuedAt", request.issuedAtEpochMilli())
                    .param("expiresAt", request.expiresAtEpochMilli())
                    .update();
            return new SaveOutcome.Stored(new CapabilityStateId(id));
        } catch (GeneralSecurity unavailable) {
            throw new StateSecurityFailure(unavailable);
        }
    }

    @Override
    public LoadOutcome load(cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId renderRequestId,
                            String evaluationFingerprint) {
        try {
            var found = jdbc.sql("""
                            select capability_state_id, evaluation_fingerprint, state_cipher, expires_at
                            from rendering_capability_state
                            where render_request_id = :renderRequestId
                            """)
                    .param("renderRequestId", renderRequestId.value())
                    .query((rs, rowNum) -> new StoredCipher(
                            rs.getString("capability_state_id"),
                            rs.getString("evaluation_fingerprint"),
                            rs.getBytes("state_cipher"),
                            rs.getLong("expires_at")))
                    .stream()
                    .findFirst();
            if (found.isEmpty()) {
                return new LoadOutcome.Missing();
            }
            var record = found.get();
            var nowMillis = System.currentTimeMillis();
            if (record.expiresAt() <= nowMillis) {
                return new LoadOutcome.Missing();
            }
            if (!record.fingerprint().equals(evaluationFingerprint)) {
                return new LoadOutcome.LoadFingerprintConflict();
            }
            var state = decrypt(record.id(), evaluationFingerprint, record.cipher());
            return new LoadOutcome.Loaded(state, record.expiresAt());
        } catch (DataAccessException unavailable) {
            return new LoadOutcome.LoadUnavailable();
        } catch (GeneralSecurity fault) {
            return new LoadOutcome.Missing();
        }
    }

    /** 固定 TTL 过期清扫：只删过期行，不续期、不读取明文。 */
    public int sweepExpired() {
        var nowMillis = System.currentTimeMillis();
        return jdbc.sql("""
                        delete from rendering_capability_state
                        where expires_at <= :now
                        """)
                .param("now", nowMillis)
                .update();
    }

    private byte[] encrypt(String id, String fingerprint, byte[] state) throws GeneralSecurity {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            var nonce = derivedNonce(id, fingerprint);
            cipher.init(Cipher.ENCRYPT_MODE, key,
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(fingerprint.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(state);
        } catch (java.security.GeneralSecurityException e) {
            throw new GeneralSecurity(e);
        }
    }

    private byte[] decrypt(String id, String fingerprint, byte[] cipherBytes)
            throws GeneralSecurity {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            var nonce = derivedNonce(id, fingerprint);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(fingerprint.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(cipherBytes);
        } catch (java.security.GeneralSecurityException e) {
            throw new GeneralSecurity(e);
        }
    }

    private byte[] derivedNonce(String id, String fingerprint) throws GeneralSecurity {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(id.getBytes(StandardCharsets.UTF_8));
            mac.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            var full = mac.doFinal();
            var nonce = new byte[GCM_NONCE_BYTES];
            System.arraycopy(full, 0, nonce, 0, GCM_NONCE_BYTES);
            return nonce;
        } catch (java.security.GeneralSecurityException e) {
            throw new GeneralSecurity(e);
        }
    }

    private String newIdentity() {
        var bytes = new byte[32];
        identityEntropy.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record ExistingRecord(String id, String fingerprint, long expiresAt) {
    }

    private record StoredCipher(String id, String fingerprint, byte[] cipher, long expiresAt) {
    }

    private static final class GeneralSecurity extends Exception {
        GeneralSecurity(Throwable cause) {
            super(cause);
        }
    }

    private static final class StateSecurityFailure extends RuntimeException {
        StateSecurityFailure(Throwable cause) {
            super(cause);
        }
    }
}
