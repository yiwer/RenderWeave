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

/** Immutable, payload-safe authority lock for the R5P2 successor namespace. */
public final class R5P2Authority {
    public static final String VERSION = "renderweave-r5p2-authority/1.0";
    private static final String RESOURCE = "visual-eval/r5p2/authority-v1.json";
    private static final String RESOURCE_SHA256 =
            "274585e94941248dd2bea55026c06428f2945aea7cc48ce2b269c21f5f3ccc07";
    private static final String SPEC_PATH =
            "specs/changes/20260815-r5p2-source-line-reconciliation-successor.md";
    private static final String SPEC_SHA256 =
            "e33269e1faa04f21239a0e79d4346fc90439f142b26111b3764164f53ba7d902";
    private static final String BASELINE_REVISION =
            "4b756c52cbc2fd389d8ca34f4c4a65b1bc9615db";
    private static final String HISTORICAL_SPEC_PATH =
            "specs/changes/20260815-r5-paired-product-view-successor.md";
    private static final String HISTORICAL_SPEC_SHA256 =
            "650ad1632347592d1fc34325983744c02563b43d8a565b9b1cd24e1a805a892a";
    private static final String HISTORICAL_TERMINAL = "R5P_MEASUREMENT_INVALID";
    private static final String PRODUCER_REPORT_IDENTITY =
            "renderweave-r5p-paired-product-view-report/1.0:"
                    + "2f15a068bd6c5eb8416a1d7da7c8fd679278a8f734cd78d2d35ade6ab01ff783";
    private static final String PRODUCER_REPORT_SHA256 =
            "df622da5089f069ed4b6bd2a929fec6539839af6375d3669d18f896397082625";
    private static final String INDEPENDENT_EVIDENCE_IDENTITY =
            "renderweave-r5p-independent-replay-evidence/1.0:"
                    + "2ccd12203e15ac572d72036530973ad181e76f0a08ebd4b84b2d4b14aaca5281";
    private static final String INDEPENDENT_EVIDENCE_SHA256 =
            "1086bbee024a126d7c665995a44461faee36e4a7ee541e73f8bccd2f2fc393d6";
    private static final String HISTORICAL_EVALUATION_IDENTITY =
            "renderweave-r5p-paired-view-evaluation/1.0:"
                    + "c8ad69263640ca49cd93ca24c6b558c6f913ff89a40c84052634c7cd79f66b65";
    private static final String CORPUS_IDENTITY =
            "renderweave-visual-stage-corpus/2.0:"
                    + "c596621eb680e7e10d42d2e1d1f926995cec9716cc6ef83a96a50ad53adc285c";
    private static final String CORPUS_LOCK_SHA256 =
            "cf54fd985e89a024fdc0742a737c21442c49718fdf58b0bb05b87e2cffd2247d";
    private static final List<String> CLOSED_TICKETS = List.of(
            "R5P-07", "R5P-08", "R5P-09", "R5P-10", "R5P-11", "R5P-12");
    private static final List<String> PROHIBITED_IDENTITIES = List.of(
            "renderweave-r5p-authority/1.0:"
                    + "05958659a5ffc302e92f6cc6cda8b1efd868e2ec4fa7f92b0d63f821f843441d",
            "renderweave-r5p-paired-view-assignment/1.0:"
                    + "39266e24b85e0189577573e6e4e56905d41a43f7e0f81a9514fbdbcac954c3e8",
            HISTORICAL_EVALUATION_IDENTITY,
            PRODUCER_REPORT_IDENTITY,
            INDEPENDENT_EVIDENCE_IDENTITY);
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    private R5P2Authority() { }

    public static Lock load() {
        return load(R5P2Authority.class.getClassLoader());
    }

