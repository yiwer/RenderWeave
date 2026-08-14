package cn.hbads.renderweave.inference.eval.visual.quality;

import java.util.List;
import java.util.Objects;

/** Joins the frozen offline evidence into the sole R2-R5 decision seam. */
public final class OfflineQualityDecisionAssembler {
    public Bundle assemble(
            RapidOcrCausalEvidencePack rapidOcr,
            ChallengerCapabilityAdmission challengers,
            R3OrderRepeatProbeEvidence r3,
            R5OracleProbeEvidence r5,
            ComponentEvidenceAuthority componentEvidenceAuthority
    ) {
        Objects.requireNonNull(rapidOcr, "rapidOcr");
        Objects.requireNonNull(challengers, "challengers");
        Objects.requireNonNull(r3, "r3");
        Objects.requireNonNull(r5, "r5");
        var protocol = OfflineQualityEvaluationProtocol.load();
        if (!protocol.identity().equals(rapidOcr.protocolIdentity())
                || !protocol.identity().equals(r3.protocolIdentity())
                || !protocol.identity().equals(r5.protocolIdentity())
                || !rapidOcr.evaluationIdentity().equals(r3.sourceEvaluationIdentity())
                || !rapidOcr.corpusIdentity().equals(r5.corpusIdentity())
                || !rapidOcr.annotationSetIdentity().equals(r5.annotationSetIdentity())
                || !rapidOcr.capabilityIdentity().equals(r5.capabilityIdentity())
                || !rapidOcr.acquisitionPolicyIdentity().equals(r5.acquisitionPolicyIdentity())) {
            throw new IllegalArgumentException("QUALITY_REPAIR_OFFLINE_EVIDENCE_IDENTITY_DRIFT");
        }
        if (!rapidOcr.externalProviderUsage().zeroUsage()
                || !r3.externalProviderUsage().zeroUsage()
                || !r5.externalProviderUsage().zeroUsage()) {
            throw new IllegalArgumentException("QUALITY_REPAIR_OFFLINE_PROVIDER_USAGE_NONZERO");
        }
        if (challengers.optionalThirdChallenger()
                != ChallengerCapabilityAdmission.OptionalThirdChallenger.NONE) {
            throw new IllegalArgumentException("QUALITY_REPAIR_OPTIONAL_CHALLENGER_DRIFT");
        }

        var rapidIdentity = new RapidOcrCausalEvidencePackJsonCodec().evidenceIdentity(rapidOcr);
        var r3Identity = new R3OrderRepeatProbeEvidenceJsonCodec().evidenceIdentity(r3);
        var r5Identity = new R5OracleProbeEvidenceJsonCodec().evidenceIdentity(r5);
        var verifiedComponents = readComponentVerifications(
                componentEvidenceAuthority, rapidIdentity, r3Identity, r5Identity);
        var capabilityAdmitted = challengers.challengers().stream().allMatch(item ->
                item.admissionDisposition() == ChallengerCapabilityAdmission.AdmissionDisposition.ADMITTED
                        && item.executable() && item.missingAdmissionDimensions().isEmpty());
        var evidencePack = new FrozenQualityEvidencePack(
                FrozenQualityEvidencePack.VERSION,
                FrozenQualityEvidencePack.BASE_REVISION,
                FrozenQualityEvidencePack.N7_04_EVIDENCE_AUTHORITY_SHA256,
                FrozenQualityEvidencePack.N7_04_AUDIT_SHA256,
                FrozenQualityEvidencePack.N7Decision.FAIL,
                FrozenQualityEvidencePack.AuthorizationStatus.CLOSED,
                FrozenQualityEvidencePack.N7DependencyStatus.BLOCKED,
                verifiedComponents,
                List.of(
                        new FrozenQualityEvidencePack.RouteEvidence(
                                FrozenQualityEvidencePack.Route.R2,
                                List.of(
                                        predicate("R2_CAPABILITY_ADMITTED",
                                                capabilityAdmitted
                                                        ? FrozenQualityEvidencePack.PredicateResult.PASS
                                                        : FrozenQualityEvidencePack.PredicateResult.MISSING,
                                                capabilityAdmitted
                                                        ? "R2_CAPABILITY_ADMITTED"
                                                        : "R2_CAPABILITY_NOT_ADMITTED",
                                                challengers.identity()),
                                        predicate("R2_SHADOW_NET_BENEFIT",
                                                FrozenQualityEvidencePack.PredicateResult.MISSING,
                                                "R2_SHADOW_NOT_RUN",
                                                protocol.identity()),
                                        predicate("R2_STABLE_PERCEPTION_GAP",
                                                FrozenQualityEvidencePack.PredicateResult.PASS,
                                                "R2_RAPIDOCR_STABLE_GAP_PRESENT",
                                                rapidIdentity))),
                        new FrozenQualityEvidencePack.RouteEvidence(
                                FrozenQualityEvidencePack.Route.R3,
                                List.of(predicate("R3_CAUSAL_ORDER_REPEAT_DEFECT",
                                        routeResult(r3.disposition()),
                                        r3.reasonCode(),
                                        r3Identity))),
                        new FrozenQualityEvidencePack.RouteEvidence(
                                FrozenQualityEvidencePack.Route.R4,
                                List.of(predicate("R4_SHAPE_CODEC_BOTTLENECK",
                                        FrozenQualityEvidencePack.PredicateResult.FAIL,
                                        "R4_SEMANTIC_BOTTLENECK_DOMINATES",
                                        "sha256:" + FrozenQualityEvidencePack.N7_04_AUDIT_SHA256))),
                        new FrozenQualityEvidencePack.RouteEvidence(
                                FrozenQualityEvidencePack.Route.R5,
                                List.of(predicate("R5_STATIC_VIEW_CAUSAL_GAIN",
                                        routeResult(r5.disposition()),
                                        r5.reasonCode(),
                                        r5Identity)))
                ),
                List.of(),
                new FrozenQualityEvidencePack.ExternalProviderUsage(0, 0, 0));
        var decision = new R2R5TriggerDecisionEngine().decide(evidencePack);
        var packCodec = new FrozenQualityEvidencePackJsonCodec();
        var decisionCodec = new R2R5TriggerDecisionJsonCodec();
        var encodedPack = packCodec.write(evidencePack);
        var packIdentity = packCodec.evidencePackIdentity(evidencePack);
        var encodedDecision = decisionCodec.write(decision);
        var decisionIdentity = decisionCodec.decisionIdentity(decision);
        packCodec.read(encodedPack, packIdentity);
        decisionCodec.read(encodedDecision, decisionIdentity);
        return new Bundle(
                evidencePack, packIdentity, encodedPack,
                decision, decisionIdentity, encodedDecision);
    }

