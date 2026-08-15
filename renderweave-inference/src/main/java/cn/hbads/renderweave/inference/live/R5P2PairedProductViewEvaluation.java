package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowCaseEvaluator;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowCaseRecord;
import cn.hbads.renderweave.inference.eval.visual.quality.R5P2Assignment;
import cn.hbads.renderweave.inference.eval.visual.quality.R5P2Authority;
import cn.hbads.renderweave.inference.eval.visual.quality.R5P2SourceLineReconciliation;
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
 * Runs the frozen R5P2 offline producer without persisting image, OCR, geometry, or gold payloads.
 * Each acquisition call is one complete branch request to the public adapter process protocol.
 */
public final class R5P2PairedProductViewEvaluation {
    public static final String VERSION =
            "renderweave-r5p2-paired-product-view-evaluator/1.0";
    public static final String REPORT_VERSION =
            "renderweave-r5p2-paired-product-view-report/1.0";
    private static final String ENVELOPE_VERSION =
            "renderweave-r5p2-paired-product-view-envelope/1.0";
    private static final String TERMINAL = "R5P2_PAIRED_PRODUCER_COMPLETE";
    private static final String DETERMINISM_VERDICT =
            "R5P2_PAIRED_TWO_RUN_DETERMINISTIC";
    private static final List<String> EXPECTED_CASE_ORDER = List.of(
            "transit-board-v3", "restaurant-menu-v3", "hospital-schedule-v3",
            "transit-board-v5", "transit-board-v2", "invoice-lines-v3",
            "school-timetable-v4", "building-directory-v5", "weather-forecast-v3",
            "warehouse-inventory-v2", "event-agenda-v4", "product-catalog-v5");
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
        var authority = R5P2Authority.load();
        var assignment = R5P2Assignment.load();
        var policy = RapidOcrBaselineContract.policy(
                RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
        validateFrozenContracts(authority, assignment, policy);

        var confirmationOrder = assignment.confirmationCases().stream()
                .map(R5P2Assignment.CaseAssignment::caseId).toList();
        var access = assignment.newHoldoutAccessAudit();
        var grant = access.open(R5P2Assignment.HoldoutAccessRole.OFFICIAL_PRODUCER,
                assignment.identity(), confirmationOrder);
        var corpus = new LayeredVisualCorpus();
        if (!authority.corpusIdentity().equals(corpus.corpusIdentity())) {
            throw invalid("R5P2_PAIRED_CORPUS_IDENTITY_DRIFT");
        }
        access.recordGoldMetricRead(grant, grant.holdoutCaseId());

        var runs = new ArrayList<RunReport>();
        for (var runOrdinal = 1; runOrdinal <= 2; runOrdinal++) {
            runs.add(run(runOrdinal, assignment, corpus, policy, factory));
        }
        access.seal(grant);

        var determinism = requireDeterministic(runs.get(0), runs.get(1));
        var firstRun = runs.getFirst();
        var diagnostic = summarize(firstRun.caseResults().stream()
                        .filter(item -> item.cohort()
                                == R5P2Assignment.Cohort.HISTORICAL_DIAGNOSTIC)
                        .toList(), assignment.thresholds(), false);
        var confirmation = summarize(firstRun.caseResults().stream()
                        .filter(item -> item.cohort()
                                == R5P2Assignment.Cohort.SEALED_CONFIRMATION)
                        .toList(), assignment.thresholds(), true);
        var transit = firstRun.caseResults().stream()
                .filter(item -> "transit-board-v3".equals(item.caseId()))
                .findFirst().orElseThrow(() -> invalid("R5P2_PAIRED_TRANSIT_CASE_MISSING"));
        var transitGate = new CaseGate(
                transit.caseId(), transit.pairMetrics().targetImproved(),
                transit.pairMetrics().hallucinationNonIncrease(),
                transit.pairMetrics().targetImproved()
                        && transit.pairMetrics().hallucinationNonIncrease());
        var accounting = sumAccounting(runs);
        var report = new Report(
                REPORT_VERSION, authority.authorityIdentity(), assignment.identity(),
                assignment.fixtureSetIdentity(), assignment.evaluationIdentity(),
                assignment.thresholdIdentity(), assignment.identities(), accounting,
                List.copyOf(runs), determinism, diagnostic, confirmation, transitGate,
                diagnostic.thresholdPass() && confirmation.thresholdPass(),
                new HoldoutAccessSummary(
                        R5P2Assignment.HoldoutAccessRole.OFFICIAL_PRODUCER.name(),
                        grant.holdoutCaseId(), access.status().name(), access.goldMetricReads()),
                new ExternalProviderUsage(0, 0, 0), 0, false, TERMINAL);
        var identity = reportIdentity(report);
        var encoded = encode(new Envelope(ENVELOPE_VERSION, identity, report));
        if (!readReport(encoded, identity).equals(report)) {
            throw new IllegalStateException("R5P2_PAIRED_REPORT_ROUND_TRIP_DRIFT");
        }
        return new Result(report, identity, encoded);
    }

    public Report readReport(byte[] encoded, String expectedIdentity) {
        if (encoded == null || encoded.length == 0 || encoded.length > MAXIMUM_REPORT_BYTES
                || expectedIdentity == null) {
            throw invalid("R5P2_PAIRED_REPORT_INVALID");
        }
        try {
            var envelope = JSON.readValue(encoded, Envelope.class);
            if (!ENVELOPE_VERSION.equals(envelope.envelopeVersion())
                    || envelope.report() == null
                    || !expectedIdentity.equals(envelope.reportIdentity())
                    || !reportIdentity(envelope.report()).equals(envelope.reportIdentity())) {
                throw invalid("R5P2_PAIRED_REPORT_INVALID");
            }
            return envelope.report();
        } catch (RuntimeException failure) {
            throw invalid("R5P2_PAIRED_REPORT_INVALID");
        }
    }

