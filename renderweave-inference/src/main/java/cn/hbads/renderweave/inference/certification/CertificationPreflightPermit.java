package cn.hbads.renderweave.inference.certification;

import java.util.UUID;

/** A side-effect-free stage permit. Provider accounting remains zero until a later authorized runner exists. */
public record CertificationPreflightPermit(
        String authorizationId,
        UUID cycleId,
        CertificationStage stage,
        String profileSha256,
        String manifestIdentity,
        int providerAttempts,
        int providerReservations,
        long providerCostMicrosCny,
        int apiKeyReads
) { }
