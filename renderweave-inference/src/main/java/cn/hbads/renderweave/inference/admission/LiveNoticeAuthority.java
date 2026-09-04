package cn.hbads.renderweave.inference.admission;

import java.util.Optional;

/**
 * Resolves the currently configured external-transfer notice identity for a confirmed route.
 * Absent until the production admission configuration is wired; call authorization fails closed
 * when a first dispatch needs a notice identity that cannot be established.
 */
@FunctionalInterface
public interface LiveNoticeAuthority {
    Optional<ExternalTransferNotice.Identity> currentNotice(String profileId, String locale);

    static LiveNoticeAuthority absent() {
        return (profileId, locale) -> Optional.empty();
    }
}