    private static void validateFrozenContracts(
            R5P2Authority.Lock authority,
            R5P2Assignment assignment,
            AcquisitionPolicy policy
    ) {
        var identities = assignment.identities();
        if (!"R5P2_AUTHORITY_LOCKED".equals(authority.terminalCode())
                || !authority.externalProviderUsage().zeroUsage()
                || authority.apiKeyReads() != 0
                || !assignment.externalProviderUsage().zeroUsage()
                || assignment.apiKeyReads() != 0) {
            throw invalid("R5P2_PAIRED_PROVIDER_BOUNDARY_VIOLATED");
        }
        if (!VERSION.equals(identities.evaluatorIdentity())
                || !MultiScaleVisualViewPlanner.VERSION.equals(identities.staticPlannerVersion())
                || !BoundedVisualInspection.VERSION.equals(identities.actionModuleVersion())
                || !R5P2SourceLineReconciliation.PROJECTION_IDENTITY.equals(
                        identities.projectionIdentity())
                || !R5P2SourceLineReconciliation.POLICY_IDENTITY.equals(
                        identities.reconciliationPolicyIdentity())
                || !RapidOcrShadowCaseEvaluator.VERSION.equals(
                        identities.caseEvaluatorIdentity())
                || !("AcquisitionPolicy/1.0:" + policy.identity()).equals(
                        identities.acquisitionPolicyIdentity())
                || !policy.capabilityIdentity().equals(identities.capabilityIdentity())) {
            throw invalid("R5P2_PAIRED_FROZEN_CONTRACT_DRIFT");
        }
    }

    private static RunReport run(
            int runOrdinal,
            R5P2Assignment assignment,
            LayeredVisualCorpus corpus,
            AcquisitionPolicy expectedPolicy,
            RunSessionFactory factory
    ) {
        var results = new ArrayList<PairedCaseResult>();
        var branchProcesses = 0;
        var artifactViews = 0;
        try (var session = Objects.requireNonNull(factory.open(runOrdinal), "runSession")) {
            if (!expectedPolicy.equals(session.policy())) {
                throw invalid("R5P2_PAIRED_ACQUISITION_POLICY_DRIFT");
            }
            for (var assigned : assignment.cases()) {
                var evaluationCase = corpus.require(assigned.caseId());
                validateCaseClosure(assigned, evaluationCase);
                var result = evaluateCase(
                        assigned, evaluationCase, expectedPolicy, session.acquisition());
                results.add(result);
                branchProcesses = Math.addExact(branchProcesses, 2);
                artifactViews = Math.addExact(artifactViews,
                        Math.addExact(result.baseline().artifactViews(),
                                result.successor().artifactViews()));
            }
        }
        return new RunReport(runOrdinal,
                new ProcessAccounting(1, branchProcesses, artifactViews,
                        results.size(), results.size()), List.copyOf(results));
    }

    private static void validateCaseClosure(
            R5P2Assignment.CaseAssignment assigned,
            LayeredVisualCorpus.Case actual
    ) {
        if (!assigned.caseId().equals(actual.caseId())
                || !assigned.caseIdentity().equals(actual.caseIdentity())
                || assigned.partition() != actual.partition()
                || assigned.difficulty() != actual.difficulty()
                || !assigned.failureSlices().equals(actual.failureSlices())
                || !assigned.renderIdentity().equals(actual.renderIdentity())) {
            throw invalid("R5P2_PAIRED_CASE_CLOSURE_DRIFT");
        }
    }

