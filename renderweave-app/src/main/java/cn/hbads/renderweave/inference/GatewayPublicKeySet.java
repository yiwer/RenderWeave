package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.GatewayAssertionKeyResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Immutable Ed25519 public-key ring loaded without following key-file symlinks. */
final class GatewayPublicKeySet implements GatewayAssertionKeyResolver {
    private static final int MAXIMUM_KEYS = 8;
    private static final int MAXIMUM_PEM_BYTES = 8 * 1024;
    private final Map<String, PublicKey> keys;

    private GatewayPublicKeySet(Map<String, PublicKey> keys) {
        this.keys = Map.copyOf(keys);
    }

    static GatewayPublicKeySet load(Path directory) {
        try {
            var normalized = directory.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(normalized)) {
                throw new IllegalArgumentException("Gateway public-key directory is invalid");
            }
            final java.util.List<Path> paths;
            try (var stream = Files.list(normalized)) {
                paths = stream
                        .filter(path -> path.getFileName().toString().endsWith(".pem"))
                        .sorted()
                        .toList();
            }
            if (paths.isEmpty() || paths.size() > MAXIMUM_KEYS) {
                throw new IllegalArgumentException("Gateway public-key count is invalid");
            }
            var loaded = new LinkedHashMap<String, PublicKey>();
            for (var path : paths) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(path) || Files.size(path) > MAXIMUM_PEM_BYTES) {
                    throw new IllegalArgumentException("Gateway public-key file is invalid");
                }
                var filename = path.getFileName().toString();
                var keyId = filename.substring(0, filename.length() - ".pem".length());
                if (!keyId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                    throw new IllegalArgumentException("Gateway public-key id is invalid");
                }
                var pem = Files.readString(path, StandardCharsets.US_ASCII);
                var encoded = pem
                        .replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s", "");
                var key = KeyFactory.getInstance("Ed25519").generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(encoded))
                );
                loaded.put(keyId, key);
            }
            return new GatewayPublicKeySet(loaded);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Gateway public-key set cannot be loaded", failure);
        }
    }

    @Override
    public Optional<PublicKey> resolve(String keyId) {
        return Optional.ofNullable(keys.get(keyId));
    }
}