    static Lock load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        try (var input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalid("R5P2_AUTHORITY_RESOURCE_MISSING");
            var bytes = input.readAllBytes();
            var digest = sha256(bytes);
            if (!RESOURCE_SHA256.equals(digest)) throw invalid("R5P2_AUTHORITY_RESOURCE_DRIFT");
            var lock = JSON.readValue(bytes, Document.class).toLock(VERSION + ":" + digest);
            lock.validate();
            return lock;
        } catch (IOException failure) {
            throw new IllegalStateException("R5P2_AUTHORITY_RESOURCE_INVALID", failure);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public ExternalProviderUsage {
            if (attempts < 0 || reservations < 0 || costMicrosCny < 0) {
                throw invalid("R5P2_PROVIDER_USAGE_INVALID");
            }
        }

        public boolean zeroUsage() {
            return attempts == 0 && reservations == 0 && costMicrosCny == 0;
        }
    }

    public record History(
            String historicalSpecPath,
            String historicalSpecSha256,
            String effectiveTerminal,
            String producerReportIdentity,
            String producerReportSha256,
            String independentEvidenceIdentity,
            String independentEvidenceSha256,
            String evaluationIdentity,
            List<String> closedTicketIds
    ) {
        public History {
            Objects.requireNonNull(historicalSpecPath, "historicalSpecPath");
            Objects.requireNonNull(historicalSpecSha256, "historicalSpecSha256");
            Objects.requireNonNull(effectiveTerminal, "effectiveTerminal");
            Objects.requireNonNull(producerReportIdentity, "producerReportIdentity");
            Objects.requireNonNull(producerReportSha256, "producerReportSha256");
            Objects.requireNonNull(independentEvidenceIdentity, "independentEvidenceIdentity");
            Objects.requireNonNull(independentEvidenceSha256, "independentEvidenceSha256");
            Objects.requireNonNull(evaluationIdentity, "evaluationIdentity");
            closedTicketIds = List.copyOf(Objects.requireNonNull(closedTicketIds, "closedTicketIds"));
        }

        public History withEffectiveTerminal(String value) {
            return new History(historicalSpecPath, historicalSpecSha256, value,
                    producerReportIdentity, producerReportSha256, independentEvidenceIdentity,
                    independentEvidenceSha256, evaluationIdentity, closedTicketIds);
        }

        public History withProducerReportSha256(String value) {
            return new History(historicalSpecPath, historicalSpecSha256, effectiveTerminal,
                    producerReportIdentity, value, independentEvidenceIdentity,
                    independentEvidenceSha256, evaluationIdentity, closedTicketIds);
        }

        public History withIndependentEvidenceSha256(String value) {
            return new History(historicalSpecPath, historicalSpecSha256, effectiveTerminal,
                    producerReportIdentity, producerReportSha256, independentEvidenceIdentity,
                    value, evaluationIdentity, closedTicketIds);
        }
    }

