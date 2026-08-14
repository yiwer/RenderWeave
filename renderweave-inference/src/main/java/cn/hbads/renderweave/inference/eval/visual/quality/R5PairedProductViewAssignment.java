package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord;
import cn.hbads.renderweave.inference.eval.visual.LayeredVisualCorpus;
import cn.hbads.renderweave.inference.live.BoundedVisualInspection;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable pre-result R5P seen-veto and sealed-confirmation assignment. */
public final class R5PairedProductViewAssignment {
    public static final String VERSION = "renderweave-r5p-paired-view-assignment/1.0";
    public static final String EVALUATION_VERSION =
            "renderweave-r5p-paired-view-evaluation/1.0";
    private static final String RESOURCE = "visual-eval/r5p/paired-view-assignment-v1.json";
    private static final String SPEC_IDENTITY =
            "spec-sha256:650ad1632347592d1fc34325983744c02563b43d8a565b9b1cd24e1a805a892a";
    private static final String AUTHORITY_IDENTITY =
            "renderweave-r5p-authority/1.0:"
                    + "05958659a5ffc302e92f6cc6cda8b1efd868e2ec4fa7f92b0d63f821f843441d";
    private static final String BASELINE_REVISION =
            "57be4d9b249c0aa06a1c0b32abc634c152a97234";
    private static final String OLD_ASSIGNMENT_IDENTITY =
            "renderweave-r5-product-transform-assignment/1.0:"
                    + "46c8e4c9c28b8628bac6532deeeb1a9ee311dda58b1a76f23a1b1d70abe7b540";
    private static final String CORPUS_IDENTITY =
            "renderweave-visual-stage-corpus/2.0:"
                    + "c596621eb680e7e10d42d2e1d1f926995cec9716cc6ef83a96a50ad53adc285c";
    private static final String ANNOTATION_SET_IDENTITY =
            "renderweave-layered-annotation-set/2.0:"
                    + "a6f7796d0433bb59779a3e1b99fa3c20b3e49148d24eb69dfe17682414fa746a";
    private static final String SELECTION_POLICY =
            "case-domain-difficulty-failure-slice-metadata-only-pre-result/1.0";
    private static final String SEEN_USAGE_POLICY =
            "veto-only-no-confirmation-no-holdout-no-ac021/1.0";
    private static final String CONFIRMATION_USAGE_POLICY =
            "sealed-confirmation-only-no-ac021/1.0";
    private static final List<String> CASE_IDS = List.of(
            "transit-board-v3", "restaurant-menu-v3", "hospital-schedule-v3",
            "transit-board-v5", "transit-board-v2", "invoice-lines-v3",
            "school-timetable-v4", "building-directory-v5");
    private static final List<String> SEEN_CASE_IDS = CASE_IDS.subList(0, 4);
    private static final List<String> CONFIRMATION_CASE_IDS = CASE_IDS.subList(4, 8);
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    private final String identity;
    private final String evaluationIdentity;
    private final List<CaseAssignment> cases;
    private final Thresholds thresholds;
    private final Identities identities;
    private final ExternalProviderUsage externalProviderUsage;
    private final int apiKeyReads;
    private final String terminalCode;

    private R5PairedProductViewAssignment(
            String identity,
            String evaluationIdentity,
            List<CaseAssignment> cases,
            Thresholds thresholds,
            Identities identities,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads,
            String terminalCode
    ) {
        this.identity = identity;
        this.evaluationIdentity = evaluationIdentity;
        this.cases = cases;
        this.thresholds = thresholds;
        this.identities = identities;
        this.externalProviderUsage = externalProviderUsage;
        this.apiKeyReads = apiKeyReads;
        this.terminalCode = terminalCode;
    }

    public static R5PairedProductViewAssignment load() {
        return load(R5PairedProductViewAssignment.class.getClassLoader());
    }

