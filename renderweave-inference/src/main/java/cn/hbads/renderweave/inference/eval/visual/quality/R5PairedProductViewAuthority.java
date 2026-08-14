package cn.hbads.renderweave.inference.eval.visual.quality;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable, payload-safe lock for the R5 paired-product-view successor. */
public final class R5PairedProductViewAuthority {
    public static final String VERSION = "renderweave-r5p-authority/1.0";
    private static final String RESOURCE = "visual-eval/r5p/paired-product-view-authority-v1.json";
    private static final String RESOURCE_SHA256 =
            "05958659a5ffc302e92f6cc6cda8b1efd868e2ec4fa7f92b0d63f821f843441d";
    private static final String SPEC_PATH =
            "specs/changes/20260815-r5-paired-product-view-successor.md";
    private static final String SPEC_SHA256 =
            "650ad1632347592d1fc34325983744c02563b43d8a565b9b1cd24e1a805a892a";
    private static final String BASELINE_REVISION =
            "57be4d9b249c0aa06a1c0b32abc634c152a97234";
    private static final String N7_04_EVIDENCE_AUTHORITY_SHA256 =
            FrozenQualityEvidencePack.N7_04_EVIDENCE_AUTHORITY_SHA256;
    private static final String N7_04_AUDIT_SHA256 = FrozenQualityEvidencePack.N7_04_AUDIT_SHA256;
    private static final String OLD_R5_AUTHORITY_VERSION = R5ProductTransformAuthority.VERSION;
    private static final String OLD_R5_AUTHORITY_SHA256 =
            "a6ef7ee0820ea906cb371371d66a8eaef3ba77ac569ae24d6e4935e144ef4475";
    private static final String OLD_R5_RUNNER_VERSION =
            "renderweave-r5-product-transform-runner/1.0";
    private static final String OLD_R5_RUNNER_SOURCE_SHA256 =
            "3f7e03764f5a71d6c796ffc82e1c468d7458ca5338bc3e012166c389a3776178";
    private static final String OLD_R5_RUNNER_DISPOSITION = "R5_PRODUCT_TRANSFORM_ROUTE_CLOSED";
    private static final List<String> PROHIBITED_IDENTITIES = List.of(
            "N7-04",
            "N7-05",
            FrozenQualityEvidencePack.N7_04_AUTHORIZATION_ID,
            FrozenQualityEvidencePack.N7_04_CONTRACT_IDENTITY,
            FrozenQualityEvidencePack.N7_04_EVALUATION_IDENTITY,
            "renderweave-r5-product-transform-assignment/1.0:"
                    + "46c8e4c9c28b8628bac6532deeeb1a9ee311dda58b1a76f23a1b1d70abe7b540",
            "renderweave-r5-product-transform-evaluation/1.0:"
                    + "e25bb4531545a399ee2e83082cc7b3dbceda0cbaa8e2e7965a570e3484925d46",
            "renderweave-r5-product-transform-evidence/1.0:"
                    + "3041df28a17167c9b9eb322d0f60cf2508b2cff0ac8da344449c544908211528",
            OLD_R5_AUTHORITY_VERSION + ":" + OLD_R5_AUTHORITY_SHA256,
            OLD_R5_RUNNER_VERSION);
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    private R5PairedProductViewAuthority() { }