    public record Lock(
            String authorityVersion,
            String approvedSpecPath,
            String approvedSpecSha256,
            String baselineRevision,
            History history,
            String corpusIdentity,
            String corpusIdentityLockSha256,
            List<String> prohibitedIdentityValues,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads,
            String terminalCode,
            String authorityIdentity
    ) {
        public Lock {
            Objects.requireNonNull(history, "history");
            prohibitedIdentityValues = List.copyOf(
                    Objects.requireNonNull(prohibitedIdentityValues, "prohibitedIdentityValues"));
            Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
        }

        private void validate() {
            if (!VERSION.equals(authorityVersion)
                    || !SPEC_PATH.equals(approvedSpecPath)
                    || !SPEC_SHA256.equals(approvedSpecSha256)
                    || !BASELINE_REVISION.equals(baselineRevision)
                    || !CORPUS_IDENTITY.equals(corpusIdentity)
                    || !CORPUS_LOCK_SHA256.equals(corpusIdentityLockSha256)
                    || !PROHIBITED_IDENTITIES.equals(prohibitedIdentityValues)
                    || !externalProviderUsage.zeroUsage()
                    || apiKeyReads != 0
                    || !"R5P2_AUTHORITY_LOCKED".equals(terminalCode)
                    || !(VERSION + ":" + RESOURCE_SHA256).equals(authorityIdentity)) {
                throw invalid("R5P2_AUTHORITY_DRIFT");
            }
            requireHistory(history);
        }

        public String approvedSpecIdentity() {
            return "spec-sha256:" + approvedSpecSha256;
        }

        public void requireHistory(History current) {
            Objects.requireNonNull(current, "current");
            if (!HISTORICAL_TERMINAL.equals(current.effectiveTerminal())) {
                throw invalid("R5P2_HISTORICAL_TERMINAL_DRIFT");
            }
            if (!HISTORICAL_SPEC_PATH.equals(current.historicalSpecPath())
                    || !HISTORICAL_SPEC_SHA256.equals(current.historicalSpecSha256())
                    || !PRODUCER_REPORT_IDENTITY.equals(current.producerReportIdentity())
                    || !PRODUCER_REPORT_SHA256.equals(current.producerReportSha256())
                    || !INDEPENDENT_EVIDENCE_IDENTITY.equals(current.independentEvidenceIdentity())
                    || !INDEPENDENT_EVIDENCE_SHA256.equals(current.independentEvidenceSha256())
                    || !HISTORICAL_EVALUATION_IDENTITY.equals(current.evaluationIdentity())
                    || !CLOSED_TICKETS.equals(current.closedTicketIds())) {
                throw invalid("R5P2_HISTORICAL_EVIDENCE_DRIFT");
            }
        }

        public void requireFreshR5P2Identity(String value) {
            if (value == null || value.isBlank() || value.length() > 256
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw invalid("R5P2_SUCCESSOR_IDENTITY_INVALID");
            }
            if (prohibitedIdentityValues.contains(value)
                    || value.startsWith("R5P-")
                    || value.startsWith("renderweave-r5p-")) {
                throw invalid("R5P2_HISTORICAL_IDENTITY_REUSED");
            }
            if (!(value.startsWith("R5P2-") || value.startsWith("renderweave-r5p2-"))) {
                throw invalid("R5P2_SUCCESSOR_NAMESPACE_REQUIRED");
            }
        }

        public boolean allowsLiveOrJ1() {
            return false;
        }

        @Override
        public String toString() {
            return "Lock[authorityIdentity=" + authorityIdentity + ", baselineRevision="
                    + baselineRevision + ", historicalTerminal=" + history.effectiveTerminal()
                    + ", terminalCode=" + terminalCode + ", externalProviderUsage="
                    + externalProviderUsage + ", apiKeyReads=" + apiKeyReads + "]";
        }
    }

    private record Document(
            String authorityVersion,
            String approvedSpecPath,
            String approvedSpecSha256,
            String baselineRevision,
            String historicalSpecPath,
            String historicalSpecSha256,
            String historicalEffectiveTerminal,
            String historicalProducerReportIdentity,
            String historicalProducerReportSha256,
            String historicalIndependentEvidenceIdentity,
            String historicalIndependentEvidenceSha256,
            String historicalEvaluationIdentity,
            List<String> closedTicketIds,
            String corpusIdentity,
            String corpusIdentityLockSha256,
            List<String> prohibitedIdentityValues,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads,
            String terminalCode
    ) {
        Lock toLock(String identity) {
            return new Lock(authorityVersion, approvedSpecPath, approvedSpecSha256,
                    baselineRevision, new History(historicalSpecPath, historicalSpecSha256,
                    historicalEffectiveTerminal, historicalProducerReportIdentity,
                    historicalProducerReportSha256, historicalIndependentEvidenceIdentity,
                    historicalIndependentEvidenceSha256, historicalEvaluationIdentity,
                    closedTicketIds), corpusIdentity, corpusIdentityLockSha256,
                    prohibitedIdentityValues, externalProviderUsage, apiKeyReads, terminalCode,
                    identity);
        }
    }
}
