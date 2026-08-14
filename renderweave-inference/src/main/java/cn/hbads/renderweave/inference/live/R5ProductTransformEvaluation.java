package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowCaseEvaluator;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowCaseRecord;
import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluation;
import cn.hbads.renderweave.inference.eval.visual.VisualStageRasterizer;
import cn.hbads.renderweave.inference.eval.visual.quality.R5ProductTransformAssignment;
import cn.hbads.renderweave.inference.eval.visual.quality.R5ProductTransformEvidence;
import cn.hbads.renderweave.inference.eval.visual.quality.R5ProductTransformEvidenceJsonCodec;
import cn.hbads.renderweave.inference.vision.AcquisitionPolicy;
import cn.hbads.renderweave.inference.vision.ArtifactSet;
import cn.hbads.renderweave.inference.vision.DocumentObservationIR;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes the predeclared 3 DEV + 1 HOLDOUT product-raster transform gate twice. */
public final class R5ProductTransformEvaluation {
    public static final String VERSION = "renderweave-r5-product-transform-runner/1.0";

    public Result evaluate(RapidOcrShadowEvaluation.RunSessionFactory factory) {
        Objects.requireNonNull(factory, "factory");
        var corpus = new LayeredVisualCorpus();
        var assignment = R5ProductTransformAssignment.load();
        var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
        var first = run(factory, 1, corpus, assignment, policy);
        var second = run(factory, 2, corpus, assignment, policy);
        var secondById = second.cases().stream().collect(java.util.stream.Collectors.toMap(
                R5ProductTransformEvidence.CaseRecord::caseId, item -> item));
        var deterministicCases = Math.toIntExact(first.cases().stream()
                .filter(item -> equivalent(item, secondById.get(item.caseId()))).count());
        var evidence = R5ProductTransformEvidence.decide(
                evaluationIdentity(corpus, assignment, policy), List.of(first, second), deterministicCases);
        var codec = new R5ProductTransformEvidenceJsonCodec();
        var encoded = codec.write(evidence);
        var evidenceIdentity = codec.evidenceIdentity(evidence);
        codec.read(encoded, evidenceIdentity);
        return new Result(evidence, evidenceIdentity, encoded);
    }

    private static R5ProductTransformEvidence.RunRecord run(
            RapidOcrShadowEvaluation.RunSessionFactory factory,
            int runOrdinal,
            LayeredVisualCorpus corpus,
            R5ProductTransformAssignment assignment,
            AcquisitionPolicy expectedPolicy
    ) {
        try (var session = Objects.requireNonNull(factory.open(runOrdinal), "runSession")) {
            if (!expectedPolicy.equals(session.policy())) {
                throw new IllegalArgumentException("R5_PRODUCT_ACQUISITION_POLICY_DRIFT");
            }
            var result = new ArrayList<R5ProductTransformEvidence.CaseRecord>();
            var rasterizer = new VisualStageRasterizer();
            var transform = new R5ProductRasterTransform();
            var evaluator = new RapidOcrShadowCaseEvaluator();
            for (var assigned : assignment.cases()) {
                var evaluationCase = corpus.require(assigned.caseId());
                var rendered = rasterizer.render(evaluationCase.renderCase());
                if (!evaluationCase.renderIdentity().equals("render-sha256:" + rendered.sha256())) {
                    throw new IllegalStateException("R5_PRODUCT_SOURCE_RENDER_DRIFT");
                }
                var source = new VisualSourceImage(
                        rendered.sha256(), rendered.bytes(), rendered.width(), rendered.height());
                var staticPlan = new MultiScaleVisualViewPlanner().plan(List.of(source), List.of());
                var staticView = staticPlan.require("view-00-overview-00");
                var inspected = assigned.regions().stream().map(region -> transform.render(
                        source,
                        staticPlan.require(region.baseViewId()),
                        region.boundingBox(),
                        marginBps(region.marginPreset()),
                        longEdge(region.resolutionPreset()))).toList();

                var staticTimed = acquire(session, expectedPolicy, List.of(staticView.providerImage()));
                var inspectedImages = inspected.stream().map(item -> new cn.hbads.renderweave.inference.provider.ProviderImage(
                        item.artifactId(), item.mediaType(), item.bytes(), item.width(), item.height())).toList();
                var inspectedTimed = acquire(session, expectedPolicy, inspectedImages);
                var projectedStatic = project(
                        staticTimed.observation(), source, List.of(new Projection(
                                staticView.crop(), staticView.providerImage().width(), staticView.providerImage().height())),
                        expectedPolicy);
                var projections = inspected.stream().map(item -> new Projection(
                        item.sourceCrop(), item.width(), item.height())).toList();
                var projectedInspected = project(
                        inspectedTimed.observation(), source, projections, expectedPolicy);
                var staticRecord = evaluator.evaluateAgainstSameGold(
                        evaluationCase, projectedStatic, staticTimed.micros(), source.artifactId());
                var inspectedRecord = evaluator.evaluateAgainstSameGold(
                        evaluationCase, projectedInspected, inspectedTimed.micros(), source.artifactId());
                result.add(new R5ProductTransformEvidence.CaseRecord(
                        assigned.caseId(), evaluationCase.caseIdentity(), assigned.partition(),
                        source.width(), source.height(),
                        staticPlanIdentity(staticPlan), requestIdentity(assignment, assigned),
                        inspectedPlanIdentity(assignment, assigned, inspected),
                        1, inspected.size(),
                        Math.multiplyExact((long) staticView.providerImage().width(), staticView.providerImage().height()),
                        inspected.stream().mapToLong(item -> Math.multiplyExact((long) item.width(), item.height())).sum(),
                        staticView.providerImage().bytes().length,
                        inspected.stream().mapToLong(item -> item.bytes().length).sum(),
                        staticTimed.micros(), inspectedTimed.micros(),
                        staticResource(staticView), inspected.stream().map(R5ProductTransformEvaluation::resource).toList(),
                        metrics(staticRecord), metrics(inspectedRecord)));
            }
            return new R5ProductTransformEvidence.RunRecord(runOrdinal, result);
        }
    }

