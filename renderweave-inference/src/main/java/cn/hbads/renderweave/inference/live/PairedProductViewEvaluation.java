package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowCaseEvaluator;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowCaseRecord;
import cn.hbads.renderweave.inference.eval.visual.quality.R5PairedProductViewAssignment;
import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.ArtifactSet;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.DocumentVisionObservation;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import cn.hbads.renderweave.inference.vision.VisualEvidenceAcquisition;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Executes the frozen R5P baseline/successor pair without persisting OCR or image payloads.
 * Projection and coalescing exist only inside this evaluation module.
 */
public final class PairedProductViewEvaluation {
    public static final String VERSION = "renderweave-r5p-paired-product-view-evaluator/1.0";
    public static final String REPORT_VERSION =
            "renderweave-r5p-paired-product-view-report/1.0";
    private static final String ENVELOPE_VERSION =
            "renderweave-r5p-paired-product-view-envelope/1.0";
    private static final String AUTHORITY_IDENTITY =
            "renderweave-r5p-authority/1.0:"
                    + "05958659a5ffc302e92f6cc6cda8b1efd868e2ec4fa7f92b0d63f821f843441d";
    private static final String PROJECTION_IDENTITY = "renderweave-r5p-source-projection/1.0";
    private static final String COALESCING_IDENTITY =
            "renderweave-r5p-observation-coalescing/1.0";
    private static final String COALESCING_TEXT_RULE =
            "unicode-nfc-whitespace-collapse-exact/1.0";
    private static final String COALESCING_GEOMETRY_RULE =
            "intersection-over-smaller-area-at-least-5000-bps/1.0";
    private static final String RUN_PROTOCOL_IDENTITY =
            "two-isolated-complete-paired-runs/1.0";
    private static final String TERMINAL = "R5P_PAIRED_EXECUTION_COMPLETE";
    private static final int MAXIMUM_REPORT_BYTES = 4 * 1024 * 1024;
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public Result evaluate(RunSessionFactory factory) {
        Objects.requireNonNull(factory, "factory");
        var assignment = R5PairedProductViewAssignment.load();
        if (!VERSION.equals(assignment.identities().evaluatorIdentity())) {
            throw invalid("R5P_PAIRED_EVALUATOR_IDENTITY_DRIFT");
        }
        var corpus = new LayeredVisualCorpus();
        var expectedPolicy = RapidOcrBaselineContract.policy(
                RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
        var runs = new ArrayList<RunReport>();
        for (var runOrdinal = 1; runOrdinal <= 2; runOrdinal++) {
            runs.add(run(runOrdinal, assignment, corpus, expectedPolicy, factory));
        }
        var determinism = requireDeterministic(runs.get(0), runs.get(1));
        var seen = summarize(
                runs.getFirst().caseResults().stream().filter(item ->
                        item.cohort() == R5PairedProductViewAssignment.Cohort.SEEN_DIAGNOSTIC)
                        .toList(), assignment.thresholds(), false);
        var confirmation = summarize(
                runs.getFirst().caseResults().stream().filter(item ->
                        item.cohort() == R5PairedProductViewAssignment.Cohort.SEALED_CONFIRMATION)
                        .toList(), assignment.thresholds(), true);
        var report = new Report(
                REPORT_VERSION,
                AUTHORITY_IDENTITY,
                assignment.identity(),
                assignment.evaluationIdentity(),
                VERSION,
                "AcquisitionPolicy/1.0:" + expectedPolicy.identity(),
                expectedPolicy.capabilityIdentity(),
                PROJECTION_IDENTITY,
                COALESCING_IDENTITY,
                COALESCING_TEXT_RULE,
                COALESCING_GEOMETRY_RULE,
                RUN_PROTOCOL_IDENTITY,
                assignment.cases().size(),
                assignment.cases().size() * 2 * 2,
                assignment.cases().size() * 2 * 2,
                true,
                List.copyOf(runs),
                determinism,
                seen,
                confirmation,
                seen.thresholdPass() && confirmation.thresholdPass(),
                new ExternalProviderUsage(0, 0, 0),
                0,
                TERMINAL);
        var identity = reportIdentity(report);
        var encoded = encode(new Envelope(ENVELOPE_VERSION, identity, report));
        if (!readReport(encoded, identity).equals(report)) {
            throw new IllegalStateException("R5P_PAIRED_REPORT_ROUND_TRIP_DRIFT");
        }
        return new Result(report, identity, encoded);
    }

    public Report readReport(byte[] encoded, String expectedIdentity) {
        if (encoded == null || encoded.length == 0 || encoded.length > MAXIMUM_REPORT_BYTES
                || expectedIdentity == null) {
            throw invalid("R5P_PAIRED_REPORT_INVALID");
        }
        try {
            var envelope = JSON.readValue(encoded, Envelope.class);
            if (!ENVELOPE_VERSION.equals(envelope.envelopeVersion())
                    || envelope.report() == null
                    || !expectedIdentity.equals(envelope.reportIdentity())
                    || !reportIdentity(envelope.report()).equals(envelope.reportIdentity())) {
                throw invalid("R5P_PAIRED_REPORT_INVALID");
            }
            return envelope.report();
        } catch (RuntimeException failure) {
            throw invalid("R5P_PAIRED_REPORT_INVALID");
        }
    }

    private static RunReport run(
            int runOrdinal,
            R5PairedProductViewAssignment assignment,
            LayeredVisualCorpus corpus,
            AcquisitionPolicy expectedPolicy,
            RunSessionFactory factory
    ) {
        var results = new ArrayList<PairedCaseResult>();
        try (var session = Objects.requireNonNull(factory.open(runOrdinal), "runSession")) {
            if (!expectedPolicy.equals(session.policy())) {
                throw invalid("R5P_PAIRED_ACQUISITION_POLICY_DRIFT");
            }
            for (var assigned : assignment.cases()) {
                results.add(evaluateCase(
                        assigned, corpus.require(assigned.caseId()), expectedPolicy,
                        session.acquisition()));
            }
        }
        return new RunReport(runOrdinal, true, results.size(), results.size() * 2,
                results.size() * 2, List.copyOf(results));
    }

    private static PairedCaseResult evaluateCase(
            R5PairedProductViewAssignment.CaseAssignment assigned,
            LayeredVisualCorpus.Case evaluationCase,
            AcquisitionPolicy policy,
            VisualEvidenceAcquisition acquisition
    ) {
        var raw = fixture(assigned);
        var prepared = new ProductViewHarness().prepare(List.of(
                new ProductViewHarness.RawRasterFixture(
                        assigned.caseId(), assigned.caseId() + ".png", "image/png", raw)));
        var provenance = prepared.normalizationProvenance().getFirst();
        if (!assigned.rawFixtureSha256().equals(provenance.rawFixtureSha256())
                || assigned.width() != provenance.width()
                || assigned.height() != provenance.height()) {
            throw invalid("R5P_PAIRED_NORMALIZATION_DRIFT");
        }

        var staticViews = prepared.plan().descriptors().stream()
                .map(descriptor -> prepared.plan().require(descriptor.viewId())).toList();
        var baseline = acquireBranch(
                Branch.BASELINE, prepared, staticViews,
                MultiScaleVisualViewPlanner.VERSION,
                ProductViewHarness.staticPlanIdentity(prepared.plan()),
                BoundedVisualInspection.ResourceSummary.empty(),
                evaluationCase, policy, acquisition);

        var action = new BoundedVisualInspection().inspect(
                prepared.artifactSet(), prepared.plan(),
                new BoundedVisualInspection.InspectionRequest(
                        BoundedVisualInspection.REQUEST_VERSION, assigned.regions()),
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial());
        if (action.disposition() != BoundedVisualInspection.Disposition.EXECUTED
                || !"R5P_INSPECTION_EXECUTED".equals(action.reasonCode())) {
            throw invalid("R5P_PAIRED_SUCCESSOR_PLAN_INVALID");
        }
        var successor = acquireBranch(
                Branch.SUCCESSOR, prepared, action.executionViews(), action.planVersion(),
                action.planIdentity(), action.resourceSummary(), evaluationCase, policy, acquisition);
        var metrics = compare(baseline.metrics(), successor.metrics());
        return new PairedCaseResult(
                assigned.caseId(), assigned.caseIdentity(), assigned.cohort(),
                assigned.sourcePartition(),
                new NormalizationSummary(
                        provenance.rawFixtureSha256(), provenance.inputFingerprint(),
                        provenance.normalizedArtifactId(), provenance.mediaType(),
                        provenance.encodedBytes(), provenance.width(), provenance.height(),
                        prepared.blobWrites(), prepared.blobReads()),
                baseline, successor, metrics);
    }

    private static BranchResult acquireBranch(
            Branch branch,
            ProductViewHarness.PreparedProductView prepared,
            List<VisualView> views,
            String planVersion,
            String planIdentity,
            BoundedVisualInspection.ResourceSummary actionResources,
            LayeredVisualCorpus.Case evaluationCase,
            AcquisitionPolicy policy,
            VisualEvidenceAcquisition acquisition
    ) {
        if (views.isEmpty() || views.size() > ArtifactSet.MAXIMUM_ARTIFACTS) {
            throw invalid("R5P_PAIRED_PLAN_COVERAGE_INVALID");
        }
        var artifacts = new ArrayList<ArtifactSet.Artifact>();
        for (var ordinal = 0; ordinal < views.size(); ordinal++) {
            var image = views.get(ordinal).providerImage();
            artifacts.add(new ArtifactSet.Artifact(
                    image.artifactId(), ordinal, image.mediaType(), image.bytes(),
                    image.width(), image.height(), true));
        }
        var acquisitionInput = ArtifactSet.canonical(artifacts);
        var started = System.nanoTime();
        var observation = Objects.requireNonNull(
                acquisition.acquire(acquisitionInput, policy), "observation");
        var acquisitionMicros = Math.max(0L, Math.floorDiv(System.nanoTime() - started, 1_000L));
        validateObservation(views, observation, policy);

        var projected = project(views, observation, prepared.artifactSet().artifacts().getFirst());
        var coalesced = coalesce(projected);
        var measured = new RapidOcrShadowCaseEvaluator().evaluateProjectedAgainstSameGold(
                evaluationCase, prepared.artifactSet().artifacts().getFirst().artifactId(),
                evaluationLines(coalesced), confidenceStats(coalesced), acquisitionMicros);
        var traces = viewTraces(views, observation);
        var totalBytes = traces.stream().mapToLong(ViewTrace::encodedBytes)
                .reduce(0L, Math::addExact);
        var totalPixels = traces.stream().mapToLong(item ->
                Math.multiplyExact((long) item.width(), item.height()))
                .reduce(0L, Math::addExact);
        return new BranchResult(
                branch, planVersion, planIdentity, views.size(), observation.artifacts().size(),
                List.copyOf(traces), totalBytes, totalPixels,
                observation.observationCount(), projected.size(), coalesced.size(),
                observationIdentity(observation), metricInputIdentity(
                        prepared.artifactSet().artifacts().getFirst(), coalesced),
                metrics(measured),
                new ResourceSummary(
                        views.size(), actionResources.inspectedViews(), totalBytes, totalPixels,
                        actionResources.inspectedPixels(),
                        actionResources.additionalVisualTokens(),
                        actionResources.localTransformMillis(), acquisitionMicros),
                new ExternalProviderUsage(0, 0, 0), 0);
    }

    private static void validateObservation(
            List<VisualView> views,
            DocumentObservationIR observation,
            AcquisitionPolicy policy
    ) {
        if (!DocumentObservationIR.VERSION.equals(observation.contractVersion())
                || !policy.identity().equals(observation.acquisitionPolicyIdentity())
                || !policy.capabilityIdentity().equals(observation.capabilityIdentity())
                || observation.artifacts().size() != views.size()) {
            throw invalid("R5P_PAIRED_ACQUISITION_COVERAGE_INVALID");
        }
        for (var ordinal = 0; ordinal < views.size(); ordinal++) {
            var view = views.get(ordinal);
            var expected = view.providerImage();
            var actual = observation.artifacts().get(ordinal);
            if (actual.sourceOrdinal() != ordinal
                    || !expected.artifactId().equals(actual.artifactId())
                    || !expected.mediaType().equals(actual.mediaType())
                    || expected.width() != actual.width()
                    || expected.height() != actual.height()
                    || !actual.orientationApplied()) {
                throw invalid("R5P_PAIRED_ACQUISITION_COVERAGE_INVALID");
            }
        }
    }

    private static List<ProjectedLine> project(
            List<VisualView> views,
            DocumentObservationIR observation,
            ArtifactSet.Artifact source
    ) {
        var result = new ArrayList<ProjectedLine>();
        for (var viewOrdinal = 0; viewOrdinal < views.size(); viewOrdinal++) {
            var view = views.get(viewOrdinal);
            var artifact = observation.artifacts().get(viewOrdinal);
            for (var line : artifact.observations()) {
                var evidence = view.toOriginalEvidence(canonicalBox(
                        line.sourcePixelBox(), artifact.width(), artifact.height()));
                if (!source.artifactId().equals(evidence.artifactId())) {
                    throw invalid("R5P_PAIRED_SOURCE_PROJECTION_INVALID");
                }
                result.add(new ProjectedLine(
                        line.text(), evidence.boundingBox(), line.confidence(),
                        viewOrdinal, line.canonicalOrder()));
            }
        }
        result.sort(PROJECTED_ORDER);
        return List.copyOf(result);
    }

    private static CandidateBoundingBox canonicalBox(
            DocumentObservationIR.SourcePixelBox box,
            int width,
            int height
    ) {
        box.requireWithin(width, height);
        return new CandidateBoundingBox(
                Math.toIntExact(Math.floorDiv((long) box.left() * 10_000L, width)),
                Math.toIntExact(Math.floorDiv((long) box.top() * 10_000L, height)),
                Math.toIntExact(Math.ceilDiv((long) box.right() * 10_000L, width)),
                Math.toIntExact(Math.ceilDiv((long) box.bottom() * 10_000L, height)));
    }

    private static List<ProjectedLine> coalesce(List<ProjectedLine> source) {
        var result = new ArrayList<ProjectedLine>();
        for (var candidate : source) {
            var matched = -1;
            for (var index = 0; index < result.size(); index++) {
                var existing = result.get(index);
                if (candidate.text().equals(existing.text())
                        && overlapsAtFrozenThreshold(
                        candidate.boundingBox(), existing.boundingBox())) {
                    matched = index;
                    break;
                }
            }
            if (matched < 0) {
                result.add(candidate);
            } else if (prefer(candidate, result.get(matched))) {
                result.set(matched, candidate);
            }
        }
        result.sort(PROJECTED_ORDER);
        return List.copyOf(result);
    }

    private static boolean prefer(ProjectedLine candidate, ProjectedLine existing) {
        var confidence = Integer.compare(
                candidate.confidence().nativeValueBps(), existing.confidence().nativeValueBps());
        if (confidence != 0) return confidence > 0;
        var area = Long.compare(area(candidate.boundingBox()), area(existing.boundingBox()));
        if (area != 0) return area < 0;
        if (candidate.viewOrdinal() != existing.viewOrdinal()) {
            return candidate.viewOrdinal() < existing.viewOrdinal();
        }
        return candidate.lineOrdinal() < existing.lineOrdinal();
    }

    private static boolean overlapsAtFrozenThreshold(
            CandidateBoundingBox left,
            CandidateBoundingBox right
    ) {
        var width = Math.max(0, Math.min(left.right(), right.right())
                - Math.max(left.left(), right.left()));
        var height = Math.max(0, Math.min(left.bottom(), right.bottom())
                - Math.max(left.top(), right.top()));
        var intersection = Math.multiplyExact((long) width, height);
        var smaller = Math.min(area(left), area(right));
        return smaller > 0L && Math.multiplyExact(intersection, 10_000L)
                >= Math.multiplyExact(smaller, 5_000L);
    }

    private static long area(CandidateBoundingBox box) {
        return Math.multiplyExact((long) box.right() - box.left(), box.bottom() - box.top());
    }

    private static List<DocumentVisionObservation.TextLine> evaluationLines(
            List<ProjectedLine> lines
    ) {
        var observations = new ArrayList<DocumentVisionObservation.TextLine>();
        for (var ordinal = 0; ordinal < lines.size(); ordinal++) {
            var line = lines.get(ordinal);
            observations.add(new DocumentVisionObservation.TextLine(
                    "ocr-00-%03d".formatted(ordinal), ordinal,
                    line.boundingBox(),
                    DocumentVisionObservation.ConfidenceBucket.valueOf(
                            line.confidence().derivedBucket().name()),
                    line.text()));
        }
        return List.copyOf(observations);
    }

    private static RapidOcrShadowCaseRecord.ConfidenceStats confidenceStats(
            List<ProjectedLine> lines
    ) {
        long total = 0;
        long low = 0;
        long medium = 0;
        long high = 0;
        for (var line : lines) {
            total += line.confidence().nativeValueBps();
            switch (line.confidence().derivedBucket()) {
                case LOW -> low++;
                case MEDIUM -> medium++;
                case HIGH -> high++;
            }
        }
        return new RapidOcrShadowCaseRecord.ConfidenceStats(
                lines.size(), total, low, medium, high);
    }

    private static List<ViewTrace> viewTraces(
            List<VisualView> views,
            DocumentObservationIR observation
    ) {
        var result = new ArrayList<ViewTrace>();
        for (var ordinal = 0; ordinal < views.size(); ordinal++) {
            var view = views.get(ordinal);
            var descriptor = view.descriptor();
            var image = view.providerImage();
            var viewIdentity = "renderweave-r5p-executed-view/1.0:" + framedSha256(List.of(
                    "ordinal=" + ordinal,
                    "view-id=" + descriptor.viewId(),
                    "source=" + descriptor.sourceArtifactId(),
                    "source-ordinal=" + descriptor.sourceOrdinal(),
                    "kind=" + descriptor.kind(),
                    "source-box=" + coordinates(descriptor.sourceBoundingBox()),
                    "dimensions=" + image.width() + "x" + image.height(),
                    "provider-artifact=" + image.artifactId(),
                    "encoded-bytes=" + image.bytes().length));
            result.add(new ViewTrace(
                    ordinal, descriptor.viewId(), descriptor.kind().name(), viewIdentity,
                    descriptor.sourceArtifactId(), image.artifactId(), image.width(), image.height(),
                    image.bytes().length, sha256(image.bytes()),
                    observation.artifacts().get(ordinal).observations().size()));
        }
        return List.copyOf(result);
    }

    private static String observationIdentity(DocumentObservationIR observation) {
        var frames = new ArrayList<String>();
        frames.add("policy=" + observation.acquisitionPolicyIdentity());
        frames.add("capability=" + observation.capabilityIdentity());
        for (var artifact : observation.artifacts()) {
            frames.add("artifact=" + artifact.sourceOrdinal() + ":" + artifact.artifactId()
                    + ":" + artifact.width() + "x" + artifact.height());
            for (var line : artifact.observations()) {
                frames.add("line=" + line.canonicalOrder() + ":"
                        + pixels(line.sourcePixelBox()) + ":"
                        + line.confidence().nativeValueBps() + ":" + line.text());
            }
        }
        return "renderweave-r5p-temporary-observation/1.0:" + framedSha256(frames);
    }

    private static String metricInputIdentity(
            ArtifactSet.Artifact source,
            List<ProjectedLine> lines
    ) {
        var frames = new ArrayList<String>();
        frames.add("projection=" + PROJECTION_IDENTITY);
        frames.add("coalescing=" + COALESCING_IDENTITY);
        frames.add("source=" + source.artifactId() + ":" + source.width() + "x" + source.height());
        for (var line : lines) {
            frames.add("line=" + coordinates(line.boundingBox()) + ":"
                    + line.confidence().nativeValueBps() + ":" + line.text());
        }
        return "renderweave-r5p-metric-input/1.0:" + framedSha256(frames);
    }

    private static CaseMetrics metrics(RapidOcrShadowCaseRecord value) {
        var ocr = value.ocr();
        return new CaseMetrics(
                value.layout().lines().expected(), value.layout().lines().matched(),
                value.layout().lines().recallBps(),
                Math.addExact(Math.addExact(
                        ocr.characterSubstitutions(), ocr.characterInsertions()),
                        ocr.characterDeletions()),
                ocr.hallucinationCases(),
                value.order().expectedEdges(), value.order().comparableEdges(),
                value.order().correctEdges(), value.order().accuracyBps(),
                value.repeat().expectedMemberships(),
                value.repeat().observableMemberships(),
                value.repeat().membershipRecallBps());
    }

    private static PairMetrics compare(CaseMetrics baseline, CaseMetrics successor) {
        var matchedGain = Math.subtractExact(
                successor.matchedLines(), baseline.matchedLines());
        var characterErrorReduction = Math.subtractExact(
                baseline.characterErrors(), successor.characterErrors());
        var hallucinationIncrease = Math.subtractExact(
                successor.hallucinationCases(), baseline.hallucinationCases());
        return new PairMetrics(
                matchedGain, successor.lineRecallBps() - baseline.lineRecallBps(),
                characterErrorReduction, hallucinationIncrease,
                successor.orderAccuracyBps() - baseline.orderAccuracyBps(),
                successor.repeatRecallBps() - baseline.repeatRecallBps(),
                matchedGain > 0 || characterErrorReduction > 0,
                hallucinationIncrease <= 0);
    }

    private static CohortSummary summarize(
            List<PairedCaseResult> cases,
            R5PairedProductViewAssignment.Thresholds thresholds,
            boolean confirmation
    ) {
        long baselineExpected = 0;
        long baselineMatched = 0;
        long successorExpected = 0;
        long successorMatched = 0;
        long baselineErrors = 0;
        long successorErrors = 0;
        long baselineHallucinations = 0;
        long successorHallucinations = 0;
        long baselineComparableOrder = 0;
        long baselineCorrectOrder = 0;
        long successorComparableOrder = 0;
        long successorCorrectOrder = 0;
        long baselineExpectedMemberships = 0;
        long baselineObservableMemberships = 0;
        long successorExpectedMemberships = 0;
        long successorObservableMemberships = 0;
        var targetCases = 0;
        var hallucinationCases = 0;
        for (var item : cases) {
            var left = item.baseline().metrics();
            var right = item.successor().metrics();
            baselineExpected += left.expectedLines();
            baselineMatched += left.matchedLines();
            successorExpected += right.expectedLines();
            successorMatched += right.matchedLines();
            baselineErrors += left.characterErrors();
            successorErrors += right.characterErrors();
            baselineHallucinations += left.hallucinationCases();
            successorHallucinations += right.hallucinationCases();
            baselineComparableOrder += left.orderComparableEdges();
            baselineCorrectOrder += left.orderCorrectEdges();
            successorComparableOrder += right.orderComparableEdges();
            successorCorrectOrder += right.orderCorrectEdges();
            baselineExpectedMemberships += left.repeatExpectedMemberships();
            baselineObservableMemberships += left.repeatObservableMemberships();
            successorExpectedMemberships += right.repeatExpectedMemberships();
            successorObservableMemberships += right.repeatObservableMemberships();
            if (item.pairMetrics().targetImproved()) targetCases++;
            if (item.pairMetrics().hallucinationNonIncrease()) hallucinationCases++;
        }
        var baselineRecall = ratio(baselineMatched, baselineExpected);
        var successorRecall = ratio(successorMatched, successorExpected);
        var baselineOrder = ratio(baselineCorrectOrder, baselineComparableOrder);
        var successorOrder = ratio(successorCorrectOrder, successorComparableOrder);
        var baselineRepeat = ratio(
                baselineObservableMemberships, baselineExpectedMemberships);
        var successorRepeat = ratio(
                successorObservableMemberships, successorExpectedMemberships);
        var targetPass = targetCases == cases.size();
        var hallucinationPass = hallucinationCases == cases.size()
                && successorHallucinations - baselineHallucinations
                <= thresholds.maximumPerCaseHallucinationIncrease();
        var aggregatePass = !confirmation || successorRecall - baselineRecall
                >= thresholds.minimumConfirmationLineRecallGainBps()
                && baselineErrors - successorErrors
                >= thresholds.minimumConfirmationCharacterErrorReduction()
                && successorOrder - baselineOrder
                >= -thresholds.maximumConfirmationOrderRegressionBps()
                && successorRepeat - baselineRepeat
                >= -thresholds.maximumConfirmationRepeatRegressionBps();
        return new CohortSummary(
                cases.size(), targetCases, hallucinationCases,
                baselineMatched, successorMatched, baselineRecall, successorRecall,
                successorRecall - baselineRecall, baselineErrors, successorErrors,
                baselineErrors - successorErrors, baselineHallucinations,
                successorHallucinations, baselineOrder, successorOrder,
                successorOrder - baselineOrder, baselineRepeat, successorRepeat,
                successorRepeat - baselineRepeat, targetPass && hallucinationPass && aggregatePass);
    }

    private static int ratio(long numerator, long denominator) {
        return denominator == 0L ? 10_000 : Math.toIntExact(
                Math.floorDiv(Math.multiplyExact(numerator, 10_000L), denominator));
    }

    private static Determinism requireDeterministic(RunReport first, RunReport second) {
        var equivalentCases = 0;
        var equivalentBranches = 0;
        for (var index = 0; index < first.caseResults().size(); index++) {
            var left = first.caseResults().get(index);
            var right = second.caseResults().get(index);
            var baseline = deterministicIdentity(left, left.baseline());
            var successor = deterministicIdentity(left, left.successor());
            if (baseline.equals(deterministicIdentity(right, right.baseline()))) {
                equivalentBranches++;
            }
            if (successor.equals(deterministicIdentity(right, right.successor()))) {
                equivalentBranches++;
            }
            if (left.caseId().equals(right.caseId())
                    && left.caseIdentity().equals(right.caseIdentity())
                    && left.cohort() == right.cohort()
                    && left.sourcePartition() == right.sourcePartition()
                    && left.normalization().equals(right.normalization())
                    && left.pairMetrics().equals(right.pairMetrics())
                    && baseline.equals(deterministicIdentity(right, right.baseline()))
                    && successor.equals(deterministicIdentity(right, right.successor()))) {
                equivalentCases++;
            }
        }
        if (equivalentCases != first.caseResults().size()
                || equivalentBranches != first.caseResults().size() * 2) {
            throw new IllegalStateException("R5P_PAIRED_SECOND_RUN_DRIFT");
        }
        return new Determinism(
                first.caseResults().size(), equivalentCases,
                first.caseResults().size() * 2, equivalentBranches, true,
                "R5P_PAIRED_TWO_RUN_DETERMINISTIC");
    }

    private static String deterministicIdentity(
            PairedCaseResult item,
            BranchResult branch
    ) {
        var frames = new ArrayList<String>();
        frames.add("case=" + item.caseIdentity());
        frames.add("normalization=" + item.normalization());
        frames.add("branch=" + branch.branch());
        frames.add("plan=" + branch.planVersion() + ":" + branch.planIdentity());
        frames.add("counts=" + branch.plannedViewCount() + ":" + branch.acquiredViewCount()
                + ":" + branch.rawObservationCount() + ":"
                + branch.projectedObservationCount() + ":"
                + branch.coalescedObservationCount());
        branch.viewTrace().forEach(trace -> frames.add("view=" + trace));
        frames.add("observation=" + branch.observationIdentity());
        frames.add("metric-input=" + branch.metricInputIdentity());
        frames.add("metrics=" + branch.metrics());
        frames.add("resources=" + branch.resources().deterministicIdentity());
        return framedSha256(frames);
    }

    private static byte[] fixture(R5PairedProductViewAssignment.CaseAssignment assigned) {
        try (var input = PairedProductViewEvaluation.class.getClassLoader()
                .getResourceAsStream(assigned.rawFixtureResource())) {
            if (input == null) throw invalid("R5P_PAIRED_FIXTURE_MISSING");
            var bytes = input.readAllBytes();
            if (!assigned.rawFixtureSha256().equals(sha256(bytes))) {
                throw invalid("R5P_PAIRED_FIXTURE_DRIFT");
            }
            return bytes;
        } catch (IOException failure) {
            throw invalid("R5P_PAIRED_FIXTURE_DRIFT");
        }
    }

    private static String reportIdentity(Report report) {
        return REPORT_VERSION + ":" + sha256(encode(report));
    }

    private static byte[] encode(Object value) {
        try {
            return JSON.writeValueAsBytes(canonicalNode(JSON.valueToTree(value)));
        } catch (RuntimeException failure) {
            throw invalid("R5P_PAIRED_REPORT_INVALID");
        }
    }

    private static JsonNode canonicalNode(JsonNode source) {
        if (source.isObject()) {
            var result = JSON.createObjectNode();
            var properties = new ArrayList<>(source.properties());
            properties.sort(java.util.Map.Entry.comparingByKey());
            properties.forEach(property ->
                    result.set(property.getKey(), canonicalNode(property.getValue())));
            return result;
        }
        if (source.isArray()) {
            var result = JSON.createArrayNode();
            source.forEach(item -> result.add(canonicalNode(item)));
            return result;
        }
        return source;
    }

    private static String coordinates(CandidateBoundingBox box) {
        return box.left() + "," + box.top() + "," + box.right() + "," + box.bottom();
    }

    private static String pixels(DocumentObservationIR.SourcePixelBox box) {
        return box.left() + "," + box.top() + "," + box.right() + "," + box.bottom();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static String framedSha256(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                var encoded = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(encoded);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    private static final Comparator<ProjectedLine> PROJECTED_ORDER = Comparator
            .comparingInt((ProjectedLine item) -> item.boundingBox().top())
            .thenComparingInt(item -> item.boundingBox().left())
            .thenComparingInt(item -> item.boundingBox().bottom())
            .thenComparingInt(item -> item.boundingBox().right())
            .thenComparing(ProjectedLine::text)
            .thenComparingInt(ProjectedLine::viewOrdinal)
            .thenComparingInt(ProjectedLine::lineOrdinal);

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

        public static RunSession of(
                AcquisitionPolicy policy,
                VisualEvidenceAcquisition acquisition
        ) {
            return new RunSession(policy, acquisition, () -> { });
        }

        public static RunSession of(
                AcquisitionPolicy policy,
                VisualEvidenceAcquisition acquisition,
                Runnable closeAction
        ) {
            return new RunSession(policy, acquisition, closeAction);
        }

        public AcquisitionPolicy policy() {
            return policy;
        }

        public VisualEvidenceAcquisition acquisition() {
            return acquisition;
        }

        @Override
        public void close() {
            closeAction.run();
        }
    }

    public enum Branch { BASELINE, SUCCESSOR }

    public record Report(
            String reportVersion,
            String authorityIdentity,
            String assignmentIdentity,
            String evaluationIdentity,
            String evaluatorIdentity,
            String acquisitionPolicyIdentity,
            String capabilityIdentity,
            String projectionIdentity,
            String coalescingIdentity,
            String coalescingTextRule,
            String coalescingGeometryRule,
            String runProtocolIdentity,
            int caseCount,
            int executedBranchCount,
            int actualAcquisitionCalls,
            boolean measurementComplete,
            List<RunReport> runs,
            Determinism determinism,
            CohortSummary seenSummary,
            CohortSummary confirmationSummary,
            boolean producerQualityPass,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads,
            String terminalCode
    ) {
        public Report {
            if (!REPORT_VERSION.equals(reportVersion)
                    || !AUTHORITY_IDENTITY.equals(authorityIdentity)
                    || !VERSION.equals(evaluatorIdentity)
                    || !PROJECTION_IDENTITY.equals(projectionIdentity)
                    || !COALESCING_IDENTITY.equals(coalescingIdentity)
                    || !COALESCING_TEXT_RULE.equals(coalescingTextRule)
                    || !COALESCING_GEOMETRY_RULE.equals(coalescingGeometryRule)
                    || !RUN_PROTOCOL_IDENTITY.equals(runProtocolIdentity)
                    || !TERMINAL.equals(terminalCode)
                    || caseCount != 8 || executedBranchCount != 32
                    || actualAcquisitionCalls != 32 || !measurementComplete
                    || apiKeyReads != 0) {
                throw invalid("R5P_PAIRED_REPORT_INVALID");
            }
            requireIdentity(assignmentIdentity);
            requireIdentity(evaluationIdentity);
            requireIdentity(acquisitionPolicyIdentity);
            Objects.requireNonNull(capabilityIdentity, "capabilityIdentity");
            runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
            if (runs.size() != 2 || runs.get(0).runOrdinal() != 1
                    || runs.get(1).runOrdinal() != 2) {
                throw invalid("R5P_PAIRED_REPORT_INVALID");
            }
            Objects.requireNonNull(determinism, "determinism");
            Objects.requireNonNull(seenSummary, "seenSummary");
            Objects.requireNonNull(confirmationSummary, "confirmationSummary");
            Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
            if (producerQualityPass != (seenSummary.thresholdPass()
                    && confirmationSummary.thresholdPass())) {
                throw invalid("R5P_PAIRED_REPORT_INVALID");
            }
        }
    }

    public record RunReport(
            int runOrdinal,
            boolean complete,
            int caseCount,
            int executedBranchCount,
            int actualAcquisitionCalls,
            List<PairedCaseResult> caseResults
    ) {
        public RunReport {
            caseResults = List.copyOf(Objects.requireNonNull(caseResults, "caseResults"));
            if (runOrdinal < 1 || runOrdinal > 2 || !complete || caseCount != 8
                    || executedBranchCount != 16 || actualAcquisitionCalls != 16
                    || caseResults.size() != caseCount) {
                throw invalid("R5P_PAIRED_RUN_INVALID");
            }
        }
    }

    public record PairedCaseResult(
            String caseId,
            String caseIdentity,
            R5PairedProductViewAssignment.Cohort cohort,
            LayeredEvaluationRecord.Partition sourcePartition,
            NormalizationSummary normalization,
            BranchResult baseline,
            BranchResult successor,
            PairMetrics pairMetrics
    ) {
        public PairedCaseResult {
            if (caseId == null || !caseId.matches("[a-z][a-z0-9-]{0,127}")) {
                throw invalid("R5P_PAIRED_CASE_INVALID");
            }
            requireIdentity(caseIdentity);
            Objects.requireNonNull(cohort, "cohort");
            Objects.requireNonNull(sourcePartition, "sourcePartition");
            Objects.requireNonNull(normalization, "normalization");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(successor, "successor");
            Objects.requireNonNull(pairMetrics, "pairMetrics");
            if (baseline.branch() != Branch.BASELINE || successor.branch() != Branch.SUCCESSOR) {
                throw invalid("R5P_PAIRED_BRANCH_INVALID");
            }
        }
    }

    public record NormalizationSummary(
            String rawFixtureSha256,
            String inputFingerprint,
            String normalizedArtifactId,
            String mediaType,
            long encodedBytes,
            int width,
            int height,
            int blobWrites,
            int blobReads
    ) {
        public NormalizationSummary {
            requireSha(rawFixtureSha256);
            requireSha(inputFingerprint);
            requireSha(normalizedArtifactId);
            if (!"image/png".equals(mediaType) || encodedBytes < 1L
                    || width < 1 || height < 1 || blobWrites != 1 || blobReads != 1) {
                throw invalid("R5P_PAIRED_NORMALIZATION_INVALID");
            }
        }
    }

    public record BranchResult(
            Branch branch,
            String planVersion,
            String planIdentity,
            int plannedViewCount,
            int acquiredViewCount,
            List<ViewTrace> viewTrace,
            long totalEncodedBytes,
            long totalPixels,
            int rawObservationCount,
            int projectedObservationCount,
            int coalescedObservationCount,
            String observationIdentity,
            String metricInputIdentity,
            CaseMetrics metrics,
            ResourceSummary resources,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads
    ) {
        public BranchResult {
            Objects.requireNonNull(branch, "branch");
            Objects.requireNonNull(planVersion, "planVersion");
            requireIdentity(planIdentity);
            viewTrace = List.copyOf(Objects.requireNonNull(viewTrace, "viewTrace"));
            if (plannedViewCount < 1 || plannedViewCount != acquiredViewCount
                    || viewTrace.size() != plannedViewCount || totalEncodedBytes < 1L
                    || totalPixels < 1L || rawObservationCount < 0
                    || projectedObservationCount != rawObservationCount
                    || coalescedObservationCount < 0
                    || coalescedObservationCount > projectedObservationCount
                    || apiKeyReads != 0) {
                throw invalid("R5P_PAIRED_BRANCH_INVALID");
            }
            requireIdentity(observationIdentity);
            requireIdentity(metricInputIdentity);
            Objects.requireNonNull(metrics, "metrics");
            Objects.requireNonNull(resources, "resources");
            Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
        }
    }

    public record ViewTrace(
            int planOrdinal,
            String viewId,
            String kind,
            String viewIdentity,
            String sourceArtifactId,
            String providerArtifactId,
            int width,
            int height,
            long encodedBytes,
            String encodedSha256,
            int observationCount
    ) {
        public ViewTrace {
            if (planOrdinal < 0 || viewId == null || viewId.isBlank()
                    || kind == null || kind.isBlank() || width < 1 || height < 1
                    || encodedBytes < 1L || observationCount < 0) {
                throw invalid("R5P_PAIRED_VIEW_TRACE_INVALID");
            }
            requireIdentity(viewIdentity);
            requireSha(sourceArtifactId);
            requireSha(providerArtifactId);
            requireSha(encodedSha256);
        }
    }

    public record CaseMetrics(
            long expectedLines,
            long matchedLines,
            int lineRecallBps,
            long characterErrors,
            long hallucinationCases,
            long orderExpectedEdges,
            long orderComparableEdges,
            long orderCorrectEdges,
            int orderAccuracyBps,
            long repeatExpectedMemberships,
            long repeatObservableMemberships,
            int repeatRecallBps
    ) {
        public CaseMetrics {
            if (expectedLines < 0L || matchedLines < 0L || matchedLines > expectedLines
                    || lineRecallBps < 0 || lineRecallBps > 10_000
                    || lineRecallBps != ratio(matchedLines, expectedLines)
                    || characterErrors < 0L
                    || hallucinationCases < 0L || hallucinationCases > 1L
                    || orderExpectedEdges < 0L || orderComparableEdges < 0L
                    || orderCorrectEdges < 0L || orderCorrectEdges > orderComparableEdges
                    || orderComparableEdges > orderExpectedEdges
                    || orderAccuracyBps < 0 || orderAccuracyBps > 10_000
                    || orderAccuracyBps != ratio(orderCorrectEdges, orderComparableEdges)
                    || repeatExpectedMemberships < 0L
                    || repeatObservableMemberships < 0L
                    || repeatObservableMemberships > repeatExpectedMemberships
                    || repeatRecallBps < 0 || repeatRecallBps > 10_000
                    || repeatRecallBps != ratio(
                    repeatObservableMemberships, repeatExpectedMemberships)) {
                throw invalid("R5P_PAIRED_METRICS_INVALID");
            }
        }
    }

    public record PairMetrics(
            long matchedLineGain,
            int lineRecallGainBps,
            long characterErrorReduction,
            long hallucinationIncrease,
            int orderAccuracyDeltaBps,
            int repeatRecallDeltaBps,
            boolean targetImproved,
            boolean hallucinationNonIncrease
    ) {
        public PairMetrics {
            if (targetImproved != (matchedLineGain > 0L || characterErrorReduction > 0L)
                    || hallucinationNonIncrease != (hallucinationIncrease <= 0L)) {
                throw invalid("R5P_PAIRED_DELTA_INVALID");
            }
        }
    }

    public record ResourceSummary(
            int totalViews,
            int inspectedViews,
            long totalEncodedBytes,
            long totalPixels,
            long inspectedPixels,
            long additionalVisualTokens,
            long localTransformMillis,
            long acquisitionMicros
    ) {
        public ResourceSummary {
            if (totalViews < 1 || inspectedViews < 0 || inspectedViews > totalViews
                    || totalEncodedBytes < 1L || totalPixels < 1L || inspectedPixels < 0L
                    || additionalVisualTokens < 0L || localTransformMillis < 0L
                    || acquisitionMicros < 0L) {
                throw invalid("R5P_PAIRED_RESOURCE_INVALID");
            }
        }

        String deterministicIdentity() {
            return totalViews + ":" + inspectedViews + ":" + totalEncodedBytes + ":"
                    + totalPixels + ":" + inspectedPixels + ":" + additionalVisualTokens;
        }
    }

    public record CohortSummary(
            int caseCount,
            int targetImprovementCases,
            int hallucinationNonIncreaseCases,
            long baselineMatchedLines,
            long successorMatchedLines,
            int baselineLineRecallBps,
            int successorLineRecallBps,
            int lineRecallGainBps,
            long baselineCharacterErrors,
            long successorCharacterErrors,
            long characterErrorReduction,
            long baselineHallucinations,
            long successorHallucinations,
            int baselineOrderAccuracyBps,
            int successorOrderAccuracyBps,
            int orderAccuracyDeltaBps,
            int baselineRepeatRecallBps,
            int successorRepeatRecallBps,
            int repeatRecallDeltaBps,
            boolean thresholdPass
    ) {
        public CohortSummary {
            if (caseCount != 4 || targetImprovementCases < 0
                    || targetImprovementCases > caseCount
                    || hallucinationNonIncreaseCases < 0
                    || hallucinationNonIncreaseCases > caseCount
                    || baselineMatchedLines < 0L || successorMatchedLines < 0L
                    || baselineCharacterErrors < 0L || successorCharacterErrors < 0L
                    || baselineHallucinations < 0L || successorHallucinations < 0L) {
                throw invalid("R5P_PAIRED_COHORT_SUMMARY_INVALID");
            }
        }
    }

    public record Determinism(
            int comparedCases,
            int equivalentCases,
            int comparedBranches,
            int equivalentBranches,
            boolean deterministic,
            String verdictCode
    ) {
        public Determinism {
            if (comparedCases != 8 || equivalentCases != comparedCases
                    || comparedBranches != 16 || equivalentBranches != comparedBranches
                    || !deterministic
                    || !"R5P_PAIRED_TWO_RUN_DETERMINISTIC".equals(verdictCode)) {
                throw invalid("R5P_PAIRED_DETERMINISM_INVALID");
            }
        }
    }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public ExternalProviderUsage {
            if (attempts != 0L || reservations != 0L || costMicrosCny != 0L) {
                throw invalid("R5P_PAIRED_PROVIDER_USAGE_NONZERO");
            }
        }
    }

    public record Result(Report report, String reportIdentity, byte[] encodedReport) {
        public Result {
            Objects.requireNonNull(report, "report");
            requireIdentity(reportIdentity);
            encodedReport = Objects.requireNonNull(encodedReport, "encodedReport").clone();
            if (encodedReport.length == 0) throw invalid("R5P_PAIRED_REPORT_INVALID");
        }

        @Override
        public byte[] encodedReport() {
            return encodedReport.clone();
        }

        @Override
        public String toString() {
            return "Result[reportIdentity=" + reportIdentity + ", caseCount="
                    + report.caseCount() + ", payload=<redacted>]";
        }
    }

    private record ProjectedLine(
            String text,
            CandidateBoundingBox boundingBox,
            DocumentObservationIR.Confidence confidence,
            int viewOrdinal,
            int lineOrdinal
    ) { }

    private record Envelope(String envelopeVersion, String reportIdentity, Report report) { }

    private static void requireIdentity(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._/-]+:[0-9a-f]{64}")) {
            throw invalid("R5P_PAIRED_IDENTITY_INVALID");
        }
    }

    private static void requireSha(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid("R5P_PAIRED_SHA_INVALID");
        }
    }
}
