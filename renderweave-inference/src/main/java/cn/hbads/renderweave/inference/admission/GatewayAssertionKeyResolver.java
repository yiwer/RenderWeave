package cn.hbads.renderweave.inference.admission;

import java.security.PublicKey;
import java.util.Optional;

@FunctionalInterface
public interface GatewayAssertionKeyResolver {
    Optional<PublicKey> resolve(String keyId);
}