    private static PairedCaseResult evaluateCase(
            R5P2Assignment.CaseAssignment assigned,
            LayeredVisualCorpus.Case evaluationCase,
            AcquisitionPolicy policy,
            VisualEvidenceAcquisition acquisition
    ) {
        var raw = fixture(assigned);
        var prepared = new ProductViewHarness().prepare(List.of(
                new ProductViewHarness.RawRasterFixture(
                        assigned.caseId(), assigned.caseId() + ".png", "image/png", raw)),
                R5P2Assignment.NORMALIZATION_PROFILE_ID,
                assigned.normalizationSourceReference());
        var provenance = prepared.normalizationProvenance().getFirst();
        if (!assigned.rawFixtureSha256().equals(provenance.rawFixtureSha256())
                || !assigned.normalizationFingerprint().equals(provenance.inputFingerprint())
                || assigned.encodedBytes() != provenance.encodedBytes()
                || assigned.width() != provenance.width()
                || assigned.height() != provenance.height()) {
            throw invalid("R5P2_PAIRED_NORMALIZATION_DRIFT");
        }
        var normalization = new NormalizationSummary(
                assigned.normalizationSourceReference(), provenance.rawFixtureSha256(),
                provenance.inputFingerprint(), provenance.normalizedArtifactId(),
                provenance.mediaType(), provenance.encodedBytes(), provenance.width(),
                provenance.height(), prepared.blobWrites(), prepared.blobReads());

        var staticViews = prepared.plan().descriptors().stream()
                .map(descriptor -> prepared.plan().require(descriptor.viewId())).toList();
        var baseline = acquireBranch(
                Branch.BASELINE, prepared, staticViews, MultiScaleVisualViewPlanner.VERSION,
                ProductViewHarness.staticPlanIdentity(prepared.plan()),
                BoundedVisualInspection.ResourceSummary.empty(), evaluationCase,
                policy, acquisition);

        var action = new BoundedVisualInspection().inspect(
                prepared.artifactSet(), prepared.plan(),
                new BoundedVisualInspection.InspectionRequest(
                        BoundedVisualInspection.REQUEST_VERSION, assigned.regions()),
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial());
        if (action.disposition() != BoundedVisualInspection.Disposition.EXECUTED
                || !"R5P_INSPECTION_EXECUTED".equals(action.reasonCode())) {
            throw invalid("R5P2_PAIRED_SUCCESSOR_PLAN_INVALID");
        }
        var successor = acquireBranch(
                Branch.SUCCESSOR, prepared, action.executionViews(), action.planVersion(),
                action.planIdentity(), action.resourceSummary(), evaluationCase,
                policy, acquisition);
        return new PairedCaseResult(
                assigned.caseId(), assigned.caseIdentity(), assigned.cohort(),
                assigned.partition(), normalization, baseline, successor,
                compare(baseline.metrics(), successor.metrics()));
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
            throw invalid("R5P2_PAIRED_PLAN_COVERAGE_INVALID");
        }
        var artifacts = new ArrayList<ArtifactSet.Artifact>();
        for (var ordinal = 0; ordinal < views.size(); ordinal++) {
            var image = views.get(ordinal).providerImage();
            artifacts.add(new ArtifactSet.Artifact(
                    image.artifactId(), ordinal, image.mediaType(), image.bytes(),
                    image.width(), image.height(), true));
        }
        var acquisitionInput = ArtifactSet.canonical(artifacts);
        var requestIdentity = requestIdentity(branch, planVersion, planIdentity,
                policy, acquisitionInput);
        var started = System.nanoTime();
        var observation = Objects.requireNonNull(
                acquisition.acquire(acquisitionInput, policy), "observation");
        var acquisitionMicros = Math.max(
                0L, Math.floorDiv(System.nanoTime() - started, 1_000L));
        validateObservation(views, observation, policy);

