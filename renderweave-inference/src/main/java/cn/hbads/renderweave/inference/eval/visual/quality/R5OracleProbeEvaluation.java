package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowCaseEvaluator;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowCaseRecord;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluation;
import cn.hbads.renderweave.inference.eval.visual.VisualStageRasterizer;
import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.ArtifactSet;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes the fixed 3+1 baseline-versus-oracle probe twice without any Provider path. */
public final class R5OracleProbeEvaluation {
    public static final String VERSION = "renderweave-r5-oracle-probe-runner/1.0";

    public Result evaluate(RapidOcrShadowEvaluation.RunSessionFactory factory) {
        Objects.requireNonNull(factory, "factory");
        var corpus = new LayeredVisualCorpus();
        var protocol = OfflineQualityEvaluationProtocol.load();
        var assignment = protocol.r5ProbeAssignment();
        var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
        var transform = new R5OracleHigherResolutionTransform();
        var first = run(factory, 1, corpus, assignment.caseIds(), policy, transform);
        var second = run(factory, 2, corpus, assignment.caseIds(), policy, transform);
        var differentials = new java.util.ArrayList<R5OracleProbeEvidence.CaseDifferential>();
        var deterministicCases = 0;
        for (var caseId : assignment.caseIds()) {
            var left = first.get(caseId);
            var right = second.get(caseId);
            var deterministic = left.baselineRecord().metricsEquivalent(right.baselineRecord())
                    && left.oracleRecord().metricsEquivalent(right.oracleRecord())
                    && equivalent(left.baselineObservation(), right.baselineObservation())
                    && equivalent(left.oracleObservation(), right.oracleObservation());
            if (deterministic) deterministicCases++;
            var evaluationCase = corpus.require(caseId);
            differentials.add(new R5OracleProbeEvidence.CaseDifferential(
                    caseId,
                    evaluationCase.caseIdentity(),
                    evaluationCase.partition(),
                    evaluationCase.renderCase().width(),
                    evaluationCase.renderCase().height(),
                    left.oracleWidth(),
                    left.oracleHeight(),
                    metrics(left.baselineRecord()),
                    metrics(left.oracleRecord()),
                    deterministic));
        }
        var evaluationIdentity = evaluationIdentity(corpus, protocol, policy, transform);
        var evidence = R5OracleProbeEvidence.decide(
                evaluationIdentity, differentials, deterministicCases);
        var codec = new R5OracleProbeEvidenceJsonCodec();
        var encoded = codec.write(evidence);
        var evidenceIdentity = codec.evidenceIdentity(evidence);
        codec.read(encoded, evidenceIdentity);
        return new Result(evidence, evidenceIdentity, encoded);
    }

    private static Map<String, Measurement> run(
            RapidOcrShadowEvaluation.RunSessionFactory factory,
            int runOrdinal,
            LayeredVisualCorpus corpus,
            List<String> caseIds,
            AcquisitionPolicy expectedPolicy,
            R5OracleHigherResolutionTransform transform
    ) {
        try (var session = Objects.requireNonNull(factory.open(runOrdinal), "runSession")) {
            if (!expectedPolicy.equals(session.policy())) {
                throw new IllegalArgumentException("R5_PROBE_ACQUISITION_POLICY_DRIFT");
            }
            var rasterizer = new VisualStageRasterizer();
            var evaluator = new RapidOcrShadowCaseEvaluator();
            var result = new LinkedHashMap<String, Measurement>();
            for (var caseId : caseIds) {
                var evaluationCase = corpus.require(caseId);
                var baseline = rasterizer.render(evaluationCase.renderCase());
                if (!evaluationCase.renderIdentity().equals("render-sha256:" + baseline.sha256())) {
                    throw new IllegalStateException("R5_PROBE_BASELINE_RENDER_DRIFT");
                }
                var oracle = transform.render(evaluationCase);
                var baselineTimed = acquire(session, expectedPolicy, artifact(
                        baseline.sha256(), baseline.mediaType(), baseline.bytes(), baseline.width(), baseline.height()));
                var oracleTimed = acquire(session, expectedPolicy, artifact(
                        oracle.artifactId(), oracle.mediaType(), oracle.bytes(), oracle.width(), oracle.height()));
                var measurement = new Measurement(
                        evaluator.evaluate(evaluationCase, baselineTimed.observation(), baselineTimed.micros()),
                        evaluator.evaluateAgainstSameGold(
                                evaluationCase, oracleTimed.observation(), oracleTimed.micros(), oracle.artifactId()),
                        baselineTimed.observation(),
                        oracleTimed.observation(),
                        oracle.width(),
                        oracle.height());
                if (result.putIfAbsent(caseId, measurement) != null) {
                    throw new IllegalStateException("R5_PROBE_DUPLICATE_CASE");
                }
            }
            return Map.copyOf(result);
        }
    }

