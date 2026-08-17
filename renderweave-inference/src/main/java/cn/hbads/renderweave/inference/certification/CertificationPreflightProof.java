package cn.hbads.renderweave.inference.certification;

import java.util.UUID;

/**
 * Side-effect-free P0 proof. This object never grants Provider egress; P1 must add an
 * atomic persistent usage ledger before an authorized runner can exist.
 */
public record CertificationPreflightProof(
        String authorizationId,
        UUID cycleId,
        CertificationStage stage,
        String profileSha256,
        String manifestIdentity,
        int providerAttempts,
        int providerReservations,
        long providerCostMicrosCny,
        int apiKeyReads,
        boolean grantsProviderEgress
) {
    public CertificationPreflightProof {
        if (providerAttempts != 0 || providerReservations != 0
                || providerCostMicrosCny != 0 || apiKeyReads != 0 || grantsProviderEgress) {
            throw new IllegalArgumentException("CERTIFICATION_P0_PROOF_MUST_BE_PROVIDER_ZERO");
        }
    }
}
