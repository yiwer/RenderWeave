package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.InferenceStorageException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;

interface ArtifactKekRing {
    String currentKeyId();

    SecretKey require(String keyId);

    static ArtifactKekRing of(String currentKeyId, Map<String, byte[]> keys) {
        ArtifactEnvelope.requireKeyId(currentKeyId);
        if (keys == null || keys.isEmpty() || keys.size() > 8) {
            throw new IllegalArgumentException("KEK ring must contain 1..8 keys");
        }
        var material = new LinkedHashMap<String, SecretKey>();
        for (var entry : keys.entrySet()) {
            var keyId = ArtifactEnvelope.requireKeyId(entry.getKey());
            var bytes = entry.getValue();
            if (bytes == null || bytes.length != 32) {
                throw new IllegalArgumentException("Every artifact KEK must contain exactly 32 bytes");
            }
            material.put(keyId, new SecretKeySpec(bytes.clone(), "AES"));
        }
        if (!material.containsKey(currentKeyId)) {
            throw new IllegalArgumentException("Current artifact KEK is absent from the ring");
        }
        var immutable = Map.copyOf(material);
        return new ArtifactKekRing() {
            @Override
            public String currentKeyId() {
                return currentKeyId;
            }

            @Override
            public SecretKey require(String keyId) {
                var key = immutable.get(keyId);
                if (key == null) {
                    throw new InferenceStorageException(
                            "STORAGE_KEK_UNAVAILABLE",
                            "The required artifact key-encryption key is unavailable",
                            null
                    );
                }
                return key;
            }
        };
    }
}
