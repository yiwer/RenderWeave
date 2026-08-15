package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.eval.visual.LayeredVisualAnnotation;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.eval.visual.quality.R5P2Assignment;
import cn.hbads.renderweave.inference.eval.visual.quality.R5P2Authority;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the ephemeral stdin contract for the independent R5P2 verifier.
 * It materializes product inputs but performs no OCR, metric computation, or decision.
 */
final class R5P2IndependentReplayProtocol {
    static final String VERSION = "renderweave-r5p2-independent-replay-input/1.0";
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    byte[] build() {
        var authority = R5P2Authority.load();
        var assignment = R5P2Assignment.load();
        if (!authority.externalProviderUsage().zeroUsage()
                || authority.apiKeyReads() != 0
                || !assignment.externalProviderUsage().zeroUsage()
                || assignment.apiKeyReads() != 0) {
            throw invalid("R5P2_A2_PROVIDER_BOUNDARY_VIOLATED");
        }
        var confirmationOrder = assignment.confirmationCases().stream()
                .map(R5P2Assignment.CaseAssignment::caseId).toList();
        var audit = assignment.newHoldoutAccessAudit();
        var grant = audit.open(R5P2Assignment.HoldoutAccessRole.INDEPENDENT_REPLAY,
                assignment.identity(), confirmationOrder);
        var corpus = new LayeredVisualCorpus();
        if (!authority.corpusIdentity().equals(corpus.corpusIdentity())) {
            throw invalid("R5P2_A2_CORPUS_IDENTITY_DRIFT");
        }
        audit.recordGoldMetricRead(grant, grant.holdoutCaseId());

        var runs = new ArrayList<RunInput>();
        for (var runOrdinal = 1; runOrdinal <= 2; runOrdinal++) {
            var cases = new ArrayList<CaseInput>();
            for (var assigned : assignment.cases()) {
                cases.add(materialize(assigned, corpus.require(assigned.caseId())));
            }
            runs.add(new RunInput(runOrdinal, List.copyOf(cases)));
        }
        audit.seal(grant);
        var protocol = new Protocol(
                VERSION, authority.authorityIdentity(), assignment.identity(),
                assignment.fixtureSetIdentity(), assignment.evaluationIdentity(),
                assignment.thresholdIdentity(), assignment.identities(),
                assignment.thresholds(),
                new AccessBoundary(
                        0, 0, 0, R5P2Assignment.HoldoutAccessRole.INDEPENDENT_REPLAY.name(),
                        grant.holdoutCaseId(), audit.status().name(), audit.goldMetricReads()),
                Map.of("attempts", 0, "reservations", 0, "costMicrosCny", 0),
                0, List.copyOf(runs));
        try {
            return JSON.writeValueAsBytes(canonical(JSON.valueToTree(protocol)));
        } catch (RuntimeException failure) {
            throw invalid("R5P2_A2_PROTOCOL_ENCODING_FAILED");
        }
    }