    static R5PairedProductViewAssignment load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        final byte[] bytes;
        final Document document;
        try (var input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalid("R5P_ASSIGNMENT_RESOURCE_MISSING");
            bytes = input.readAllBytes();
            document = JSON.readValue(bytes, Document.class);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalArgumentException known) throw known;
            throw invalid("R5P_ASSIGNMENT_INVALID");
        }
        validateHeader(document);
        var corpus = new LayeredVisualCorpus();
        var cases = validateCases(document.caseAssignments(), corpus, classLoader);
        var thresholds = validateThresholds(document.thresholds());
        var identities = validateIdentities(document.identities(), document.runtimeComponents());
        if (!new ExternalProviderUsage(0, 0, 0).equals(document.externalProviderUsage())) {
            throw invalid("R5P_ASSIGNMENT_PROVIDER_USAGE_NONZERO");
        }
        if (document.apiKeyReads() != 0) {
            throw invalid("R5P_ASSIGNMENT_API_KEY_READ_NONZERO");
        }
        if (!"R5P_ASSIGNMENT_FROZEN".equals(document.terminalCode())) {
            throw invalid("R5P_ASSIGNMENT_TERMINAL_DRIFT");
        }
        var identity = VERSION + ":" + sha256(bytes);
        var evaluationIdentity = evaluationIdentity(identity, cases, thresholds, identities);
        return new R5PairedProductViewAssignment(
                identity, evaluationIdentity, cases, thresholds, identities,
                document.externalProviderUsage(), document.apiKeyReads(), document.terminalCode());
    }

    public String identity() {
        return identity;
    }

    public String evaluationIdentity() {
        return evaluationIdentity;
    }

    public List<CaseAssignment> cases() {
        return cases;
    }

    public List<CaseAssignment> seenCases() {
        return cases.stream().filter(item -> item.cohort() == Cohort.SEEN_DIAGNOSTIC).toList();
    }

    public List<CaseAssignment> confirmationCases() {
        return cases.stream().filter(item -> item.cohort() == Cohort.SEALED_CONFIRMATION).toList();
    }

    public Thresholds thresholds() {
        return thresholds;
    }

    public Identities identities() {
        return identities;
    }

    public ExternalProviderUsage externalProviderUsage() {
        return externalProviderUsage;
    }

    public int apiKeyReads() {
        return apiKeyReads;
    }

    public String terminalCode() {
        return terminalCode;
    }

    private static void validateHeader(Document document) {
        if (document == null
                || !VERSION.equals(document.assignmentVersion())
                || !EVALUATION_VERSION.equals(document.evaluationVersion())
                || !SPEC_IDENTITY.equals(document.approvedSpecIdentity())
                || !AUTHORITY_IDENTITY.equals(document.authorityIdentity())
                || !BASELINE_REVISION.equals(document.baselineRevision())
                || !OLD_ASSIGNMENT_IDENTITY.equals(document.oldAssignmentIdentity())
                || !LayeredVisualCorpus.VERSION.equals(document.corpusVersion())
                || !CORPUS_IDENTITY.equals(document.corpusIdentity())
                || !ANNOTATION_SET_IDENTITY.equals(document.annotationSetIdentity())
                || !SELECTION_POLICY.equals(document.selectionPolicy())
                || !SEEN_USAGE_POLICY.equals(document.seenUsagePolicy())
                || !CONFIRMATION_USAGE_POLICY.equals(document.confirmationUsagePolicy())) {
            throw invalid("R5P_ASSIGNMENT_AUTHORITY_DRIFT");
        }
    }

    private static List<CaseAssignment> validateCases(
            List<CaseDocument> source,
            LayeredVisualCorpus corpus,
            ClassLoader classLoader
    ) {
        source = List.copyOf(Objects.requireNonNull(source, "caseAssignments"));
        if (!source.stream().map(CaseDocument::caseId).toList().equals(CASE_IDS)) {
            throw invalid("R5P_ASSIGNMENT_CASE_SET_DRIFT");
        }
        var oldAssignment = R5ProductTransformAssignment.load();
        if (!OLD_ASSIGNMENT_IDENTITY.equals(oldAssignment.identity())) {
            throw invalid("R5P_ASSIGNMENT_OLD_ASSIGNMENT_DRIFT");
        }
        var result = new ArrayList<CaseAssignment>();
        for (var index = 0; index < source.size(); index++) {
            var document = source.get(index);
            var expected = corpus.require(document.caseId());
            var expectedCohort = index < SEEN_CASE_IDS.size()
                    ? Cohort.SEEN_DIAGNOSTIC : Cohort.SEALED_CONFIRMATION;
            if (document.cohort() != expectedCohort) {
                throw invalid("R5P_ASSIGNMENT_CASE_SET_DRIFT");
            }
            if (document.sourcePartition() != expected.partition()) {
                throw invalid("R5P_ASSIGNMENT_PARTITION_DRIFT");
            }
            if (!expected.caseIdentity().equals(document.caseIdentity())
                    || !expected.renderIdentity().equals(document.renderIdentity())) {
                throw invalid("R5P_ASSIGNMENT_CASE_IDENTITY_DRIFT");
            }
            var expectedResource = "visual-eval/r5p/raw/" + document.caseId() + ".png";
            if (!expectedResource.equals(document.rawFixtureResource())
                    || !document.renderIdentity().equals(
                    "render-sha256:" + document.rawFixtureSha256())
                    || document.width() != expected.renderCase().width()
                    || document.height() != expected.renderCase().height()) {
                throw invalid("R5P_ASSIGNMENT_FIXTURE_DRIFT");
            }
            validateFixture(document, classLoader);
            var regions = validateRegions(document);
            result.add(new CaseAssignment(
                    document.caseId(), document.cohort(), document.sourcePartition(),
                    document.caseIdentity(), document.renderIdentity(),
                    document.rawFixtureResource(), document.rawFixtureSha256(),
                    document.width(), document.height(), regions));
        }
        validateIsolation(result, oldAssignment);
        return List.copyOf(result);
    }

    private static void validateFixture(CaseDocument document, ClassLoader classLoader) {
        try (var input = classLoader.getResourceAsStream(document.rawFixtureResource())) {
            if (input == null) throw invalid("R5P_ASSIGNMENT_FIXTURE_MISSING");
            var bytes = input.readAllBytes();
            if (!document.rawFixtureSha256().equals(sha256(bytes))) {
                throw invalid("R5P_ASSIGNMENT_FIXTURE_DRIFT");
            }
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() != document.width()
                    || image.getHeight() != document.height()) {
                throw invalid("R5P_ASSIGNMENT_FIXTURE_DRIFT");
            }
        } catch (IOException failure) {
            throw invalid("R5P_ASSIGNMENT_FIXTURE_DRIFT");
        }
    }

    private static List<BoundedVisualInspection.InspectionRegion> validateRegions(
            CaseDocument document
    ) {
        var values = List.copyOf(Objects.requireNonNull(document.regions(), "regions"));
        if (values.size() != 2) throw invalid("R5P_ASSIGNMENT_REGION_DRIFT");
        var result = values.stream().map(source -> {
            if (!"view-00-overview-00".equals(source.baseViewId())
                    || source.marginPreset()
                    != BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS
                    || source.resolutionPreset()
                    != BoundedVisualInspection.ResolutionPreset.INSPECT_LONG_EDGE_2400) {
                throw invalid("R5P_ASSIGNMENT_REGION_DRIFT");
            }
            var box = List.copyOf(Objects.requireNonNull(source.boundingBox(), "boundingBox"));
            if (box.size() != 4 || box.stream().anyMatch(Objects::isNull)) {
                throw invalid("R5P_ASSIGNMENT_REGION_DRIFT");
            }
            return new BoundedVisualInspection.InspectionRegion(
                    source.baseViewId(),
                    new CandidateBoundingBox(box.get(0), box.get(1), box.get(2), box.get(3)),
                    source.marginPreset(), source.resolutionPreset());
        }).toList();
        if (new HashSet<>(result).size() != result.size()
                || !expectedRegionBoxes(document.caseId()).equals(result.stream()
                .map(item -> List.of(
                        item.boundingBox().left(), item.boundingBox().top(),
                        item.boundingBox().right(), item.boundingBox().bottom()))
                .toList())) {
            throw invalid("R5P_ASSIGNMENT_REGION_DRIFT");
        }
        return List.copyOf(result);
    }

    private static List<List<Integer>> expectedRegionBoxes(String caseId) {
        var transit = caseId.startsWith("transit-board-");
        return transit
                ? List.of(List.of(200, 200, 9800, 2900), List.of(200, 2900, 9800, 9800))
                : List.of(List.of(200, 200, 9800, 2500), List.of(200, 2500, 9800, 9800));
    }

    private static void validateIsolation(
            List<CaseAssignment> cases,
            R5ProductTransformAssignment oldAssignment
    ) {
        var seen = cases.stream().filter(item -> item.cohort() == Cohort.SEEN_DIAGNOSTIC).toList();
        var confirmation = cases.stream()
                .filter(item -> item.cohort() == Cohort.SEALED_CONFIRMATION).toList();
        if (!seen.stream().map(CaseAssignment::caseId).toList().equals(SEEN_CASE_IDS)
                || !confirmation.stream().map(CaseAssignment::caseId).toList()
                .equals(CONFIRMATION_CASE_IDS)
                || confirmation.stream().filter(item ->
                item.sourcePartition() == LayeredEvaluationRecord.Partition.DEV).count() != 3
                || confirmation.stream().filter(item ->
                item.sourcePartition() == LayeredEvaluationRecord.Partition.HOLDOUT).count() != 1
                || confirmation.stream().anyMatch(item -> SEEN_CASE_IDS.contains(item.caseId()))
                || confirmation.stream().anyMatch(item -> "transit-board-v5".equals(item.caseId()))) {
            throw invalid("R5P_ASSIGNMENT_COHORT_OVERLAP");
        }
        for (var index = 0; index < seen.size(); index++) {
            var inherited = oldAssignment.cases().get(index);
            var current = seen.get(index);
            if (!current.caseId().equals(inherited.caseId())
                    || current.sourcePartition() != inherited.partition()
                    || current.regions().size() != inherited.regions().size()) {
                throw invalid("R5P_ASSIGNMENT_SEEN_INHERITANCE_DRIFT");
            }
            for (var regionIndex = 0; regionIndex < current.regions().size(); regionIndex++) {
                var left = current.regions().get(regionIndex);
                var right = inherited.regions().get(regionIndex);
                if (!left.baseViewId().equals(right.baseViewId())
                        || !left.boundingBox().equals(right.boundingBox())
                        || !left.marginPreset().name().equals(right.marginPreset())
                        || !left.resolutionPreset().name().equals(right.resolutionPreset())) {
                    throw invalid("R5P_ASSIGNMENT_SEEN_INHERITANCE_DRIFT");
                }
            }
        }
        if (new HashSet<>(cases.stream().map(CaseAssignment::caseIdentity).toList()).size()
                != cases.size()
                || new HashSet<>(cases.stream().map(CaseAssignment::rawFixtureSha256).toList()).size()
                != cases.size()) {
            throw invalid("R5P_ASSIGNMENT_COHORT_OVERLAP");
        }
    }

    private static Thresholds validateThresholds(Thresholds value) {
        if (value == null
                || !"MATCHED_LINE_INCREASE_OR_CHARACTER_ERROR_REDUCTION"
                .equals(value.perCaseTargetImprovementRule())
                || value.maximumPerCaseHallucinationIncrease() != 0
                || value.minimumConfirmationLineRecallGainBps() != 500
                || value.minimumConfirmationCharacterErrorReduction() != 1
                || value.maximumConfirmationOrderRegressionBps() != 100
                || value.maximumConfirmationRepeatRegressionBps() != 100
                || value.coalescingIntersectionOverSmallerAreaBps() != 5_000) {
            throw invalid("R5P_ASSIGNMENT_THRESHOLD_DRIFT");
        }
        return value;
    }

    private static Identities validateIdentities(
            Identities value, RuntimeComponents runtime
    ) {
        var policy = RapidOcrBaselineContract.policy(
                RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
        if (value == null
                || !"renderweave-input-normalizer-source-sha256/1.0:"
                .concat("71a4f90ee7298fb3ef3a3550e34880ed3216213fc69f31b80f9b0e496570654a")
                .equals(value.normalizerIdentity())
                || !"renderweave-visual-view-plan/1.0".equals(value.staticPlannerVersion())
                || !"e10d2955c9b463ee3996eac333abf4c8f32c2a82faf38e286796e56b7d52fa0c"
                .equals(value.staticPlannerSourceSha256())
                || !BoundedVisualInspection.VERSION.equals(value.actionModuleVersion())
                || !"99df52f72e1b5ff06c064bf96281149cf56e631f6f285bcbb882eb1e09723e4f"
                .equals(value.actionModuleSourceSha256())
                || !BoundedVisualInspection.PLAN_VERSION.equals(value.successorPlanVersion())
                || !BoundedVisualInspection.AdaptiveInspectionPolicy.initial().identity()
                .equals(value.actionPolicyIdentity())
                || !"renderweave-r5-product-raster-transform/1.0"
                .equals(value.transformVersion())
                || !"3d1b0fd84a3d0600227f20c415fd4c1f333a0a26b8299812f5c412626058e552"
                .equals(value.transformSourceSha256())
                || !("AcquisitionPolicy/1.0:" + policy.identity())
                .equals(value.acquisitionPolicyIdentity())
                || !policy.capabilityIdentity().equals(value.capabilityIdentity())
                || !policy.adapterIdentity().equals(value.adapterIdentity())
                || !"d715b44731171e5d29f4e405ef2320c5e6e0ea13c8129d53da06715d13875b84"
                .equals(value.adapterSourceSha256())
                || !"renderweave-r5p-source-projection/1.0".equals(value.projectionIdentity())
                || !"renderweave-r5p-observation-coalescing/1.0"
                .equals(value.coalescingIdentity())
                || !"unicode-nfc-whitespace-collapse-exact/1.0"
                .equals(value.coalescingTextRule())
                || !"intersection-over-smaller-area-at-least-5000-bps/1.0"
                .equals(value.coalescingGeometryRule())
                || !"renderweave-rapidocr-shadow-case-evaluator/1.0"
                .equals(value.caseEvaluatorIdentity())
                || !"renderweave-r5p-paired-product-view-evaluator/1.0"
                .equals(value.evaluatorIdentity())
                || !"two-isolated-complete-paired-runs/1.0"
                .equals(value.runProtocolIdentity())) {
            throw invalid("R5P_ASSIGNMENT_IDENTITY_DRIFT");
        }
        validateRuntime(runtime, value.runtimeIdentity());
        return value;
    }

    private static void validateRuntime(RuntimeComponents value, String identity) {
        if (value == null
                || !"Windows 11".equals(value.os())
                || !"amd64".equals(value.arch())
                || !"Oracle Corporation".equals(value.javaVendor())
                || !"21.0.11+9-LTS-211".equals(value.javaRuntime())
                || !"3.12.13".equals(value.python())
                || !"3.9.2".equals(value.rapidocr())
                || !"2026.0.0".equals(value.openvino())
                || !"java2d-bicubic+java-imageio-png/1.0".equals(value.imageRuntime())
                || !"d715b44731171e5d29f4e405ef2320c5e6e0ea13c8129d53da06715d13875b84"
                .equals(value.adapterSha256())
                || !"c05805399d7d10b1d1e32f2f52faf2a9fe6617db50f6b96221cb3b7be47e58a5"
                .equals(value.modelSha256())) {
            throw invalid("R5P_ASSIGNMENT_RUNTIME_DRIFT");
        }
        var frames = List.of(
                "os=" + value.os(), "arch=" + value.arch(),
                "java-vendor=" + value.javaVendor(), "java-runtime=" + value.javaRuntime(),
                "python=" + value.python(), "rapidocr=" + value.rapidocr(),
                "openvino=" + value.openvino(), "image-runtime=" + value.imageRuntime(),
                "adapter-sha256=" + value.adapterSha256(),
                "model-sha256=" + value.modelSha256());
        var expected = "renderweave-r5p-runtime/1.0:" + framedSha256(frames);
        if (!expected.equals(identity)) throw invalid("R5P_ASSIGNMENT_RUNTIME_DRIFT");
    }

    private static String evaluationIdentity(
            String assignmentIdentity,
            List<CaseAssignment> cases,
            Thresholds thresholds,
            Identities identities
    ) {
        var frames = new ArrayList<String>();
        frames.add("assignment=" + assignmentIdentity);
        frames.add("authority=" + AUTHORITY_IDENTITY);
        frames.add("baseline=" + BASELINE_REVISION);
        frames.add("policy=" + identities.actionPolicyIdentity());
        frames.add("acquisition=" + identities.acquisitionPolicyIdentity());
        frames.add("runtime=" + identities.runtimeIdentity());
        frames.add("evaluator=" + identities.evaluatorIdentity());
        frames.add("thresholds=" + thresholds);
        cases.forEach(item -> frames.add("case=" + item.cohort() + ":" + item.caseIdentity()
                + ":" + item.rawFixtureSha256()));
        return EVALUATION_VERSION + ":" + framedSha256(frames);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
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

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public enum Cohort { SEEN_DIAGNOSTIC, SEALED_CONFIRMATION }

    public record CaseAssignment(
            String caseId,
            Cohort cohort,
            LayeredEvaluationRecord.Partition sourcePartition,
            String caseIdentity,
            String renderIdentity,
            String rawFixtureResource,
            String rawFixtureSha256,
            int width,
            int height,
            List<BoundedVisualInspection.InspectionRegion> regions
    ) {
        public CaseAssignment {
            Objects.requireNonNull(caseId, "caseId");
            Objects.requireNonNull(cohort, "cohort");
            Objects.requireNonNull(sourcePartition, "sourcePartition");
            Objects.requireNonNull(caseIdentity, "caseIdentity");
            Objects.requireNonNull(renderIdentity, "renderIdentity");
            Objects.requireNonNull(rawFixtureResource, "rawFixtureResource");
            Objects.requireNonNull(rawFixtureSha256, "rawFixtureSha256");
            regions = List.copyOf(regions);
        }

        public boolean seenVeto() {
            return cohort == Cohort.SEEN_DIAGNOSTIC;
        }

        public boolean contributesToConfirmation() {
            return cohort == Cohort.SEALED_CONFIRMATION;
        }

        public boolean mayClaimAc021() {
            return false;
        }

        @Override
        public String toString() {
            return "CaseAssignment[caseId=" + caseId + ", cohort=" + cohort
                    + ", sourcePartition=" + sourcePartition + ", caseIdentity=" + caseIdentity
                    + ", rawFixtureSha256=" + rawFixtureSha256 + ", dimensions="
                    + width + "x" + height + ", regions=<redacted:" + regions.size() + ">]";
        }
    }

    public record Thresholds(
            String perCaseTargetImprovementRule,
            int maximumPerCaseHallucinationIncrease,
            int minimumConfirmationLineRecallGainBps,
            int minimumConfirmationCharacterErrorReduction,
            int maximumConfirmationOrderRegressionBps,
            int maximumConfirmationRepeatRegressionBps,
            int coalescingIntersectionOverSmallerAreaBps
    ) { }

    public record Identities(
            String normalizerIdentity,
            String staticPlannerVersion,
            String staticPlannerSourceSha256,
            String actionModuleVersion,
            String actionModuleSourceSha256,
            String successorPlanVersion,
            String actionPolicyIdentity,
            String transformVersion,
            String transformSourceSha256,
            String acquisitionPolicyIdentity,
            String capabilityIdentity,
            String adapterIdentity,
            String adapterSourceSha256,
            String projectionIdentity,
            String coalescingIdentity,
            String coalescingTextRule,
            String coalescingGeometryRule,
            String caseEvaluatorIdentity,
            String evaluatorIdentity,
            String runProtocolIdentity,
            String runtimeIdentity
    ) { }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) { }

    private record Document(
            String assignmentVersion,
            String evaluationVersion,
            String approvedSpecIdentity,
            String authorityIdentity,
            String baselineRevision,
            String oldAssignmentIdentity,
            String corpusVersion,
            String corpusIdentity,
            String annotationSetIdentity,
            String selectionPolicy,
            String seenUsagePolicy,
            String confirmationUsagePolicy,
            List<CaseDocument> caseAssignments,
            Thresholds thresholds,
            Identities identities,
            RuntimeComponents runtimeComponents,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads,
            String terminalCode
    ) { }

    private record CaseDocument(
            String caseId,
            Cohort cohort,
            LayeredEvaluationRecord.Partition sourcePartition,
            String caseIdentity,
            String renderIdentity,
            String rawFixtureResource,
            String rawFixtureSha256,
            int width,
            int height,
            List<RegionDocument> regions
    ) { }

    private record RegionDocument(
            String baseViewId,
            List<Integer> boundingBox,
            BoundedVisualInspection.MarginPreset marginPreset,
            BoundedVisualInspection.ResolutionPreset resolutionPreset
    ) { }

    private record RuntimeComponents(
            String os,
            String arch,
            String javaVendor,
            String javaRuntime,
            String python,
            String rapidocr,
            String openvino,
            String imageRuntime,
            String adapterSha256,
            String modelSha256
    ) { }
}