    public static Lock load() {
        try (var input = R5PairedProductViewAuthority.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalid("R5P_AUTHORITY_RESOURCE_MISSING");
            var bytes = input.readAllBytes();
            var digest = sha256(bytes);
            if (!RESOURCE_SHA256.equals(digest)) throw invalid("R5P_AUTHORITY_RESOURCE_DRIFT");
            var value = JSON.readValue(bytes, Document.class).toLock(VERSION + ":" + digest);
            value.validate();
            return value;
        } catch (IOException failure) {
            throw new IllegalStateException("R5P_AUTHORITY_RESOURCE_INVALID", failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public enum N7Decision { FAIL, PASS }

    public enum AuthorizationStatus { CLOSED, OPEN }

    public enum N7DependencyStatus { PERMANENTLY_BLOCKED, READY }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public ExternalProviderUsage {
            if (attempts < 0 || reservations < 0 || costMicrosCny < 0) {
                throw invalid("R5P_PROVIDER_USAGE_INVALID");
            }
        }

        public boolean zeroUsage() {
            return attempts == 0 && reservations == 0 && costMicrosCny == 0;
        }
    }

    public record HistoricalState(
            String n704TicketId,
            String n704EvidenceAuthoritySha256,
            String n704AuditSha256,
            N7Decision n704Decision,
            String n704AuthorizationId,
            AuthorizationStatus n704AuthorizationStatus,
            String n705TicketId,
            N7DependencyStatus n705DependencyStatus,
            String oldR5AuthorityVersion,
            String oldR5AuthoritySha256,
            String oldR5RunnerVersion,
            String oldR5RunnerSourceSha256,
            String oldR5RunnerDisposition
    ) {
        public HistoricalState {
            Objects.requireNonNull(n704TicketId, "n704TicketId");
            Objects.requireNonNull(n704EvidenceAuthoritySha256, "n704EvidenceAuthoritySha256");
            Objects.requireNonNull(n704AuditSha256, "n704AuditSha256");
            Objects.requireNonNull(n704Decision, "n704Decision");
            Objects.requireNonNull(n704AuthorizationId, "n704AuthorizationId");
            Objects.requireNonNull(n704AuthorizationStatus, "n704AuthorizationStatus");
            Objects.requireNonNull(n705TicketId, "n705TicketId");
            Objects.requireNonNull(n705DependencyStatus, "n705DependencyStatus");
            Objects.requireNonNull(oldR5AuthorityVersion, "oldR5AuthorityVersion");
            Objects.requireNonNull(oldR5AuthoritySha256, "oldR5AuthoritySha256");
            Objects.requireNonNull(oldR5RunnerVersion, "oldR5RunnerVersion");
            Objects.requireNonNull(oldR5RunnerSourceSha256, "oldR5RunnerSourceSha256");
            Objects.requireNonNull(oldR5RunnerDisposition, "oldR5RunnerDisposition");
        }

        public HistoricalState withN704Decision(N7Decision value) {
            return new HistoricalState(n704TicketId, n704EvidenceAuthoritySha256, n704AuditSha256,
                    value, n704AuthorizationId, n704AuthorizationStatus, n705TicketId,
                    n705DependencyStatus, oldR5AuthorityVersion, oldR5AuthoritySha256,
                    oldR5RunnerVersion, oldR5RunnerSourceSha256, oldR5RunnerDisposition);
        }

        public HistoricalState withN704AuthorizationStatus(AuthorizationStatus value) {
            return new HistoricalState(n704TicketId, n704EvidenceAuthoritySha256, n704AuditSha256,
                    n704Decision, n704AuthorizationId, value, n705TicketId, n705DependencyStatus,
                    oldR5AuthorityVersion, oldR5AuthoritySha256, oldR5RunnerVersion,
                    oldR5RunnerSourceSha256, oldR5RunnerDisposition);
        }

        public HistoricalState withOldR5AuthoritySha256(String value) {
            return new HistoricalState(n704TicketId, n704EvidenceAuthoritySha256, n704AuditSha256,
                    n704Decision, n704AuthorizationId, n704AuthorizationStatus, n705TicketId,
                    n705DependencyStatus, oldR5AuthorityVersion, value, oldR5RunnerVersion,
                    oldR5RunnerSourceSha256, oldR5RunnerDisposition);
        }

        public HistoricalState withOldR5RunnerDisposition(String value) {
            return new HistoricalState(n704TicketId, n704EvidenceAuthoritySha256, n704AuditSha256,
                    n704Decision, n704AuthorizationId, n704AuthorizationStatus, n705TicketId,
                    n705DependencyStatus, oldR5AuthorityVersion, oldR5AuthoritySha256,
                    oldR5RunnerVersion, oldR5RunnerSourceSha256, value);
        }

        public HistoricalState withOldR5RunnerSourceSha256(String value) {
            return new HistoricalState(n704TicketId, n704EvidenceAuthoritySha256, n704AuditSha256,
                    n704Decision, n704AuthorizationId, n704AuthorizationStatus, n705TicketId,
                    n705DependencyStatus, oldR5AuthorityVersion, oldR5AuthoritySha256,
                    oldR5RunnerVersion, value, oldR5RunnerDisposition);
        }
    }

    public record Lock(
            String authorityVersion,
            String approvedSpecPath,
            String approvedSpecSha256,
            String baselineRevision,
            HistoricalState historicalState,
            List<String> prohibitedIdentityValues,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads,
            String terminalCode,
            String authorityIdentity
    ) {
        public Lock {
            Objects.requireNonNull(historicalState, "historicalState");
            prohibitedIdentityValues = List.copyOf(
                    Objects.requireNonNull(prohibitedIdentityValues, "prohibitedIdentityValues"));
            Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
        }

        private void validate() {
            if (!VERSION.equals(authorityVersion)
                    || !SPEC_PATH.equals(approvedSpecPath)
                    || !SPEC_SHA256.equals(approvedSpecSha256)
                    || !BASELINE_REVISION.equals(baselineRevision)
                    || !PROHIBITED_IDENTITIES.equals(prohibitedIdentityValues)
                    || !externalProviderUsage.zeroUsage()
                    || apiKeyReads != 0
                    || !"R5P_AUTHORITY_LOCKED".equals(terminalCode)
                    || !(VERSION + ":" + RESOURCE_SHA256).equals(authorityIdentity)) {
                throw invalid("R5P_AUTHORITY_DRIFT");
            }
            requireHistoricalState(historicalState);
        }

        public String approvedSpecIdentity() {
            return "spec-sha256:" + approvedSpecSha256;
        }

        public String oldR5AuthorityIdentity() {
            return historicalState.oldR5AuthorityVersion() + ":"
                    + historicalState.oldR5AuthoritySha256();
        }

        public void requireHistoricalState(HistoricalState current) {
            Objects.requireNonNull(current, "current");
            if (!"N7-04".equals(current.n704TicketId())
                    || !N7_04_EVIDENCE_AUTHORITY_SHA256.equals(
                    current.n704EvidenceAuthoritySha256())
                    || !N7_04_AUDIT_SHA256.equals(current.n704AuditSha256())
                    || current.n704Decision() != N7Decision.FAIL
                    || !FrozenQualityEvidencePack.N7_04_AUTHORIZATION_ID.equals(
                    current.n704AuthorizationId())
                    || current.n704AuthorizationStatus() != AuthorizationStatus.CLOSED
                    || !"N7-05".equals(current.n705TicketId())
                    || current.n705DependencyStatus() != N7DependencyStatus.PERMANENTLY_BLOCKED) {
                throw invalid("R5P_N7_AUTHORITY_STATE_DRIFT");
            }
            if (!OLD_R5_AUTHORITY_VERSION.equals(current.oldR5AuthorityVersion())
                    || !OLD_R5_AUTHORITY_SHA256.equals(current.oldR5AuthoritySha256())) {
                throw invalid("R5P_OLD_R5_AUTHORITY_DRIFT");
            }
            if (!OLD_R5_RUNNER_VERSION.equals(current.oldR5RunnerVersion())
                    || !OLD_R5_RUNNER_SOURCE_SHA256.equals(current.oldR5RunnerSourceSha256())
                    || !OLD_R5_RUNNER_DISPOSITION.equals(current.oldR5RunnerDisposition())) {
                throw invalid("R5P_OLD_R5_RUNNER_REOPENED");
            }
        }

        public void requireFreshSuccessorIdentity(String value) {
            if (value == null || value.isBlank() || value.length() > 256
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw invalid("R5P_SUCCESSOR_IDENTITY_INVALID");
            }
            if (prohibitedIdentityValues.contains(value)
                    || value.startsWith("N7-04")
                    || value.startsWith("N7-05")
                    || value.startsWith("renderweave-r5-product-transform-")) {
                throw invalid("R5P_HISTORICAL_IDENTITY_REUSED");
            }
        }

        public boolean allowsLiveOrJ1() {
            return false;
        }

        @Override
        public String toString() {
            return "Lock[authorityIdentity=" + authorityIdentity + ", baselineRevision="
                    + baselineRevision + ", terminalCode=" + terminalCode
                    + ", externalProviderUsage=" + externalProviderUsage + ", apiKeyReads="
                    + apiKeyReads + "]";
        }
    }

    private record Document(
            String authorityVersion,
            String approvedSpecPath,
            String approvedSpecSha256,
            String baselineRevision,
            String n704TicketId,
            String n704EvidenceAuthoritySha256,
            String n704AuditSha256,
            N7Decision n704Decision,
            String n704AuthorizationId,
            AuthorizationStatus n704AuthorizationStatus,
            String n705TicketId,
            N7DependencyStatus n705DependencyStatus,
            String oldR5AuthorityVersion,
            String oldR5AuthoritySha256,
            String oldR5RunnerVersion,
            String oldR5RunnerSourceSha256,
            String oldR5RunnerDisposition,
            List<String> prohibitedIdentityValues,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads,
            String terminalCode
    ) {
        Lock toLock(String identity) {
            return new Lock(authorityVersion, approvedSpecPath, approvedSpecSha256,
                    baselineRevision, new HistoricalState(n704TicketId,
                    n704EvidenceAuthoritySha256, n704AuditSha256, n704Decision,
                    n704AuthorizationId, n704AuthorizationStatus, n705TicketId,
                    n705DependencyStatus, oldR5AuthorityVersion, oldR5AuthoritySha256,
                    oldR5RunnerVersion, oldR5RunnerSourceSha256, oldR5RunnerDisposition),
                    prohibitedIdentityValues,
                    externalProviderUsage, apiKeyReads, terminalCode, identity);
        }
    }
}