        var source = prepared.artifactSet().artifacts().getFirst();
        var projected = project(views, observation, source);
        var reconciled = R5P2SourceLineReconciliation.reconcile(projected);
        var measured = new RapidOcrShadowCaseEvaluator().evaluateProjectedAgainstSameGold(
                evaluationCase, source.artifactId(),
                evaluationLines(reconciled.representatives()),
                confidenceStats(reconciled.representatives()), acquisitionMicros);
        var traces = viewTraces(views, observation);
        var totalBytes = traces.stream().mapToLong(ViewTrace::encodedBytes)
                .reduce(0L, Math::addExact);
        var totalPixels = traces.stream().mapToLong(item ->
                        Math.multiplyExact((long) item.width(), item.height()))
                .reduce(0L, Math::addExact);
        var resources = new ResourceSummary(
                views.size(), actionResources.inspectedViews(), totalBytes, totalPixels,
                actionResources.inspectedPixels(), actionResources.additionalVisualTokens(),
                actionResources.localTransformMillis(), acquisitionMicros);
        return new BranchResult(
                branch, planVersion, planIdentity, requestIdentity, views.size(),
                observation.artifacts().size(), 1, views.size(), List.copyOf(traces),
                totalBytes, totalPixels, observation.observationCount(), projected.size(),
                reconciled.representatives().size(), rawObservationIdentity(observation),
                canonicalObservationIdentity(observation),
                reconciledMetricInputIdentity(source, reconciled), metrics(measured),
                resourceIdentity(resources), resources,
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
            throw invalid("R5P2_PAIRED_ACQUISITION_COVERAGE_INVALID");
        }
        for (var ordinal = 0; ordinal < views.size(); ordinal++) {
            var expected = views.get(ordinal).providerImage();
            var actual = observation.artifacts().get(ordinal);
            if (actual.sourceOrdinal() != ordinal
                    || !expected.artifactId().equals(actual.artifactId())
                    || !expected.mediaType().equals(actual.mediaType())
                    || expected.width() != actual.width()
                    || expected.height() != actual.height()
                    || !actual.orientationApplied()) {
                throw invalid("R5P2_PAIRED_ACQUISITION_COVERAGE_INVALID");
            }
        }
    }

    private static List<R5P2SourceLineReconciliation.ProjectedLine> project(
            List<VisualView> views,
            DocumentObservationIR observation,
            ArtifactSet.Artifact source
    ) {
        var result = new ArrayList<R5P2SourceLineReconciliation.ProjectedLine>();
        for (var viewOrdinal = 0; viewOrdinal < views.size(); viewOrdinal++) {
            var view = views.get(viewOrdinal);
            var artifact = observation.artifacts().get(viewOrdinal);
            for (var line : artifact.observations()) {
                result.add(R5P2SourceLineReconciliation.project(
                        line.observationId(), source.artifactId(), viewOrdinal,
                        line.canonicalOrder(), artifact.width(), artifact.height(),
                        source.width(), source.height(),
                        new R5P2SourceLineReconciliation.PixelBox(
                                view.crop().left(), view.crop().top(),
                                view.crop().right(), view.crop().bottom()),
                        new R5P2SourceLineReconciliation.PixelBox(
                                line.sourcePixelBox().left(), line.sourcePixelBox().top(),
                                line.sourcePixelBox().right(), line.sourcePixelBox().bottom()),
                        line.confidence().nativeValueBps(), line.text()));
            }
        }
        if (result.size() != observation.observationCount()) {
            throw invalid("R5P2_PAIRED_SOURCE_PROJECTION_INVALID");
        }
        return List.copyOf(result);
    }

    private static List<DocumentVisionObservation.TextLine> evaluationLines(
            List<R5P2SourceLineReconciliation.ProjectedLine> lines
    ) {
        var result = new ArrayList<DocumentVisionObservation.TextLine>();
        for (var ordinal = 0; ordinal < lines.size(); ordinal++) {
            var line = lines.get(ordinal);
            result.add(new DocumentVisionObservation.TextLine(
                    "ocr-00-%03d".formatted(ordinal), ordinal,
                    new CandidateBoundingBox(
                            line.sourceBox().left(), line.sourceBox().top(),
                            line.sourceBox().right(), line.sourceBox().bottom()),
                    confidenceBucket(line.confidenceBps()), line.text()));
        }
        return List.copyOf(result);
    }

    private static DocumentVisionObservation.ConfidenceBucket confidenceBucket(int value) {
        if (value < 6_000) return DocumentVisionObservation.ConfidenceBucket.LOW;
        if (value < 8_500) return DocumentVisionObservation.ConfidenceBucket.MEDIUM;
        return DocumentVisionObservation.ConfidenceBucket.HIGH;
    }

    private static RapidOcrShadowCaseRecord.ConfidenceStats confidenceStats(
            List<R5P2SourceLineReconciliation.ProjectedLine> lines
    ) {
        long total = 0;
        long low = 0;
        long medium = 0;
        long high = 0;
        for (var line : lines) {
            total += line.confidenceBps();
            switch (confidenceBucket(line.confidenceBps())) {
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
            var viewIdentity = "renderweave-r5p2-executed-view/1.0:" + framedSha256(List.of(
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
                    descriptor.sourceArtifactId(), image.artifactId(), image.width(),
                    image.height(), image.bytes().length, sha256(image.bytes()),
                    observation.artifacts().get(ordinal).observations().size()));
        }
        return List.copyOf(result);
    }

    private static String requestIdentity(
            Branch branch,
            String planVersion,
            String planIdentity,
            AcquisitionPolicy policy,
            ArtifactSet artifacts
    ) {
        var frames = new ArrayList<String>();
        frames.add("contract=renderweave-r5p2-complete-branch-process/1.0");
        frames.add("branch=" + branch);
        frames.add("plan=" + planVersion + ":" + planIdentity);
        frames.add("policy=" + policy.identity());
        frames.add("artifact-count=" + artifacts.artifacts().size());
        for (var artifact : artifacts.artifacts()) {
            frames.add("artifact=" + artifact.sourceOrdinal() + ":" + artifact.artifactId()
                    + ":" + artifact.mediaType() + ":" + artifact.width() + "x"
                    + artifact.height() + ":" + artifact.bytes().length);
        }
        return "renderweave-r5p2-branch-request/1.0:" + framedSha256(frames);
    }

    private static String rawObservationIdentity(DocumentObservationIR observation) {
        return "renderweave-r5p2-raw-observation/1.0:"
                + framedSha256(observationFrames(observation, false));
    }

    private static String canonicalObservationIdentity(DocumentObservationIR observation) {
        return "renderweave-r5p2-canonical-observation/1.0:"
                + framedSha256(observationFrames(observation, true));
    }

    private static List<String> observationFrames(
            DocumentObservationIR observation,
            boolean includeProvenance
    ) {
        var frames = new ArrayList<String>();
        frames.add("policy=" + observation.acquisitionPolicyIdentity());
        frames.add("capability=" + observation.capabilityIdentity());
        if (includeProvenance) frames.add("provenance=" + observation.provenance());
        for (var artifact : observation.artifacts()) {
            frames.add("artifact=" + artifact.sourceOrdinal() + ":" + artifact.artifactId()
                    + ":" + artifact.width() + "x" + artifact.height());
            for (var line : artifact.observations()) {
                frames.add("line=" + line.observationId() + ":" + line.canonicalOrder()
                        + ":" + pixels(line.sourcePixelBox()) + ":"
                        + line.confidence().nativeValueBps() + ":" + line.text());
            }
        }
        return frames;
    }

    private static String reconciledMetricInputIdentity(
            ArtifactSet.Artifact source,
            R5P2SourceLineReconciliation.Outcome outcome
    ) {
        var frames = new ArrayList<String>();
        frames.add("projection=" + R5P2SourceLineReconciliation.PROJECTION_IDENTITY);
        frames.add("reconciliation=" + outcome.policyIdentity());
        frames.add("source=" + source.artifactId() + ":" + source.width()
                + "x" + source.height());
        frames.add("counts=" + outcome.inputCount() + ":" + outcome.clusterCount());
        for (var line : outcome.representatives()) {
            frames.add("line=" + sourceCoordinates(line.sourceBox()) + ":"
                    + line.confidenceBps() + ":" + line.text() + ":"
                    + line.viewOrdinal() + ":" + line.lineOrdinal());
        }
        return "renderweave-r5p2-reconciled-metric-input/1.0:" + framedSha256(frames);
    }

    private static CaseMetrics metrics(RapidOcrShadowCaseRecord value) {
        var ocr = value.ocr();
        return new CaseMetrics(
                value.layout().lines().expected(), value.layout().lines().matched(),
                value.layout().lines().recallBps(),
                Math.addExact(Math.addExact(
                        ocr.characterSubstitutions(), ocr.characterInsertions()),
                        ocr.characterDeletions()),
                ocr.hallucinationCases(), value.order().expectedEdges(),
                value.order().comparableEdges(), value.order().correctEdges(),
                value.order().accuracyBps(), value.repeat().expectedMemberships(),
                value.repeat().observableMemberships(), value.repeat().membershipRecallBps());
    }

    private static PairMetrics compare(CaseMetrics baseline, CaseMetrics successor) {
        var matchedGain = Math.subtractExact(
                successor.matchedLines(), baseline.matchedLines());
        var errorReduction = Math.subtractExact(
                baseline.characterErrors(), successor.characterErrors());
        var hallucinationIncrease = Math.subtractExact(
                successor.hallucinationCases(), baseline.hallucinationCases());
        return new PairMetrics(
                matchedGain, successor.lineRecallBps() - baseline.lineRecallBps(),
                errorReduction, hallucinationIncrease,
                successor.orderAccuracyBps() - baseline.orderAccuracyBps(),
                successor.repeatRecallBps() - baseline.repeatRecallBps(),
                matchedGain > 0 || errorReduction > 0, hallucinationIncrease <= 0);
    }

    private static CohortSummary summarize(
            List<PairedCaseResult> cases,
            R5P2Assignment.Thresholds thresholds,
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
        long baselineExpectedRepeats = 0;
        long baselineObservableRepeats = 0;
        long successorExpectedRepeats = 0;
        long successorObservableRepeats = 0;
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
            baselineExpectedRepeats += left.repeatExpectedMemberships();
            baselineObservableRepeats += left.repeatObservableMemberships();
            successorExpectedRepeats += right.repeatExpectedMemberships();
            successorObservableRepeats += right.repeatObservableMemberships();
            if (item.pairMetrics().targetImproved()) targetCases++;
            if (item.pairMetrics().hallucinationNonIncrease()) hallucinationCases++;
        }
        var baselineRecall = ratio(baselineMatched, baselineExpected);
        var successorRecall = ratio(successorMatched, successorExpected);
        var baselineOrder = ratio(baselineCorrectOrder, baselineComparableOrder);
        var successorOrder = ratio(successorCorrectOrder, successorComparableOrder);
        var baselineRepeat = ratio(baselineObservableRepeats, baselineExpectedRepeats);
        var successorRepeat = ratio(successorObservableRepeats, successorExpectedRepeats);
        var perCasePass = targetCases == cases.size()
                && hallucinationCases == cases.size();
        var aggregatePass = !confirmation
                || successorRecall - baselineRecall
                        >= thresholds.minimumConfirmationLineRecallGainBps()
                && baselineErrors - successorErrors
                        >= thresholds.minimumConfirmationCharacterErrorReduction()
                && successorOrder - baselineOrder
                        >= -thresholds.maximumConfirmationOrderRegressionBps()
                && successorRepeat - baselineRepeat
                        >= -thresholds.maximumConfirmationRepeatRegressionBps();
        return new CohortSummary(
                cases.size(), targetCases, hallucinationCases, baselineMatched,
                successorMatched, baselineRecall, successorRecall,
                successorRecall - baselineRecall, baselineErrors, successorErrors,
                baselineErrors - successorErrors, baselineHallucinations,
                successorHallucinations, baselineOrder, successorOrder,
                successorOrder - baselineOrder, baselineRepeat, successorRepeat,
                successorRepeat - baselineRepeat, perCasePass && aggregatePass);
    }

    private static int ratio(long numerator, long denominator) {
        return denominator == 0L ? 10_000 : Math.toIntExact(
                Math.floorDiv(Math.multiplyExact(numerator, 10_000L), denominator));
    }

    private static Determinism requireDeterministic(RunReport first, RunReport second) {
        if (first.caseResults().size() != second.caseResults().size()) {
            throw new IllegalStateException("R5P2_PAIRED_SECOND_RUN_DRIFT");
        }
        var equivalentCases = 0;
        var equivalentBranches = 0;
        for (var index = 0; index < first.caseResults().size(); index++) {
            var left = first.caseResults().get(index);
            var right = second.caseResults().get(index);
            var baseline = deterministicIdentity(left, left.baseline());
            var successor = deterministicIdentity(left, left.successor());
            var baselineEqual = baseline.equals(deterministicIdentity(right, right.baseline()));
            var successorEqual = successor.equals(deterministicIdentity(right, right.successor()));
            if (baselineEqual) equivalentBranches++;
            if (successorEqual) equivalentBranches++;
            if (left.caseId().equals(right.caseId())
                    && left.caseIdentity().equals(right.caseIdentity())
                    && left.cohort() == right.cohort()
                    && left.partition() == right.partition()
                    && left.normalization().equals(right.normalization())
                    && left.pairMetrics().equals(right.pairMetrics())
                    && baselineEqual && successorEqual) {
                equivalentCases++;
            }
        }
        if (equivalentCases != first.caseResults().size()
                || equivalentBranches != first.caseResults().size() * 2) {
            throw new IllegalStateException("R5P2_PAIRED_SECOND_RUN_DRIFT");
        }
        return new Determinism(
                first.caseResults().size(), equivalentCases,
                first.caseResults().size() * 2, equivalentBranches,
                true, DETERMINISM_VERDICT);
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
        frames.add("request=" + branch.requestIdentity());
        frames.add("counts=" + branch.plannedViewCount() + ":"
                + branch.acquiredViewCount() + ":" + branch.branchAcquisitionProcesses()
                + ":" + branch.artifactViews() + ":" + branch.rawObservationCount()
                + ":" + branch.projectedObservationCount() + ":"
                + branch.reconciledObservationCount());
        branch.viewTrace().forEach(trace -> frames.add("view=" + trace));
        frames.add("raw-observation=" + branch.rawObservationIdentity());
        frames.add("canonical-observation=" + branch.canonicalObservationIdentity());
        frames.add("metric-input=" + branch.reconciledMetricInputIdentity());
        frames.add("metrics=" + branch.metrics());
        frames.add("resources=" + branch.resourceIdentity());
        return framedSha256(frames);
    }

    private static String resourceIdentity(ResourceSummary resources) {
        return "renderweave-r5p2-branch-resources/1.0:" + framedSha256(List.of(
                "total-views=" + resources.totalViews(),
                "inspected-views=" + resources.inspectedViews(),
                "total-encoded-bytes=" + resources.totalEncodedBytes(),
                "total-pixels=" + resources.totalPixels(),
                "inspected-pixels=" + resources.inspectedPixels(),
                "additional-visual-tokens=" + resources.additionalVisualTokens()));
    }

    private static ProcessAccounting sumAccounting(List<RunReport> runs) {
        var probes = 0;
        var branches = 0;
        var views = 0;
        var normalizations = 0;
        var actions = 0;
        for (var run : runs) {
            probes = Math.addExact(probes, run.accounting().capabilityProbeProcesses());
            branches = Math.addExact(branches, run.accounting().branchAcquisitionProcesses());
            views = Math.addExact(views, run.accounting().artifactViews());
            normalizations = Math.addExact(
                    normalizations, run.accounting().normalizationExecutions());
            actions = Math.addExact(actions, run.accounting().actionExecutions());
        }
        return new ProcessAccounting(probes, branches, views, normalizations, actions);
    }

    private static byte[] fixture(R5P2Assignment.CaseAssignment assigned) {
        try (var input = R5P2PairedProductViewEvaluation.class.getClassLoader()
                .getResourceAsStream(assigned.rawFixtureResource())) {
            if (input == null) throw invalid("R5P2_PAIRED_FIXTURE_MISSING");
            var bytes = input.readAllBytes();
            if (!assigned.rawFixtureSha256().equals(sha256(bytes))) {
                throw invalid("R5P2_PAIRED_FIXTURE_DRIFT");
            }
            return bytes;
        } catch (IOException failure) {
            throw invalid("R5P2_PAIRED_FIXTURE_DRIFT");
        }
    }

    private static String reportIdentity(Report report) {
        return REPORT_VERSION + ":" + sha256(encode(report));
    }

    private static byte[] encode(Object value) {
        try {
            return JSON.writeValueAsBytes(canonicalNode(JSON.valueToTree(value)));
        } catch (RuntimeException failure) {
            throw invalid("R5P2_PAIRED_REPORT_INVALID");
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

    private static String sourceCoordinates(R5P2SourceLineReconciliation.SourceBox box) {
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
                digest.update(Integer.toString(encoded.length)
                        .getBytes(StandardCharsets.US_ASCII));
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

        public AcquisitionPolicy policy() { return policy; }

        public VisualEvidenceAcquisition acquisition() { return acquisition; }

        @Override
        public void close() { closeAction.run(); }
    }

    public enum Branch { BASELINE, SUCCESSOR }

    public record Report(
            String reportVersion,
            String authorityIdentity,
            String assignmentIdentity,
            String fixtureSetIdentity,
            String evaluationIdentity,
            String thresholdIdentity,
            R5P2Assignment.Identities stageIdentities,
            ProcessAccounting accounting,
            List<RunReport> runs,
            Determinism determinism,
            CohortSummary diagnosticSummary,
            CohortSummary confirmationSummary,
            CaseGate transitBoardV3,
            boolean producerQualityObservationPass,
            HoldoutAccessSummary holdoutAccess,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads,
            boolean finalTerminalClaimed,
            String terminalCode
    ) {
        public Report {
            if (!REPORT_VERSION.equals(reportVersion)
                    || apiKeyReads != 0 || finalTerminalClaimed
                    || !TERMINAL.equals(terminalCode)) {
                throw invalid("R5P2_PAIRED_REPORT_INVALID");
            }
            requireIdentity(authorityIdentity);
            requireIdentity(assignmentIdentity);
            requireIdentity(fixtureSetIdentity);
            requireIdentity(evaluationIdentity);
            requireIdentity(thresholdIdentity);
            Objects.requireNonNull(stageIdentities, "stageIdentities");
            if (!VERSION.equals(stageIdentities.evaluatorIdentity())
                    || !R5P2SourceLineReconciliation.POLICY_IDENTITY.equals(
                            stageIdentities.reconciliationPolicyIdentity())
                    || !"two-isolated-complete-paired-runs-48-processes/1.0".equals(
                            stageIdentities.runProtocolIdentity())) {
                throw invalid("R5P2_PAIRED_REPORT_INVALID");
            }
            Objects.requireNonNull(accounting, "accounting");
            if (accounting.capabilityProbeProcesses() != 2
                    || accounting.branchAcquisitionProcesses() != 48
                    || accounting.artifactViews() <= accounting.branchAcquisitionProcesses()
                    || accounting.normalizationExecutions() != 24
                    || accounting.actionExecutions() != 24) {
                throw invalid("R5P2_PAIRED_ACCOUNTING_INVALID");
            }
            runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
            if (runs.size() != 2 || runs.get(0).runOrdinal() != 1
                    || runs.get(1).runOrdinal() != 2
                    || !accounting.equals(sumAccounting(runs))) {
                throw invalid("R5P2_PAIRED_REPORT_INVALID");
            }
            Objects.requireNonNull(determinism, "determinism");
            Objects.requireNonNull(diagnosticSummary, "diagnosticSummary");
            Objects.requireNonNull(confirmationSummary, "confirmationSummary");
            Objects.requireNonNull(transitBoardV3, "transitBoardV3");
            Objects.requireNonNull(holdoutAccess, "holdoutAccess");
            Objects.requireNonNull(externalProviderUsage, "externalProviderUsage");
            if (diagnosticSummary.caseCount() != 8 || confirmationSummary.caseCount() != 4
                    || producerQualityObservationPass != (diagnosticSummary.thresholdPass()
                            && confirmationSummary.thresholdPass())) {
                throw invalid("R5P2_PAIRED_REPORT_INVALID");
            }
        }
    }

    public record ProcessAccounting(
            int capabilityProbeProcesses,
            int branchAcquisitionProcesses,
            int artifactViews,
            int normalizationExecutions,
            int actionExecutions
    ) {
        public ProcessAccounting {
            if (capabilityProbeProcesses < 0 || branchAcquisitionProcesses < 0
                    || artifactViews < 0 || normalizationExecutions < 0
                    || actionExecutions < 0) {
                throw invalid("R5P2_PAIRED_ACCOUNTING_INVALID");
            }
        }
    }

    public record RunReport(
            int runOrdinal,
            ProcessAccounting accounting,
            List<PairedCaseResult> caseResults
    ) {
        public RunReport {
            Objects.requireNonNull(accounting, "accounting");
            caseResults = List.copyOf(Objects.requireNonNull(caseResults, "caseResults"));
            if (runOrdinal < 1 || runOrdinal > 2
                    || accounting.capabilityProbeProcesses() != 1
                    || accounting.branchAcquisitionProcesses() != 24
                    || accounting.artifactViews() <= accounting.branchAcquisitionProcesses()
                    || accounting.normalizationExecutions() != 12
                    || accounting.actionExecutions() != 12
                    || caseResults.size() != 12
                    || !EXPECTED_CASE_ORDER.equals(caseResults.stream()
                            .map(PairedCaseResult::caseId).toList())
                    || caseResults.stream().limit(8).anyMatch(item -> item.cohort()
                            != R5P2Assignment.Cohort.HISTORICAL_DIAGNOSTIC)
                    || caseResults.stream().skip(8).anyMatch(item -> item.cohort()
                            != R5P2Assignment.Cohort.SEALED_CONFIRMATION)) {
                throw invalid("R5P2_PAIRED_RUN_INVALID");
            }
            var expectedViews = caseResults.stream().mapToInt(item ->
                    Math.addExact(item.baseline().artifactViews(),
                            item.successor().artifactViews())).sum();
            if (accounting.artifactViews() != expectedViews) {
                throw invalid("R5P2_PAIRED_ACCOUNTING_INVALID");
            }
        }
    }

    public record PairedCaseResult(
            String caseId,
            String caseIdentity,
            R5P2Assignment.Cohort cohort,
            LayeredEvaluationRecord.Partition partition,
            NormalizationSummary normalization,
            BranchResult baseline,
            BranchResult successor,
            PairMetrics pairMetrics
    ) {
        public PairedCaseResult {
            if (caseId == null || !caseId.matches("[a-z][a-z0-9-]{0,127}")) {
                throw invalid("R5P2_PAIRED_CASE_INVALID");
            }
            requireIdentity(caseIdentity);
            Objects.requireNonNull(cohort, "cohort");
            Objects.requireNonNull(partition, "partition");
            Objects.requireNonNull(normalization, "normalization");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(successor, "successor");
            Objects.requireNonNull(pairMetrics, "pairMetrics");
            if (baseline.branch() != Branch.BASELINE
                    || successor.branch() != Branch.SUCCESSOR) {
                throw invalid("R5P2_PAIRED_BRANCH_INVALID");
            }
        }
    }

    public record NormalizationSummary(
            String sourceReference,
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
            if (sourceReference == null
                    || !sourceReference.matches("r5p2-raw-fixture:[a-z0-9-]+:[0-9a-f]{64}")) {
                throw invalid("R5P2_PAIRED_NORMALIZATION_INVALID");
            }
            requireSha(rawFixtureSha256);
            requireSha(inputFingerprint);
            requireSha(normalizedArtifactId);
            if (!"image/png".equals(mediaType) || encodedBytes < 1L
                    || width < 1 || height < 1 || blobWrites != 1 || blobReads != 1) {
                throw invalid("R5P2_PAIRED_NORMALIZATION_INVALID");
            }
        }
    }

    public record BranchResult(
            Branch branch,
            String planVersion,
            String planIdentity,
            String requestIdentity,
            int plannedViewCount,
            int acquiredViewCount,
            int branchAcquisitionProcesses,
            int artifactViews,
            List<ViewTrace> viewTrace,
            long totalEncodedBytes,
            long totalPixels,
            int rawObservationCount,
            int projectedObservationCount,
            int reconciledObservationCount,
            String rawObservationIdentity,
            String canonicalObservationIdentity,
            String reconciledMetricInputIdentity,
            CaseMetrics metrics,
            String resourceIdentity,
            ResourceSummary resources,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads
    ) {
        public BranchResult {
            Objects.requireNonNull(branch, "branch");
            Objects.requireNonNull(planVersion, "planVersion");
            requireIdentity(planIdentity);
            requireIdentity(requestIdentity);
            viewTrace = List.copyOf(Objects.requireNonNull(viewTrace, "viewTrace"));
            if (plannedViewCount < 1 || plannedViewCount != acquiredViewCount
                    || branchAcquisitionProcesses != 1 || artifactViews != plannedViewCount
                    || viewTrace.size() != plannedViewCount || totalEncodedBytes < 1L
                    || totalPixels < 1L || rawObservationCount < 1
                    || projectedObservationCount != rawObservationCount
                    || reconciledObservationCount < 1
                    || reconciledObservationCount > projectedObservationCount
                    || apiKeyReads != 0) {
                throw invalid("R5P2_PAIRED_BRANCH_INVALID");
            }
            requireIdentity(rawObservationIdentity);
            requireIdentity(canonicalObservationIdentity);
            requireIdentity(reconciledMetricInputIdentity);
            Objects.requireNonNull(metrics, "metrics");
            requireIdentity(resourceIdentity);
            Objects.requireNonNull(resources, "resources");
            if (!resourceIdentity.equals(
                    R5P2PairedProductViewEvaluation.resourceIdentity(resources))) {
                throw invalid("R5P2_PAIRED_RESOURCE_IDENTITY_INVALID");
            }
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
                throw invalid("R5P2_PAIRED_VIEW_TRACE_INVALID");
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
                    || characterErrors < 0L || hallucinationCases < 0L
                    || hallucinationCases > 1L || orderExpectedEdges < 0L
                    || orderComparableEdges < 0L || orderCorrectEdges < 0L
                    || orderCorrectEdges > orderComparableEdges
                    || orderComparableEdges > orderExpectedEdges
                    || orderAccuracyBps != ratio(orderCorrectEdges, orderComparableEdges)
                    || repeatExpectedMemberships < 0L
                    || repeatObservableMemberships < 0L
                    || repeatObservableMemberships > repeatExpectedMemberships
                    || repeatRecallBps != ratio(
                            repeatObservableMemberships, repeatExpectedMemberships)) {
                throw invalid("R5P2_PAIRED_METRICS_INVALID");
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
                throw invalid("R5P2_PAIRED_DELTA_INVALID");
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
                throw invalid("R5P2_PAIRED_RESOURCE_INVALID");
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
            if (caseCount != 4 && caseCount != 8
                    || targetImprovementCases < 0 || targetImprovementCases > caseCount
                    || hallucinationNonIncreaseCases < 0
                    || hallucinationNonIncreaseCases > caseCount
                    || baselineMatchedLines < 0L || successorMatchedLines < 0L
                    || baselineCharacterErrors < 0L || successorCharacterErrors < 0L
                    || baselineHallucinations < 0L || successorHallucinations < 0L) {
                throw invalid("R5P2_PAIRED_COHORT_SUMMARY_INVALID");
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
            if (comparedCases != 12 || equivalentCases != comparedCases
                    || comparedBranches != 24 || equivalentBranches != comparedBranches
                    || !deterministic || !DETERMINISM_VERDICT.equals(verdictCode)) {
                throw invalid("R5P2_PAIRED_DETERMINISM_INVALID");
            }
        }
    }

    public record CaseGate(
            String caseId,
            boolean targetImproved,
            boolean hallucinationNonIncrease,
            boolean pass
    ) {
        public CaseGate {
            if (!"transit-board-v3".equals(caseId)
                    || pass != (targetImproved && hallucinationNonIncrease)) {
                throw invalid("R5P2_PAIRED_TRANSIT_GATE_INVALID");
            }
        }
    }

    public record HoldoutAccessSummary(
            String role,
            String caseId,
            String status,
            int goldMetricReads
    ) {
        public HoldoutAccessSummary {
            if (!R5P2Assignment.HoldoutAccessRole.OFFICIAL_PRODUCER.name().equals(role)
                    || !"product-catalog-v5".equals(caseId)
                    || !R5P2Assignment.HoldoutAccessStatus.SEALED.name().equals(status)
                    || goldMetricReads != 1) {
                throw invalid("R5P2_PAIRED_HOLDOUT_ACCESS_INVALID");
            }
        }
    }

    public record ExternalProviderUsage(
            long attempts,
            long reservations,
            long costMicrosCny
    ) {
        public ExternalProviderUsage {
            if (attempts != 0L || reservations != 0L || costMicrosCny != 0L) {
                throw invalid("R5P2_PAIRED_PROVIDER_USAGE_NONZERO");
            }
        }
    }

    public record Result(Report report, String reportIdentity, byte[] encodedReport) {
        public Result {
            Objects.requireNonNull(report, "report");
            requireIdentity(reportIdentity);
            encodedReport = Objects.requireNonNull(encodedReport, "encodedReport").clone();
            if (encodedReport.length == 0 || encodedReport.length > MAXIMUM_REPORT_BYTES) {
                throw invalid("R5P2_PAIRED_REPORT_INVALID");
            }
        }

        @Override
        public byte[] encodedReport() { return encodedReport.clone(); }

        @Override
        public String toString() {
            return "Result[reportIdentity=" + reportIdentity + ", caseCount=12, payload=<redacted>]";
        }
    }

    private static void requireIdentity(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._/-]+:[0-9a-f]{64}")) {
            throw invalid("R5P2_PAIRED_IDENTITY_INVALID");
        }
    }

    private static void requireSha(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid("R5P2_PAIRED_SHA_INVALID");
        }
    }

    private record Envelope(String envelopeVersion, String reportIdentity, Report report) { }
}