    private static TimedObservation acquire(
            RapidOcrShadowEvaluation.RunSession session,
            AcquisitionPolicy policy,
            List<cn.hbads.renderweave.inference.provider.ProviderImage> images
    ) {
        var artifacts = ArtifactSet.canonical(java.util.stream.IntStream.range(0, images.size())
                .mapToObj(index -> {
                    var image = images.get(index);
                    return new ArtifactSet.Artifact(
                            image.artifactId(), index, image.mediaType(), image.bytes(), image.width(), image.height(), true);
                }).toList());
        var started = System.nanoTime();
        var observation = session.acquisition().acquire(artifacts, policy);
        var micros = Math.max(0, Math.floorDiv(System.nanoTime() - started, 1_000));
        if (!DocumentObservationIR.VERSION.equals(observation.contractVersion())
                || !policy.identity().equals(observation.acquisitionPolicyIdentity())
                || !policy.capabilityIdentity().equals(observation.capabilityIdentity())
                || observation.artifacts().size() != artifacts.artifacts().size()) {
            throw new IllegalArgumentException("R5_PRODUCT_OBSERVATION_IDENTITY_DRIFT");
        }
        for (var index = 0; index < artifacts.artifacts().size(); index++) {
            if (!artifacts.artifacts().get(index).artifactId().equals(
                    observation.artifacts().get(index).artifactId())) {
                throw new IllegalArgumentException("R5_PRODUCT_OBSERVATION_IDENTITY_DRIFT");
            }
        }
        return new TimedObservation(observation, micros);
    }

    /** Evaluation-only projection; product IR and durable state never receive crop OCR. */
    private static DocumentObservationIR project(
            DocumentObservationIR observation,
            VisualSourceImage source,
            List<Projection> projections,
            AcquisitionPolicy policy
    ) {
        if (observation.artifacts().size() != projections.size()) {
            throw new IllegalArgumentException("R5_PRODUCT_PROJECTION_COUNT_DRIFT");
        }
        var lines = new ArrayList<DocumentObservationIR.TextLine>();
        for (var artifactIndex = 0; artifactIndex < observation.artifacts().size(); artifactIndex++) {
            var artifact = observation.artifacts().get(artifactIndex);
            var projection = projections.get(artifactIndex);
            if (artifact.width() != projection.viewWidth() || artifact.height() != projection.viewHeight()) {
                throw new IllegalArgumentException("R5_PRODUCT_PROJECTION_DIMENSION_DRIFT");
            }
            for (var line : artifact.observations()) {
                var order = lines.size();
                if (order >= 512) throw new IllegalArgumentException("R5_PRODUCT_PROJECTION_LIMIT_EXCEEDED");
                lines.add(new DocumentObservationIR.TextLine(
                        "ocr-00-%03d".formatted(order), order,
                        project(line.sourcePixelBox(), projection), line.confidence(), line.text(), line.sensitivity()));
            }
        }
        return DocumentObservationIR.canonical(policy, observation.provenance(), List.of(
                new DocumentObservationIR.ArtifactObservation(
                        source.artifactId(), 0, "image/png", source.width(), source.height(), true, lines)));
    }