    private static List<FrozenQualityEvidencePack.ComponentVerification> readComponentVerifications(
            ComponentEvidenceAuthority source,
            String rapidIdentity,
            String r3Identity,
            String r5Identity
    ) {
        source = Objects.requireNonNull(source, "componentEvidenceAuthority");
        var reader = new OfflineComponentVerificationReader();
        return List.of(
                reader.read(
                        FrozenQualityEvidencePack.Component.RAPIDOCR_CAUSAL,
                        source.rapidOcrEvidence(), rapidIdentity,
                        source.rapidOcrVerificationSummary(), source.expectedRepositoryRevision()),
                reader.read(
                        FrozenQualityEvidencePack.Component.R3_PROBE,
                        source.r3Evidence(), r3Identity,
                        source.r3VerificationSummary(), source.expectedRepositoryRevision()),
                reader.read(
                        FrozenQualityEvidencePack.Component.R5_PROBE,
                        source.r5Evidence(), r5Identity,
                        source.r5VerificationSummary(), source.expectedRepositoryRevision()));
    }

    private static FrozenQualityEvidencePack.PredicateResult routeResult(
            R3OrderRepeatProbeEvidence.Disposition disposition
    ) {
        return switch (disposition) {
            case TRIGGERED -> FrozenQualityEvidencePack.PredicateResult.PASS;
            case NOT_TRIGGERED -> FrozenQualityEvidencePack.PredicateResult.FAIL;
            case MISSING -> FrozenQualityEvidencePack.PredicateResult.MISSING;
        };
    }

