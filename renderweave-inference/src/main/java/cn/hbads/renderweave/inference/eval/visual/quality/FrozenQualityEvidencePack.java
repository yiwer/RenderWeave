package cn.hbads.renderweave.inference.eval.visual.quality;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Payload-safe, content-addressable input to the visual quality repair decision seam. */
public record FrozenQualityEvidencePack(
        String contractVersion,
        String baseRevision,
        String n704EvidenceAuthoritySha256,
        String n704AuditSha256,
        N7Decision n704Decision,
        AuthorizationStatus n704AuthorizationStatus,
        N7DependencyStatus n705DependencyStatus,
        List<ComponentVerification> componentVerifications,
        List<RouteEvidence> routes,
        List<SuccessorIdentity> successorIdentities,
        ExternalProviderUsage externalProviderUsage
) {
    public static final String VERSION = "renderweave-frozen-quality-evidence-pack/1.0";
    public static final String BASE_REVISION = "604849e9b400abf98bca9c12951a50b1488f043b";
    public static final String N7_04_EVIDENCE_AUTHORITY_SHA256 =
            "e2cb4a0455f712b35618f8239e369e3a92bbd50a5a274d24a6eb39ee6734b78f";
    public static final String N7_04_AUDIT_SHA256 =
            "e1f550b28e7c57fd4944c3b83297e8c85a167ba147683e4aff655b00f0a59655";
    public static final String N7_04_AUTHORIZATION_ID = "n7-04-plus-canary-product-v45-20260814e";
    public static final String N7_04_CONTRACT_IDENTITY =
            "renderweave-n7-live-ticket-contract/1.0:"
                    + "caa98a6831d5a5e8dd263265822c4568d1b26117f0d438c8a90dabdf4f422843";
    public static final String N7_04_EVALUATION_IDENTITY =
            "renderweave-visual-evaluation-tree-sha256/2:"
                    + "cfa3e9708b031f9383195edcd3e4a04a447982a7633d1fe9bc95ec27d2c5c650";

    private static final String SUCCESSOR_SPEC_REFERENCE =
            "spec-sha256:4632b609d4ce5726b0671e8a56fc6674e182f22152231b727622662e14b50a0e";
    private static final String RAPIDOCR_SHADOW_REPORT_REFERENCE =
            "renderweave-rapidocr-shadow-report/1.0:"
                    + "fc2cc3523ba59e9832ba8eb6fa651fd2fac9088751a4b9b72c7fa4bab476f8a5";
    private static final Set<String> HISTORICAL_IDENTITIES = Set.of(
            "N7-04",
            "N7-05",
            N7_04_AUTHORIZATION_ID,
            N7_04_CONTRACT_IDENTITY,
            N7_04_EVALUATION_IDENTITY
    );

    public FrozenQualityEvidencePack {
        if (!VERSION.equals(contractVersion)) {
            throw invalid("QUALITY_REPAIR_EVIDENCE_PACK_VERSION_INVALID");
        }
        if (!BASE_REVISION.equals(baseRevision)) {
            throw invalid("QUALITY_REPAIR_BASE_REVISION_DRIFT");
        }
        if (!N7_04_EVIDENCE_AUTHORITY_SHA256.equals(n704EvidenceAuthoritySha256)
                || !N7_04_AUDIT_SHA256.equals(n704AuditSha256)) {
            throw invalid("QUALITY_REPAIR_N7_AUTHORITY_DIGEST_DRIFT");
        }
        if (n704Decision != N7Decision.FAIL
                || n704AuthorizationStatus != AuthorizationStatus.CLOSED
                || n705DependencyStatus != N7DependencyStatus.BLOCKED) {
            throw invalid("QUALITY_REPAIR_N7_AUTHORITY_STATE_DRIFT");
        }
        componentVerifications = canonicalComponentVerifications(componentVerifications);
        routes = canonicalRoutes(routes);
        successorIdentities = canonicalSuccessorIdentities(successorIdentities);
        externalProviderUsage = Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
        if (!externalProviderUsage.zeroUsage()) {
            throw invalid("QUALITY_REPAIR_EXTERNAL_PROVIDER_USAGE_NONZERO");
        }
    }

    public static FrozenQualityEvidencePack initial() {
        return new FrozenQualityEvidencePack(
                VERSION,
                BASE_REVISION,
                N7_04_EVIDENCE_AUTHORITY_SHA256,
                N7_04_AUDIT_SHA256,
                N7Decision.FAIL,
                AuthorizationStatus.CLOSED,
                N7DependencyStatus.BLOCKED,
                List.of(
                        ComponentVerification.missing(Component.RAPIDOCR_CAUSAL),
                        ComponentVerification.missing(Component.R3_PROBE),
                        ComponentVerification.missing(Component.R5_PROBE)),
                List.of(
                        new RouteEvidence(Route.R2, List.of(
                                predicate("R2_CAPABILITY_ADMITTED", PredicateResult.MISSING,
                                        "R2_CAPABILITY_EVIDENCE_MISSING", SUCCESSOR_SPEC_REFERENCE),
                                predicate("R2_SHADOW_NET_BENEFIT", PredicateResult.MISSING,
                                        "R2_SHADOW_QUALIFICATION_MISSING", SUCCESSOR_SPEC_REFERENCE),
                                predicate("R2_STABLE_PERCEPTION_GAP", PredicateResult.PASS,
                                        "R2_RAPIDOCR_STABLE_GAP_PRESENT", RAPIDOCR_SHADOW_REPORT_REFERENCE)
                        )),
                        new RouteEvidence(Route.R3, List.of(
                                predicate("R3_CAUSAL_ORDER_REPEAT_DEFECT", PredicateResult.MISSING,
                                        "R3_CAUSAL_EVIDENCE_MISSING", SUCCESSOR_SPEC_REFERENCE)
                        )),
                        new RouteEvidence(Route.R4, List.of(
                                predicate("R4_SHAPE_CODEC_BOTTLENECK", PredicateResult.FAIL,
                                        "R4_SEMANTIC_BOTTLENECK_DOMINATES",
                                        "sha256:" + N7_04_AUDIT_SHA256)
                        )),
                        new RouteEvidence(Route.R5, List.of(
                                predicate("R5_STATIC_VIEW_CAUSAL_GAIN", PredicateResult.MISSING,
                                        "R5_ORACLE_DIFFERENTIAL_MISSING", SUCCESSOR_SPEC_REFERENCE)
                        ))
                ),
                List.of(),
                new ExternalProviderUsage(0, 0, 0));
    }

    private static PredicateEvidence predicate(
            String predicateId,
            PredicateResult result,
            String reasonCode,
            String evidenceReference
    ) {
        return new PredicateEvidence(predicateId, "A1_A2", result, reasonCode, evidenceReference);
    }

    private static List<RouteEvidence> canonicalRoutes(List<RouteEvidence> source) {
        var result = new ArrayList<>(List.copyOf(Objects.requireNonNull(source, "routes")));
        if (result.size() != Route.values().length || result.stream().anyMatch(Objects::isNull)
                || !EnumSet.copyOf(result.stream().map(RouteEvidence::route).toList())
                .equals(EnumSet.allOf(Route.class))) {
            throw invalid("QUALITY_REPAIR_ROUTE_SET_INVALID");
        }
        result.sort(Comparator.comparing(RouteEvidence::route));
        return List.copyOf(result);
    }

    private static List<ComponentVerification> canonicalComponentVerifications(
            List<ComponentVerification> source
    ) {
        var result = new ArrayList<>(List.copyOf(
                Objects.requireNonNull(source, "componentVerifications")));
        if (result.size() != Component.values().length || result.stream().anyMatch(Objects::isNull)
                || !EnumSet.copyOf(result.stream().map(ComponentVerification::component).toList())
                .equals(EnumSet.allOf(Component.class))) {
            throw invalid("QUALITY_REPAIR_COMPONENT_VERIFICATION_SET_INVALID");
        }
        result.sort(Comparator.comparing(ComponentVerification::component));
        var revisions = result.stream().filter(item -> item.result() == VerificationResult.PASS)
                .map(ComponentVerification::repositoryRevision).distinct().count();
        if (revisions > 1) {
            throw invalid("QUALITY_REPAIR_COMPONENT_REVISION_DRIFT");
        }
        return List.copyOf(result);
    }

    private static List<SuccessorIdentity> canonicalSuccessorIdentities(List<SuccessorIdentity> source) {
        var result = new ArrayList<>(List.copyOf(Objects.requireNonNull(source, "successorIdentities")));
        if (result.stream().anyMatch(Objects::isNull)) {
            throw invalid("QUALITY_REPAIR_SUCCESSOR_IDENTITY_INVALID");
        }
        var unique = new HashSet<String>();
        for (var item : result) {
            if (HISTORICAL_IDENTITIES.contains(item.value())) {
                throw invalid("QUALITY_REPAIR_HISTORICAL_IDENTITY_REUSED");
            }
            if (!unique.add(item.kind() + "\u0000" + item.value())) {
                throw invalid("QUALITY_REPAIR_SUCCESSOR_IDENTITY_DUPLICATE");
            }
        }
        result.sort(Comparator.comparing(SuccessorIdentity::kind).thenComparing(SuccessorIdentity::value));
        return List.copyOf(result);
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public enum Route { R2, R3, R4, R5 }

    public enum PredicateResult { PASS, FAIL, MISSING }

    public enum N7Decision { FAIL, PASS }

    public enum AuthorizationStatus { CLOSED, OPEN }

    public enum N7DependencyStatus { BLOCKED, READY }

    public enum SuccessorIdentityKind { TICKET, AUTHORIZATION, CONTRACT, EVALUATION }

    public enum Component {
        RAPIDOCR_CAUSAL(
                "renderweave-rapidocr-causal-evidence/1.0",
                "renderweave-rapidocr-causal-evidence-envelope/1.0",
                "renderweave-vrq04-causal-verifier/1.0"),
        R3_PROBE(
                "renderweave-r3-order-repeat-probe-evidence/1.0",
                "renderweave-r3-order-repeat-probe-envelope/1.0",
                "renderweave-vrq05-r3-verifier/1.0"),
        R5_PROBE(
                "renderweave-r5-oracle-probe-evidence/1.0",
                "renderweave-r5-oracle-probe-envelope/1.0",
                "renderweave-vrq06-r5-verifier/1.0");

        private final String evidenceIdentityVersion;
        private final String evidenceEnvelopeVersion;
        private final String verifierVersion;

        Component(String evidenceIdentityVersion, String evidenceEnvelopeVersion, String verifierVersion) {
            this.evidenceIdentityVersion = evidenceIdentityVersion;
            this.evidenceEnvelopeVersion = evidenceEnvelopeVersion;
            this.verifierVersion = verifierVersion;
        }

        public String evidenceIdentityVersion() { return evidenceIdentityVersion; }

        public String evidenceEnvelopeVersion() { return evidenceEnvelopeVersion; }

        public String verifierVersion() { return verifierVersion; }
    }

    public enum VerificationResult { PASS, MISSING }

    public record PredicateEvidence(
            String predicateId,
            String expectedEvidenceClass,
            PredicateResult result,
            String reasonCode,
            String evidenceReference
    ) {
        public PredicateEvidence {
            if (predicateId == null || !predicateId.matches("R[2-5]_[A-Z0-9_]{1,120}")) {
                throw invalid("QUALITY_REPAIR_PREDICATE_ID_INVALID");
            }
            if (expectedEvidenceClass == null
                    || !expectedEvidenceClass.matches("[A-Z][A-Z0-9_]{0,31}")) {
                throw invalid("QUALITY_REPAIR_EVIDENCE_CLASS_INVALID");
            }
            Objects.requireNonNull(result, "result");
            if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
                throw invalid("QUALITY_REPAIR_REASON_CODE_INVALID");
            }
            if (evidenceReference == null
                    || !evidenceReference.matches("[a-z][A-Za-z0-9._/-]{0,127}:[0-9a-f]{64}")) {
                throw invalid("QUALITY_REPAIR_EVIDENCE_REFERENCE_INVALID");
            }
        }
    }

    public record ComponentVerification(
            Component component,
            String evidenceIdentity,
            String evidenceSha256,
            String verificationSummarySha256,
            String verifierVersion,
            String assurance,
            String repositoryRevision,
            VerificationResult result,
            ExternalProviderUsage externalProviderUsage
    ) {
        private static final String A2_ASSURANCE = "A2_CROSS_IMPLEMENTATION_RECOMPUTE";

        public ComponentVerification {
            Objects.requireNonNull(component, "component");
            Objects.requireNonNull(result, "result");
            externalProviderUsage = Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
            if (evidenceIdentity == null || !evidenceIdentity.matches(
                    java.util.regex.Pattern.quote(component.evidenceIdentityVersion()) + ":[0-9a-f]{64}")) {
                throw invalid("QUALITY_REPAIR_COMPONENT_EVIDENCE_IDENTITY_INVALID");
            }
            if (!isSha256(evidenceSha256) || !isSha256(verificationSummarySha256)
                    || !component.verifierVersion().equals(verifierVersion)
                    || repositoryRevision == null || !repositoryRevision.matches("[0-9a-f]{40}")
                    || !externalProviderUsage.zeroUsage()) {
                throw invalid("QUALITY_REPAIR_COMPONENT_VERIFICATION_INVALID");
            }
            if (result == VerificationResult.PASS && !A2_ASSURANCE.equals(assurance)
                    || result == VerificationResult.MISSING && !"MISSING".equals(assurance)) {
                throw invalid("QUALITY_REPAIR_COMPONENT_ASSURANCE_INVALID");
            }
        }

        static ComponentVerification missing(Component component) {
            return new ComponentVerification(
                    component,
                    component.evidenceIdentityVersion() + ":" + "0".repeat(64),
                    "0".repeat(64),
                    "0".repeat(64),
                    component.verifierVersion(),
                    "MISSING",
                    BASE_REVISION,
                    VerificationResult.MISSING,
                    new ExternalProviderUsage(0, 0, 0));
        }

        private static boolean isSha256(String value) {
            return value != null && value.matches("[0-9a-f]{64}");
        }
    }

    public record RouteEvidence(Route route, List<PredicateEvidence> predicates) {
        public RouteEvidence {
            Objects.requireNonNull(route, "route");
            var result = new ArrayList<>(List.copyOf(Objects.requireNonNull(predicates, "predicates")));
            if (result.isEmpty() || result.stream().anyMatch(Objects::isNull)) {
                throw invalid("QUALITY_REPAIR_ROUTE_PREDICATES_INVALID");
            }
            var ids = new HashSet<String>();
            for (var predicate : result) {
                if (!predicate.predicateId().startsWith(route.name() + "_")
                        || !ids.add(predicate.predicateId())) {
                    throw invalid("QUALITY_REPAIR_ROUTE_PREDICATE_SET_INVALID");
                }
            }
            result.sort(Comparator.comparing(PredicateEvidence::predicateId));
            predicates = List.copyOf(result);
        }
    }

    public record SuccessorIdentity(SuccessorIdentityKind kind, String value) {
        public SuccessorIdentity {
            Objects.requireNonNull(kind, "kind");
            if (value == null || value.isBlank() || value.length() > 256
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw invalid("QUALITY_REPAIR_SUCCESSOR_IDENTITY_INVALID");
            }
        }
    }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public ExternalProviderUsage {
            if (attempts < 0 || reservations < 0 || costMicrosCny < 0) {
                throw invalid("QUALITY_REPAIR_EXTERNAL_PROVIDER_USAGE_INVALID");
            }
        }

        public boolean zeroUsage() {
            return attempts == 0 && reservations == 0 && costMicrosCny == 0;
        }
    }
}