    private static TimedObservation acquire(
            RapidOcrShadowEvaluation.RunSession session,
            AcquisitionPolicy policy,
            ArtifactSet artifacts
    ) {
        var started = System.nanoTime();
        var observation = session.acquisition().acquire(artifacts, policy);
        var micros = Math.max(0, Math.floorDiv(System.nanoTime() - started, 1_000));
        var expected = artifacts.artifacts().getFirst();
        if (!DocumentObservationIR.VERSION.equals(observation.contractVersion())
                || !policy.identity().equals(observation.acquisitionPolicyIdentity())
                || !policy.capabilityIdentity().equals(observation.capabilityIdentity())
                || observation.artifacts().size() != 1
                || !expected.artifactId().equals(observation.artifacts().getFirst().artifactId())) {
            throw new IllegalArgumentException("R5_PROBE_OBSERVATION_IDENTITY_DRIFT");
        }
        return new TimedObservation(observation, micros);
    }

    private static ArtifactSet artifact(
            String artifactId,
            String mediaType,
            byte[] bytes,
            int width,
            int height
    ) {
        return ArtifactSet.canonical(List.of(new ArtifactSet.Artifact(
                artifactId, 0, mediaType, bytes, width, height, true)));
    }

    private static R5OracleProbeEvidence.CaseMetrics metrics(RapidOcrShadowCaseRecord record) {
        var ocr = record.ocr();
        return new R5OracleProbeEvidence.CaseMetrics(
                record.observationCount(),
                record.layout().lines().expected(),
                record.layout().lines().matched(),
                Math.addExact(Math.addExact(ocr.characterSubstitutions(), ocr.characterInsertions()),
                        ocr.characterDeletions()),
                ocr.hallucinationCases(),
                record.order().expectedEdges(),
                record.order().comparableEdges(),
                record.order().correctEdges(),
                record.repeat().expectedMemberships(),
                record.repeat().observableMemberships());
    }

    private static boolean equivalent(DocumentObservationIR left, DocumentObservationIR right) {
        return left != null && right != null
                && left.contractVersion().equals(right.contractVersion())
                && left.acquisitionPolicyIdentity().equals(right.acquisitionPolicyIdentity())
                && left.capabilityIdentity().equals(right.capabilityIdentity())
                && left.provenance().equals(right.provenance())
                && left.artifacts().equals(right.artifacts())
                && left.observationCount() == right.observationCount()
                && left.totalTextBytes() == right.totalTextBytes();
    }

    private static String evaluationIdentity(
            LayeredVisualCorpus corpus,
            OfflineQualityEvaluationProtocol protocol,
            AcquisitionPolicy policy,
            R5OracleHigherResolutionTransform transform
    ) {
        return "renderweave-r5-oracle-evaluation/1.0:" + sha256(List.of(
                VERSION,
                protocol.identity(),
                protocol.r5ProbeAssignment().identity(),
                corpus.corpusIdentity(),
                corpus.annotationSetIdentity(),
                "AcquisitionPolicy/1.0:" + policy.identity(),
                policy.capabilityIdentity(),
                transform.identity(),
                "two-isolated-baseline-and-oracle-runs/1.0",
                "provider-attempts-reservations-cost-zero/1.0"));
    }

    private static String sha256(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                var bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    public record Result(
            R5OracleProbeEvidence evidence,
            String evidenceIdentity,
            byte[] encodedEvidence
    ) {
        public Result {
            Objects.requireNonNull(evidence, "evidence");
            if (evidenceIdentity == null || !evidenceIdentity.matches(
                    "renderweave-r5-oracle-probe-evidence/1\\.0:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("R5_PROBE_EVIDENCE_IDENTITY_INVALID");
            }
            encodedEvidence = Objects.requireNonNull(encodedEvidence, "encodedEvidence").clone();
            if (encodedEvidence.length == 0) throw new IllegalArgumentException("R5_PROBE_EVIDENCE_EMPTY");
        }

        @Override
        public byte[] encodedEvidence() {
            return encodedEvidence.clone();
        }

        @Override
        public String toString() {
            return "Result[evidenceIdentity=" + evidenceIdentity + ", disposition="
                    + evidence.disposition() + ", payload=<redacted>]";
        }
    }

    private record TimedObservation(DocumentObservationIR observation, long micros) { }

    private record Measurement(
            RapidOcrShadowCaseRecord baselineRecord,
            RapidOcrShadowCaseRecord oracleRecord,
            DocumentObservationIR baselineObservation,
            DocumentObservationIR oracleObservation,
            int oracleWidth,
            int oracleHeight
    ) { }
}