    private static DocumentObservationIR.SourcePixelBox project(
            DocumentObservationIR.SourcePixelBox box,
            Projection projection
    ) {
        var crop = projection.crop();
        var cropWidth = crop.right() - crop.left();
        var cropHeight = crop.bottom() - crop.top();
        var left = crop.left() + Math.toIntExact(Math.floorDiv(
                Math.multiplyExact((long) box.left(), cropWidth), projection.viewWidth()));
        var top = crop.top() + Math.toIntExact(Math.floorDiv(
                Math.multiplyExact((long) box.top(), cropHeight), projection.viewHeight()));
        var right = crop.left() + Math.toIntExact(Math.ceilDiv(
                Math.multiplyExact((long) box.right(), cropWidth), projection.viewWidth()));
        var bottom = crop.top() + Math.toIntExact(Math.ceilDiv(
                Math.multiplyExact((long) box.bottom(), cropHeight), projection.viewHeight()));
        return new DocumentObservationIR.SourcePixelBox(
                Math.clamp(left, crop.left(), crop.right() - 1),
                Math.clamp(top, crop.top(), crop.bottom() - 1),
                Math.clamp(right, crop.left() + 1, crop.right()),
                Math.clamp(bottom, crop.top() + 1, crop.bottom()));
    }

    private static R5ProductTransformEvidence.CaseMetrics metrics(RapidOcrShadowCaseRecord record) {
        var ocr = record.ocr();
        return new R5ProductTransformEvidence.CaseMetrics(
                record.observationCount(), record.layout().lines().expected(), record.layout().lines().matched(),
                ocr.predictedCharacters(),
                Math.addExact(Math.addExact(ocr.characterSubstitutions(), ocr.characterInsertions()),
                        ocr.characterDeletions()),
                ocr.hallucinationCases(), record.order().expectedEdges(), record.order().comparableEdges(),
                record.order().correctEdges(), record.repeat().expectedMemberships(),
                record.repeat().observableMemberships());
    }

    private static R5ProductTransformEvidence.ViewResource staticResource(VisualView view) {
        var image = view.providerImage();
        var descriptor = view.descriptor();
        var identity = "renderweave-r5-static-view/1.0:" + framedSha256(List.of(
                descriptor.viewId(), descriptor.sourceArtifactId(), descriptor.kind().name(),
                descriptor.width() + "x" + descriptor.height(), image.artifactId()));
        return new R5ProductTransformEvidence.ViewResource(
                identity, image.artifactId(), image.width(), image.height(), image.bytes().length);
    }

    private static R5ProductTransformEvidence.ViewResource resource(R5ProductRasterTransform.RasterView view) {
        return new R5ProductTransformEvidence.ViewResource(
                view.identity(), view.artifactId(), view.width(), view.height(), view.bytes().length);
    }

    private static int marginBps(String preset) {
        return switch (preset) {
            case "TIGHT_0000_BPS" -> 0;
            case "CONTEXT_0500_BPS" -> 500;
            default -> throw new IllegalArgumentException("R5_PRODUCT_MARGIN_PRESET_INVALID");
        };
    }

    private static int longEdge(String preset) {
        return switch (preset) {
            case "DETAIL_LONG_EDGE_1400" -> 1_400;
            case "INSPECT_LONG_EDGE_2400" -> 2_400;
            default -> throw new IllegalArgumentException("R5_PRODUCT_RESOLUTION_PRESET_INVALID");
        };
    }

    private static String staticPlanIdentity(VisualViewPlan plan) {
        return "renderweave-r5-static-plan/1.0:" + framedSha256(plan.descriptors().stream()
                .map(item -> item.viewId() + "|" + item.sourceArtifactId() + "|" + item.kind() + "|"
                        + item.width() + "x" + item.height()).toList());
    }