    private static CaseInput materialize(
            R5P2Assignment.CaseAssignment assigned,
            LayeredVisualCorpus.Case evaluationCase
    ) {
        validateCaseClosure(assigned, evaluationCase);
        var raw = fixture(assigned);
        var prepared = new ProductViewHarness().prepare(List.of(
                new ProductViewHarness.RawRasterFixture(
                        assigned.caseId(), assigned.caseId() + ".png", "image/png", raw)),
                R5P2Assignment.NORMALIZATION_PROFILE_ID,
                assigned.normalizationSourceReference());
        var provenance = prepared.normalizationProvenance().getFirst();
        var source = prepared.artifactSet().artifacts().getFirst();
        if (!assigned.rawFixtureSha256().equals(provenance.rawFixtureSha256())
                || !assigned.normalizationFingerprint().equals(provenance.inputFingerprint())
                || !assigned.rawFixtureSha256().equals(source.artifactId())
                || assigned.width() != source.width() || assigned.height() != source.height()
                || assigned.encodedBytes() != source.bytes().length
                || prepared.blobWrites() != 1 || prepared.blobReads() != 1) {
            throw invalid("R5P2_A2_NORMALIZATION_DRIFT");
        }

        var staticViews = prepared.plan().descriptors().stream()
                .map(descriptor -> prepared.plan().require(descriptor.viewId())).toList();
        var baseline = branch(
                "BASELINE", prepared.plan().planVersion(),
                ProductViewHarness.staticPlanIdentity(prepared.plan()), null, null,
                staticViews, 0, 0, 0, 0);
        var action = new BoundedVisualInspection().inspect(
                prepared.artifactSet(), prepared.plan(),
                new BoundedVisualInspection.InspectionRequest(
                        BoundedVisualInspection.REQUEST_VERSION, assigned.regions()),
                BoundedVisualInspection.AdaptiveInspectionPolicy.initial());
        if (action.disposition() != BoundedVisualInspection.Disposition.EXECUTED
                || !"R5P_INSPECTION_EXECUTED".equals(action.reasonCode())) {
            throw invalid("R5P2_A2_ACTION_MATERIALIZATION_FAILED");
        }
        var actionResources = action.resourceSummary();
        var successor = branch(
                "SUCCESSOR", action.planVersion(), action.planIdentity(),
                action.requestIdentity(), action.policyIdentity(), action.executionViews(),
                actionResources.inspectedViews(), actionResources.inspectedPixels(),
                actionResources.additionalVisualTokens(), actionResources.localTransformMillis());
        return new CaseInput(
                assigned.caseId(), assigned.caseIdentity(), assigned.cohort().name(),
                assigned.partition().name(), assigned.width(), assigned.height(),
                assigned.normalizationSourceReference(), assigned.rawFixtureSha256(), raw,
                new NormalizationInput(
                        provenance.inputFingerprint(), provenance.normalizedArtifactId(),
                        provenance.mediaType(), provenance.encodedBytes(), provenance.width(),
                        provenance.height(), prepared.blobWrites(), prepared.blobReads(),
                        source.bytes()),
                gold(evaluationCase.annotation()), request(assigned.regions()),
                List.of(baseline, successor));
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
            throw invalid("R5P2_A2_CASE_CLOSURE_DRIFT");
        }
    }

    private static BranchInput branch(
            String branch,
            String planVersion,
            String planIdentity,
            String actionRequestIdentity,
            String actionPolicyIdentity,
            List<VisualView> views,
            int inspectedViews,
            long inspectedPixels,
            long additionalVisualTokens,
            long localTransformMillis
    ) {
        var inputs = new ArrayList<ViewInput>();
        long totalBytes = 0;
        long totalPixels = 0;
        for (var ordinal = 0; ordinal < views.size(); ordinal++) {
            var view = views.get(ordinal);
            var descriptor = view.descriptor();
            var image = view.providerImage();
            var bytes = image.bytes();
            totalBytes = Math.addExact(totalBytes, bytes.length);
            totalPixels = Math.addExact(totalPixels,
                    Math.multiplyExact((long) image.width(), image.height()));
            inputs.add(new ViewInput(
                    ordinal, descriptor.viewId(), descriptor.sourceArtifactId(),
                    descriptor.sourceOrdinal(), descriptor.kind().name(),
                    box(descriptor.sourceBoundingBox()), descriptor.width(), descriptor.height(),
                    view.sourceWidth(), view.sourceHeight(),
                    List.of(view.crop().left(), view.crop().top(),
                            view.crop().right(), view.crop().bottom()),
                    image.artifactId(), image.mediaType(), bytes.length,
                    ProductViewHarness.sha256(bytes), bytes));
        }
        return new BranchInput(
                branch, planVersion, planIdentity, actionRequestIdentity,
                actionPolicyIdentity,
                new ResourceInput(
                        views.size(), inspectedViews, totalBytes, totalPixels,
                        inspectedPixels, additionalVisualTokens, localTransformMillis),
                List.copyOf(inputs));
    }

    private static GoldInput gold(LayeredVisualAnnotation annotation) {
        var lines = annotation.ocrLines().stream().map(line -> new GoldLine(
                line.lineId(), line.text(), box(line.geometry().bounds()))).toList();
        var regions = annotation.regions().stream()
                .map(LayeredVisualAnnotation.Region::regionId).toList();
        var edges = annotation.precedenceEdges().stream().map(edge -> new GoldEdge(
                edge.beforeRegionId(), edge.afterRegionId())).toList();
        var groups = annotation.repeatGroups().stream().map(group -> new GoldRepeatGroup(
                group.groupRegionId(), group.items().stream().map(item -> new GoldRepeatItem(
                        item.itemRegionId(), item.memberRegionIds())).toList())).toList();
        return new GoldInput(lines, regions, edges, groups);
    }

    private static InspectionRequestInput request(
            List<BoundedVisualInspection.InspectionRegion> regions
    ) {
        return new InspectionRequestInput(
                BoundedVisualInspection.REQUEST_VERSION,
                regions.stream().map(item -> new InspectionRegionInput(
                        item.baseViewId(), box(item.boundingBox()),
                        item.marginPreset().name(), item.resolutionPreset().name())).toList());
    }

    private static List<Integer> box(
            cn.hbads.renderweave.inference.candidate.CandidateBoundingBox value
    ) {
        return List.of(value.left(), value.top(), value.right(), value.bottom());
    }

    private static List<Integer> box(LayeredVisualAnnotation.Box value) {
        return List.of(value.left(), value.top(), value.right(), value.bottom());
    }

    private static byte[] fixture(R5P2Assignment.CaseAssignment assigned) {
        try (var input = R5P2IndependentReplayProtocol.class.getClassLoader()
                .getResourceAsStream(assigned.rawFixtureResource())) {
            if (input == null) throw invalid("R5P2_A2_FIXTURE_MISSING");
            var bytes = input.readAllBytes();
            if (!assigned.rawFixtureSha256().equals(ProductViewHarness.sha256(bytes))) {
                throw invalid("R5P2_A2_FIXTURE_DRIFT");
            }
            return bytes;
        } catch (IOException failure) {
            throw invalid("R5P2_A2_FIXTURE_DRIFT");
        }
    }

    private static JsonNode canonical(JsonNode source) {
        if (source.isObject()) {
            var result = JSON.createObjectNode();
            var properties = new ArrayList<>(source.properties());
            properties.sort(java.util.Map.Entry.comparingByKey());
            properties.forEach(property ->
                    result.set(property.getKey(), canonical(property.getValue())));
            return result;
        }
        if (source.isArray()) {
            var result = JSON.createArrayNode();
            source.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return source;
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    private record Protocol(
            String protocolVersion,
            String authorityIdentity,
            String assignmentIdentity,
            String fixtureSetIdentity,
            String evaluationIdentity,
            String thresholdIdentity,
            R5P2Assignment.Identities stageIdentities,
            R5P2Assignment.Thresholds thresholds,
            AccessBoundary accessBoundary,
            Map<String, Integer> externalProviderUsage,
            int apiKeyReads,
            List<RunInput> runs
    ) { }

    private record AccessBoundary(
            int producerReportReadsDuringReplay,
            int producerMetricReadsDuringReplay,
            int producerDecisionReadsDuringReplay,
            String holdoutRole,
            String holdoutCaseId,
            String holdoutStatus,
            int holdoutGoldMetricReads
    ) { }

    private record RunInput(int runOrdinal, List<CaseInput> cases) { }

    private record CaseInput(
            String caseId,
            String caseIdentity,
            String cohort,
            String partition,
            int width,
            int height,
            String normalizationSourceReference,
            String rawFixtureSha256,
            byte[] rawBytes,
            NormalizationInput normalization,
            GoldInput gold,
            InspectionRequestInput inspectionRequest,
            List<BranchInput> branches
    ) { }

    private record NormalizationInput(
            String inputFingerprint,
            String normalizedArtifactId,
            String mediaType,
            long encodedBytes,
            int width,
            int height,
            int blobWrites,
            int blobReads,
            byte[] normalizedBytes
    ) { }

    private record GoldInput(
            List<GoldLine> lines,
            List<String> regionIds,
            List<GoldEdge> precedenceEdges,
            List<GoldRepeatGroup> repeatGroups
    ) { }

    private record GoldLine(String lineId, String text, List<Integer> box) { }

    private record GoldEdge(String beforeRegionId, String afterRegionId) { }

    private record GoldRepeatGroup(String groupRegionId, List<GoldRepeatItem> items) { }

    private record GoldRepeatItem(String itemRegionId, List<String> memberRegionIds) { }

    private record InspectionRequestInput(
            String contractVersion,
            List<InspectionRegionInput> regions
    ) { }

    private record InspectionRegionInput(
            String baseViewId,
            List<Integer> boundingBox,
            String marginPreset,
            String resolutionPreset
    ) { }

    private record BranchInput(
            String branch,
            String planVersion,
            String planIdentity,
            String actionRequestIdentity,
            String actionPolicyIdentity,
            ResourceInput resources,
            List<ViewInput> views
    ) { }

    private record ResourceInput(
            int totalViews,
            int inspectedViews,
            long totalEncodedBytes,
            long totalPixels,
            long inspectedPixels,
            long additionalVisualTokens,
            long localTransformMillis
    ) { }

    private record ViewInput(
            int planOrdinal,
            String viewId,
            String sourceArtifactId,
            int sourceOrdinal,
            String kind,
            List<Integer> sourceBoundingBox,
            int width,
            int height,
            int sourceWidth,
            int sourceHeight,
            List<Integer> crop,
            String providerArtifactId,
            String mediaType,
            long encodedBytes,
            String encodedSha256,
            byte[] encodedImage
    ) { }
}