    private static FrozenQualityEvidencePack.PredicateResult routeResult(
            R5OracleProbeEvidence.Disposition disposition
    ) {
        return switch (disposition) {
            case TRIGGERED -> FrozenQualityEvidencePack.PredicateResult.PASS;
            case NOT_TRIGGERED -> FrozenQualityEvidencePack.PredicateResult.FAIL;
            case MISSING -> FrozenQualityEvidencePack.PredicateResult.MISSING;
        };
    }

    private static FrozenQualityEvidencePack.PredicateEvidence predicate(
            String predicateId,
            FrozenQualityEvidencePack.PredicateResult result,
            String reasonCode,
            String evidenceReference
    ) {
        return new FrozenQualityEvidencePack.PredicateEvidence(
                predicateId, "A1_A2", result, reasonCode, evidenceReference);
    }

    public record Bundle(
            FrozenQualityEvidencePack evidencePack,
            String evidencePackIdentity,
            byte[] encodedEvidencePack,
            R2R5TriggerDecision decision,
            String decisionIdentity,
            byte[] encodedDecision
    ) {
        public Bundle {
            Objects.requireNonNull(evidencePack, "evidencePack");
            Objects.requireNonNull(decision, "decision");
            if (evidencePackIdentity == null || !evidencePackIdentity.matches(
                    "renderweave-frozen-quality-evidence-pack/1\\.0:[0-9a-f]{64}")
                    || decisionIdentity == null || !decisionIdentity.matches(
                    "renderweave-r2r5-trigger-decision/1\\.0:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("QUALITY_REPAIR_BUNDLE_IDENTITY_INVALID");
            }
            encodedEvidencePack = Objects.requireNonNull(encodedEvidencePack, "encodedEvidencePack").clone();
            encodedDecision = Objects.requireNonNull(encodedDecision, "encodedDecision").clone();
            if (encodedEvidencePack.length == 0 || encodedDecision.length == 0) {
                throw new IllegalArgumentException("QUALITY_REPAIR_BUNDLE_BYTES_INVALID");
            }
        }

        @Override
        public byte[] encodedEvidencePack() { return encodedEvidencePack.clone(); }

        @Override
        public byte[] encodedDecision() { return encodedDecision.clone(); }
    }

    /** Raw evidence authority; only the assembler can turn these bytes into PASS verifications. */
    public record ComponentEvidenceAuthority(
            byte[] rapidOcrEvidence,
            byte[] rapidOcrVerificationSummary,
            byte[] r3Evidence,
            byte[] r3VerificationSummary,
            byte[] r5Evidence,
            byte[] r5VerificationSummary,
            String expectedRepositoryRevision
    ) {
        public ComponentEvidenceAuthority {
            rapidOcrEvidence = copy(rapidOcrEvidence);
            rapidOcrVerificationSummary = copy(rapidOcrVerificationSummary);
            r3Evidence = copy(r3Evidence);
            r3VerificationSummary = copy(r3VerificationSummary);
            r5Evidence = copy(r5Evidence);
            r5VerificationSummary = copy(r5VerificationSummary);
            if (expectedRepositoryRevision == null
                    || !expectedRepositoryRevision.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("QUALITY_REPAIR_COMPONENT_REVISION_INVALID");
            }
        }

        @Override public byte[] rapidOcrEvidence() { return rapidOcrEvidence.clone(); }
        @Override public byte[] rapidOcrVerificationSummary() {
            return rapidOcrVerificationSummary.clone();
        }
        @Override public byte[] r3Evidence() { return r3Evidence.clone(); }
        @Override public byte[] r3VerificationSummary() { return r3VerificationSummary.clone(); }
        @Override public byte[] r5Evidence() { return r5Evidence.clone(); }
        @Override public byte[] r5VerificationSummary() { return r5VerificationSummary.clone(); }

        private static byte[] copy(byte[] value) {
            return Objects.requireNonNull(value, "component evidence bytes").clone();
        }
    }
}