    private static String requestIdentity(
            R5ProductTransformAssignment assignment,
            R5ProductTransformAssignment.CaseAssignment item
    ) {
        var values = new ArrayList<String>();
        values.add(assignment.identity());
        values.add(item.caseId());
        for (var region : item.regions()) {
            var box = region.boundingBox();
            values.add(region.baseViewId() + "|" + box.left() + "," + box.top() + "," + box.right() + ","
                    + box.bottom() + "|" + region.marginPreset() + "|" + region.resolutionPreset());
        }
        return "renderweave-r5-inspection-request-fixture/1.0:" + framedSha256(values);
    }

    private static String inspectedPlanIdentity(
            R5ProductTransformAssignment assignment,
            R5ProductTransformAssignment.CaseAssignment item,
            List<R5ProductRasterTransform.RasterView> views
    ) {
        var values = new ArrayList<String>();
        values.add(assignment.identity());
        values.add(requestIdentity(assignment, item));
        views.forEach(view -> values.add(view.identity()));
        return "renderweave-r5-inspected-plan/1.0:" + framedSha256(values);
    }

    private static String evaluationIdentity(
            LayeredVisualCorpus corpus,
            R5ProductTransformAssignment assignment,
            AcquisitionPolicy policy
    ) {
        return "renderweave-r5-product-transform-evaluation/1.0:" + framedSha256(List.of(
                VERSION, corpus.corpusIdentity(), corpus.annotationSetIdentity(), assignment.identity(),
                R5ProductRasterTransform.VERSION, "AcquisitionPolicy/1.0:" + policy.identity(),
                policy.capabilityIdentity(), "two-isolated-static-and-inspected-runs/1.0",
                "provider-attempts-reservations-cost-api-key-reads-zero/1.0"));
    }

    private static String framedSha256(List<String> values) {
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
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static boolean equivalent(
            R5ProductTransformEvidence.CaseRecord left,
            R5ProductTransformEvidence.CaseRecord right
    ) {
        return right != null && left.caseId().equals(right.caseId())
                && left.caseIdentity().equals(right.caseIdentity()) && left.partition() == right.partition()
                && left.sourceWidth() == right.sourceWidth() && left.sourceHeight() == right.sourceHeight()
                && left.staticPlanIdentity().equals(right.staticPlanIdentity())
                && left.requestIdentity().equals(right.requestIdentity())
                && left.inspectedPlanIdentity().equals(right.inspectedPlanIdentity())
                && left.staticViewCount() == right.staticViewCount()
                && left.inspectedViewCount() == right.inspectedViewCount()
                && left.staticDecodedPixels() == right.staticDecodedPixels()
                && left.inspectedDecodedPixels() == right.inspectedDecodedPixels()
                && left.staticEncodedBytes() == right.staticEncodedBytes()
                && left.inspectedEncodedBytes() == right.inspectedEncodedBytes()
                && left.staticResource().equals(right.staticResource())
                && left.inspectedResources().equals(right.inspectedResources())
                && left.staticView().equals(right.staticView()) && left.inspected().equals(right.inspected());
    }

    public record Result(
            R5ProductTransformEvidence evidence,
            String evidenceIdentity,
            byte[] encodedEvidence
    ) {
        public Result {
            Objects.requireNonNull(evidence, "evidence");
            if (evidenceIdentity == null || !evidenceIdentity.matches(
                    "renderweave-r5-product-transform-evidence/1\\.0:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("R5_PRODUCT_EVIDENCE_IDENTITY_INVALID");
            }
            encodedEvidence = Objects.requireNonNull(encodedEvidence, "encodedEvidence").clone();
            if (encodedEvidence.length == 0) throw new IllegalArgumentException("R5_PRODUCT_EVIDENCE_EMPTY");
        }

        @Override
        public byte[] encodedEvidence() { return encodedEvidence.clone(); }

        @Override
        public String toString() {
            return "Result[evidenceIdentity=" + evidenceIdentity + ", disposition="
                    + evidence.disposition() + ", payload=<redacted>]";
        }
    }

    private record TimedObservation(DocumentObservationIR observation, long micros) { }

    private record Projection(
            MultiScaleVisualViewPlanner.PixelCrop crop,
            int viewWidth,
            int viewHeight
    ) { }
}
