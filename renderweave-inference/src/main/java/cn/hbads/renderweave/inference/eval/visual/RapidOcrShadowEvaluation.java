package cn.hbads.renderweave.inference.eval.visual;

import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.ArtifactSet;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import cn.hbads.renderweave.inference.vision.VisualEvidenceAcquisition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes exactly two isolated, complete and zero-Provider actual acquisition runs. */
public final class RapidOcrShadowEvaluation {
    public static final String VERSION = "renderweave-rapidocr-shadow-evaluation-runner/1.0";

    public Result evaluate(RunSessionFactory factory) {
        Objects.requireNonNull(factory, "factory");
        var corpus = new LayeredVisualCorpus();
        var expectedPolicy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
        var firstObservations = new LinkedHashMap<String, DocumentObservationIR>();
        var first = run(corpus, expectedPolicy, factory, 1, firstObservations, null);
        var observationEquivalent = new int[]{0};
        var second = run(corpus, expectedPolicy, factory, 2, new LinkedHashMap<>(),
                new ObservationComparison(firstObservations, observationEquivalent));
        var identity = RapidOcrShadowEvaluationIdentity.exact(corpus, expectedPolicy);
        var report = new RapidOcrShadowReporter().report(
                corpus, identity, first, second, observationEquivalent[0]);
        if (!report.determinism().deterministic()) {
            throw new IllegalStateException("RAPIDOCR_SHADOW_SECOND_RUN_DRIFT");
        }
        var codec = new RapidOcrShadowReportJsonCodec();
        var encoded = codec.write(report);
        var reportIdentity = codec.reportIdentity(report);
        codec.read(encoded, reportIdentity);
        return new Result(report, reportIdentity, encoded);
    }

    private static List<RapidOcrShadowCaseRecord> run(
            LayeredVisualCorpus corpus,
            AcquisitionPolicy expectedPolicy,
            RunSessionFactory factory,
            int runOrdinal,
            Map<String, DocumentObservationIR> observations,
            ObservationComparison comparison
    ) {
        try (var session = Objects.requireNonNull(factory.open(runOrdinal), "runSession")) {
            if (!expectedPolicy.equals(session.policy())) {
                throw new IllegalArgumentException("RAPIDOCR_SHADOW_ACQUISITION_POLICY_DRIFT");
            }
            var rasterizer = new VisualStageRasterizer();
            var evaluator = new RapidOcrShadowCaseEvaluator();
            var records = new java.util.ArrayList<RapidOcrShadowCaseRecord>();
            for (var evaluationCase : corpus.cases()) {
                var rendered = rasterizer.render(evaluationCase.renderCase());
                if (!evaluationCase.renderIdentity().equals("render-sha256:" + rendered.sha256())) {
                    throw new IllegalStateException("RAPIDOCR_SHADOW_RENDER_IDENTITY_DRIFT");
                }
                var artifactSet = ArtifactSet.canonical(List.of(new ArtifactSet.Artifact(
                        rendered.sha256(), 0, rendered.mediaType(), rendered.bytes(),
                        rendered.width(), rendered.height(), true)));
                var started = System.nanoTime();
                var observation = session.acquisition().acquire(artifactSet, expectedPolicy);
                var micros = Math.max(0, Math.floorDiv(System.nanoTime() - started, 1_000));
                requireObservationIdentity(observation, expectedPolicy, rendered.sha256());
                records.add(evaluator.evaluate(evaluationCase, observation, micros));
                observations.put(evaluationCase.caseId(), observation);
                if (comparison != null && equivalent(
                        comparison.first().get(evaluationCase.caseId()), observation)) {
                    comparison.equivalentCount()[0]++;
                }
            }
            return List.copyOf(records);
        }
    }

    private static void requireObservationIdentity(
            DocumentObservationIR observation,
            AcquisitionPolicy policy,
            String artifactId
    ) {
        Objects.requireNonNull(observation, "observation");
        if (!DocumentObservationIR.VERSION.equals(observation.contractVersion())
                || !policy.identity().equals(observation.acquisitionPolicyIdentity())
                || !policy.capabilityIdentity().equals(observation.capabilityIdentity())
                || observation.artifacts().size() != 1
                || !artifactId.equals(observation.artifacts().getFirst().artifactId())) {
            throw new IllegalArgumentException("RAPIDOCR_SHADOW_OBSERVATION_IDENTITY_DRIFT");
        }
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

    @FunctionalInterface
    public interface RunSessionFactory {
        RunSession open(int runOrdinal);
    }

    public static final class RunSession implements AutoCloseable {
        private final AcquisitionPolicy policy;
        private final VisualEvidenceAcquisition acquisition;
        private final Runnable closeAction;

        private RunSession(
                AcquisitionPolicy policy,
                VisualEvidenceAcquisition acquisition,
                Runnable closeAction
        ) {
            this.policy = Objects.requireNonNull(policy, "policy");
            this.acquisition = Objects.requireNonNull(acquisition, "acquisition");
            this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        }

        public static RunSession of(AcquisitionPolicy policy, VisualEvidenceAcquisition acquisition) {
            return new RunSession(policy, acquisition, () -> { });
        }

        public static RunSession of(
                AcquisitionPolicy policy,
                VisualEvidenceAcquisition acquisition,
                Runnable closeAction
        ) {
            return new RunSession(policy, acquisition, closeAction);
        }

        public AcquisitionPolicy policy() { return policy; }

        public VisualEvidenceAcquisition acquisition() { return acquisition; }

        @Override
        public void close() { closeAction.run(); }
    }

    public record Result(RapidOcrShadowReport report, String reportIdentity, byte[] encodedReport) {
        public Result {
            Objects.requireNonNull(report, "report");
            reportIdentity = LayeredVisualAnnotation.requireIdentity(
                    reportIdentity, "RAPIDOCR_SHADOW_REPORT_IDENTITY_INVALID");
            encodedReport = Objects.requireNonNull(encodedReport, "encodedReport").clone();
            if (encodedReport.length == 0) throw new IllegalArgumentException("RAPIDOCR_SHADOW_REPORT_EMPTY");
        }

        @Override
        public byte[] encodedReport() { return encodedReport.clone(); }

        @Override
        public String toString() {
            return "Result[version=" + VERSION + ", reportIdentity=" + reportIdentity
                    + ", caseCount=" + report.expectedCaseCount() + ", payload=<redacted>]";
        }
    }

    private record ObservationComparison(
            Map<String, DocumentObservationIR> first,
            int[] equivalentCount
    ) { }
}
