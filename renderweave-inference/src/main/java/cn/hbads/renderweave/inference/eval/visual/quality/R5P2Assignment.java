package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord;
import cn.hbads.renderweave.inference.live.BoundedVisualInspection;
import cn.hbads.renderweave.inference.vision.RapidOcrBaselineContract;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, metadata-selected, pre-result R5P2 assignment and access protocol. */
public final class R5P2Assignment {
    public static final String CONTRACT_VERSION = "FrozenR5P2Assignment/1.0";
    public static final String IDENTITY_VERSION =
            "renderweave-r5p2-frozen-assignment/1.0";
    public static final String NORMALIZATION_PROFILE_ID = "r5p2-offline-evaluation";
    public static final String FIXTURE_SET_VERSION =
            "renderweave-r5p2-repository-raster-fixture-set/1.0";
    private static final String EVALUATION_VERSION =
            "renderweave-r5p2-paired-product-view-evaluation/1.0";
    private static final String RESOURCE = "visual-eval/r5p2/assignment-v1.json";
    private static final String IDENTITY_LOCK_RESOURCE = "visual-eval/v2/identity-lock.json";
    private static final String SPEC_IDENTITY =
            "spec-sha256:e33269e1faa04f21239a0e79d4346fc90439f142b26111b3764164f53ba7d902";
    private static final String AUTHORITY_IDENTITY =
            "renderweave-r5p2-authority/1.0:"
                    + "274585e94941248dd2bea55026c06428f2945aea7cc48ce2b269c21f5f3ccc07";
    private static final String BASELINE_REVISION =
            "4b756c52cbc2fd389d8ca34f4c4a65b1bc9615db";
    private static final String CORPUS_VERSION = "renderweave-visual-stage-corpus/2.0";
    private static final String CORPUS_IDENTITY = CORPUS_VERSION + ":"
            + "c596621eb680e7e10d42d2e1d1f926995cec9716cc6ef83a96a50ad53adc285c";
    private static final String CORPUS_LOCK_SHA256 =
            "cf54fd985e89a024fdc0742a737c21442c49718fdf58b0bb05b87e2cffd2247d";
    private static final String SELECTION_POLICY =
            "renderweave-r5p2-confirmation-selection/1.0";
    private static final List<String> SELECTION_FIELDS = List.of(
            "caseId", "partition", "difficulty", "failureSlices", "caseIdentity");
    private static final List<String> PRIOR_RESOURCES = List.of(
            "visual-eval/r5/product-transform-assignment-v1.json",
            "visual-eval/r5p/paired-view-assignment-v1.json");
    private static final List<String> PRIOR_SHA256 = List.of(
            "46c8e4c9c28b8628bac6532deeeb1a9ee311dda58b1a76f23a1b1d70abe7b540",
            "39266e24b85e0189577573e6e4e56905d41a43f7e0f81a9514fbdbcac954c3e8");
    private static final String HISTORICAL_USAGE =
            "diagnostic-veto-only-no-confirmation-no-holdout-claim/1.0";
    private static final String CONFIRMATION_USAGE =
            "sealed-fresh-confirmation-no-holdout-acceptance-claim/1.0";
    private static final List<String> DIAGNOSTIC_CASE_IDS = List.of(
            "transit-board-v3", "restaurant-menu-v3", "hospital-schedule-v3",
            "transit-board-v5", "transit-board-v2", "invoice-lines-v3",
            "school-timetable-v4", "building-directory-v5");
    private static final List<String> CONFIRMATION_CASE_IDS = List.of(
            "weather-forecast-v3", "warehouse-inventory-v2",
            "event-agenda-v4", "product-catalog-v5");
    private static final List<String> ALL_CASE_IDS = java.util.stream.Stream.concat(
            DIAGNOSTIC_CASE_IDS.stream(), CONFIRMATION_CASE_IDS.stream()).toList();
    private static final Set<String> ALLOWED_FAMILIES = Set.of(
            "analytics-dashboard", "event-agenda", "product-catalog",
            "warehouse-inventory", "weather-forecast");
    private static final List<Stratum> STRATA = List.of(
            new Stratum("DEV", "DENSE_TEXT"),
            new Stratum("DEV", "MULTI_COLUMN"),
            new Stratum("DEV", "LOW_CONTRAST"),
            new Stratum("HOLDOUT", "NOISY"));
    private static final Set<String> FORBIDDEN_SELECTION_FIELDS = Set.of(
            "ocr", "ocrText", "pairedMetric", "pairedMetrics", "gold", "goldText",
            "candidate", "candidateOutput", "model", "modelResult", "modelOutput");
    private static final Pattern FAMILY_SUFFIX = Pattern.compile("-v[0-9]+$");
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
    private final String fixtureSetIdentity;
    private final String evaluationIdentity;
    private final List<CaseAssignment> cases;
    private final Thresholds thresholds;
    private final Identities identities;
    private final RuntimeComponents runtimeComponents;
    private final AccessState accessState;
    private final ExternalProviderUsage externalProviderUsage;
    private final int apiKeyReads;
    private final String terminalCode;

    private R5P2Assignment(
            String identity,
            String fixtureSetIdentity,
            String evaluationIdentity,
            List<CaseAssignment> cases,
            Thresholds thresholds,
            Identities identities,
            RuntimeComponents runtimeComponents,
            AccessState accessState,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads,
            String terminalCode
    ) {
        this.identity = identity;
        this.fixtureSetIdentity = fixtureSetIdentity;
        this.evaluationIdentity = evaluationIdentity;
        this.cases = cases;
        this.thresholds = thresholds;
        this.identities = identities;
        this.runtimeComponents = runtimeComponents;
        this.accessState = accessState;
        this.externalProviderUsage = externalProviderUsage;
        this.apiKeyReads = apiKeyReads;
        this.terminalCode = terminalCode;
    }

    public static R5P2Assignment load() {
        return load(R5P2Assignment.class.getClassLoader());
    }

    static R5P2Assignment load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        var manifestBytes = resource(classLoader, RESOURCE, "R5P2_ASSIGNMENT_RESOURCE_MISSING");
        final Document document;
        try {
            document = JSON.readValue(manifestBytes, Document.class);
        } catch (RuntimeException failure) {
            throw invalid("R5P2_ASSIGNMENT_INVALID");
        }
        validateHeader(document);
        var identity = IDENTITY_VERSION + ":" + sha256(manifestBytes);
        var authority = R5P2Authority.load();
        if (!AUTHORITY_IDENTITY.equals(authority.authorityIdentity())) {
            throw invalid("R5P2_ASSIGNMENT_AUTHORITY_DRIFT");
        }
        authority.requireFreshR5P2Identity(identity);

        var identityLock = resource(classLoader, IDENTITY_LOCK_RESOURCE,
                "R5P2_ASSIGNMENT_CORPUS_LOCK_MISSING");
        if (!CORPUS_LOCK_SHA256.equals(sha256(identityLock))) {
            throw invalid("R5P2_ASSIGNMENT_CORPUS_LOCK_DRIFT");
        }
        var metadata = readMetadata(identityLock);
        var selected = select(metadata);
        validatePriorPairedAbsence(classLoader, document, selected);
        var cases = validateCases(document.caseAssignments(), metadata, selected, classLoader);
        var thresholds = validateThresholds(document.thresholds());
        var identities = validateIdentities(document.identities(), document.runtimeComponents());
        var access = validateAccessState(document.accessState());
        if (document.externalProviderUsage() == null
                || !document.externalProviderUsage().zeroUsage()) {
            throw invalid("R5P2_ASSIGNMENT_PROVIDER_USAGE_NONZERO");
        }
        if (document.apiKeyReads() != 0) {
            throw invalid("R5P2_ASSIGNMENT_API_KEY_READ_NONZERO");
        }
        if (!"R5P2_ASSIGNMENT_FROZEN".equals(document.terminalCode())) {
            throw invalid("R5P2_ASSIGNMENT_TERMINAL_DRIFT");
        }
        var fixtureSetIdentity = fixtureSetIdentity(cases);
        var evaluationIdentity = evaluationIdentity(
                identity, fixtureSetIdentity, cases, thresholds, identities);
        return new R5P2Assignment(
                identity, fixtureSetIdentity, evaluationIdentity, cases, thresholds,
                identities, document.runtimeComponents(), access,
                document.externalProviderUsage(), document.apiKeyReads(), document.terminalCode());
    }

    public String contractVersion() { return CONTRACT_VERSION; }

    public String identity() { return identity; }

    public String fixtureSetIdentity() { return fixtureSetIdentity; }

    public String evaluationIdentity() { return evaluationIdentity; }

    public String thresholdIdentity() { return thresholdIdentity(thresholds); }

    public List<CaseAssignment> cases() { return cases; }

    public List<CaseAssignment> diagnosticCases() {
        return cases.stream().filter(item -> item.cohort() == Cohort.HISTORICAL_DIAGNOSTIC).toList();
    }

    public List<CaseAssignment> confirmationCases() {
        return cases.stream().filter(item -> item.cohort() == Cohort.SEALED_CONFIRMATION).toList();
    }

    public Thresholds thresholds() { return thresholds; }

    public Identities identities() { return identities; }

    public RuntimeComponents runtimeComponents() { return runtimeComponents; }

    public AccessState accessState() { return accessState; }

    public ExternalProviderUsage externalProviderUsage() { return externalProviderUsage; }

    public int apiKeyReads() { return apiKeyReads; }

    public String terminalCode() { return terminalCode; }

    public HoldoutAccessAudit newHoldoutAccessAudit() {
        return HoldoutAccessAudit.frozen(identity, CONFIRMATION_CASE_IDS,
                "product-catalog-v5");
    }

    static List<Selection> recomputeSelection(byte[] identityLock) {
        return select(readMetadata(Objects.requireNonNull(identityLock, "identityLock")));
    }

    private static void validateHeader(Document value) {
        if (value == null
                || !CONTRACT_VERSION.equals(value.contractVersion())
                || !IDENTITY_VERSION.equals(value.identityVersion())
                || !EVALUATION_VERSION.equals(value.evaluationVersion())
                || !SPEC_IDENTITY.equals(value.approvedSpecIdentity())
                || !AUTHORITY_IDENTITY.equals(value.authorityIdentity())
                || !BASELINE_REVISION.equals(value.baselineRevision())
                || !CORPUS_VERSION.equals(value.corpusVersion())
                || !CORPUS_IDENTITY.equals(value.corpusIdentity())
                || !IDENTITY_LOCK_RESOURCE.equals(value.corpusIdentityLockResource())
                || !CORPUS_LOCK_SHA256.equals(value.corpusIdentityLockSha256())
                || !SELECTION_POLICY.equals(value.selectionPolicyIdentity())
                || !SELECTION_FIELDS.equals(value.selectionAllowedFields())
                || !PRIOR_RESOURCES.equals(value.priorPairedAssignmentResources())
                || !PRIOR_SHA256.equals(value.priorPairedAssignmentSha256())
                || !HISTORICAL_USAGE.equals(value.historicalUsagePolicy())
                || !CONFIRMATION_USAGE.equals(value.confirmationUsagePolicy())
                || !NORMALIZATION_PROFILE_ID.equals(value.normalizationProfileId())) {
            throw invalid("R5P2_ASSIGNMENT_AUTHORITY_DRIFT");
        }
    }

    private static List<SelectionMetadata> readMetadata(byte[] identityLock) {
        final JsonNode root;
        try {
            root = JSON.readTree(identityLock);
        } catch (RuntimeException failure) {
            throw invalid("R5P2_SELECTION_METADATA_INVALID");
        }
        if (root == null || !root.isObject()
                || !CORPUS_VERSION.equals(text(root, "corpusVersion"))
                || !CORPUS_IDENTITY.equals(text(root, "corpusIdentity"))) {
            throw invalid("R5P2_SELECTION_METADATA_INVALID");
        }
        var casesNode = root.get("cases");
        if (casesNode == null || !casesNode.isArray() || casesNode.isEmpty()) {
            throw invalid("R5P2_SELECTION_METADATA_INVALID");
        }
        var result = new ArrayList<SelectionMetadata>();
        var caseIds = new HashSet<String>();
        for (var node : casesNode) {
            if (!node.isObject()) throw invalid("R5P2_SELECTION_METADATA_INVALID");
            for (var property : node.properties()) {
                if (FORBIDDEN_SELECTION_FIELDS.contains(property.getKey())) {
                    throw invalid("R5P2_SELECTION_FORBIDDEN_METADATA");
                }
            }
            var caseId = text(node, "caseId");
            var partition = text(node, "partition");
            var difficulty = text(node, "difficulty");
            var caseIdentity = text(node, "caseIdentity");
            var slicesNode = node.get("failureSlices");
            if (!caseIds.add(caseId) || !caseId.matches("[a-z][a-z0-9-]{0,127}")
                    || !partition.matches("DEV|HOLDOUT")
                    || !difficulty.matches("BASELINE|MULTI_COLUMN|DENSE_TEXT|LOW_CONTRAST|NOISY")
                    || !caseIdentity.matches("renderweave-layered-case/2\\.0:[0-9a-f]{64}")
                    || slicesNode == null || !slicesNode.isArray()) {
                throw invalid("R5P2_SELECTION_METADATA_INVALID");
            }
            var slices = new ArrayList<String>();
            for (var slice : slicesNode) {
                if (!slice.isTextual() || !slices.add(slice.textValue())) {
                    throw invalid("R5P2_SELECTION_METADATA_INVALID");
                }
            }
            result.add(new SelectionMetadata(
                    caseId, partition, difficulty, List.copyOf(slices), caseIdentity,
                    family(caseId)));
        }
        return List.copyOf(result);
    }

    private static List<Selection> select(List<SelectionMetadata> metadata) {
        var selected = new ArrayList<Selection>();
        var usedFamilies = new HashSet<String>();
        for (var stratum : STRATA) {
            var ranked = metadata.stream()
                    .filter(item -> stratum.partition().equals(item.partition())
                            && stratum.difficulty().equals(item.difficulty())
                            && ALLOWED_FAMILIES.contains(item.family())
                            && item.failureSlices().contains("REPEATED_LIST"))
                    .map(item -> new Selection(
                            item.caseId(), item.partition(), item.difficulty(), item.family(),
                            item.caseIdentity(), selectionRank(
                            item.partition(), item.difficulty(), item.caseIdentity())))
                    .sorted(Comparator.comparing(Selection::rankSha256)
                            .thenComparing(Selection::caseId))
                    .toList();
            var chosen = ranked.stream().filter(item -> !usedFamilies.contains(item.family()))
                    .findFirst().orElseThrow(() -> invalid("R5P2_SELECTION_STRATUM_EMPTY"));
            usedFamilies.add(chosen.family());
            selected.add(chosen);
        }
        return List.copyOf(selected);
    }

    private static void validatePriorPairedAbsence(
            ClassLoader classLoader,
            Document document,
            List<Selection> selected
    ) {
        var selectedIds = selected.stream().map(Selection::caseId).collect(java.util.stream.Collectors.toSet());
        var selectedFamilies = selected.stream().map(Selection::family)
                .collect(java.util.stream.Collectors.toSet());
        for (var index = 0; index < PRIOR_RESOURCES.size(); index++) {
            var bytes = resource(classLoader, PRIOR_RESOURCES.get(index),
                    "R5P2_ASSIGNMENT_PRIOR_PAIRED_RESOURCE_MISSING");
            var priorIds = caseIds(bytes);
            if (priorIds.stream().anyMatch(selectedIds::contains)
                    || priorIds.stream().map(R5P2Assignment::family)
                    .anyMatch(selectedFamilies::contains)) {
                throw invalid("R5P2_ASSIGNMENT_PRIOR_PAIRED_OVERLAP");
            }
            if (!PRIOR_SHA256.get(index).equals(sha256(bytes))) {
                throw invalid("R5P2_ASSIGNMENT_PRIOR_PAIRED_DRIFT");
            }
        }
        if (!document.priorPairedAssignmentResources().equals(PRIOR_RESOURCES)
                || !document.priorPairedAssignmentSha256().equals(PRIOR_SHA256)) {
            throw invalid("R5P2_ASSIGNMENT_PRIOR_PAIRED_DRIFT");
        }
    }

    private static Set<String> caseIds(byte[] bytes) {
        try {
            var root = JSON.readTree(bytes);
            var values = root.get("caseAssignments");
            if (values == null || !values.isArray()) {
                throw invalid("R5P2_ASSIGNMENT_PRIOR_PAIRED_DRIFT");
            }
            var result = new LinkedHashSet<String>();
            for (var value : values) {
                var caseId = text(value, "caseId");
                if (!result.add(caseId)) throw invalid("R5P2_ASSIGNMENT_PRIOR_PAIRED_DRIFT");
            }
            return Set.copyOf(result);
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException known) throw known;
            throw invalid("R5P2_ASSIGNMENT_PRIOR_PAIRED_DRIFT");
        }
    }

    private static List<CaseAssignment> validateCases(
            List<CaseDocument> source,
            List<SelectionMetadata> metadata,
            List<Selection> selected,
            ClassLoader classLoader
    ) {
        source = List.copyOf(Objects.requireNonNull(source, "caseAssignments"));
        if (!source.stream().map(CaseDocument::caseId).toList().equals(ALL_CASE_IDS)) {
            throw invalid("R5P2_ASSIGNMENT_CASE_SET_DRIFT");
        }
        if (!selected.stream().map(Selection::caseId).toList().equals(CONFIRMATION_CASE_IDS)) {
            throw invalid("R5P2_ASSIGNMENT_SELECTION_DRIFT");
        }
        var metadataById = new HashMap<String, SelectionMetadata>();
        metadata.forEach(item -> metadataById.put(item.caseId(), item));
        var selectionById = new HashMap<String, Selection>();
        selected.forEach(item -> selectionById.put(item.caseId(), item));
        var result = new ArrayList<CaseAssignment>();
        for (var index = 0; index < source.size(); index++) {
            var document = source.get(index);
            var expectedMetadata = metadataById.get(document.caseId());
            var expectedCohort = index < DIAGNOSTIC_CASE_IDS.size()
                    ? Cohort.HISTORICAL_DIAGNOSTIC : Cohort.SEALED_CONFIRMATION;
            if (expectedMetadata == null
                    || document.cohort() != expectedCohort
                    || !expectedMetadata.partition().equals(document.partition().name())
                    || !expectedMetadata.difficulty().equals(document.difficulty().name())
                    || !expectedMetadata.failureSlices().equals(document.failureSlices().stream()
                    .map(Enum::name).toList())
                    || !expectedMetadata.family().equals(document.family())
                    || !expectedMetadata.caseIdentity().equals(document.caseIdentity())) {
                throw invalid("R5P2_ASSIGNMENT_METADATA_DRIFT");
            }
            var selection = selectionById.get(document.caseId());
            if (expectedCohort == Cohort.HISTORICAL_DIAGNOSTIC) {
                if (document.selectionRankSha256() != null
                        || !"R5P_FROZEN_REUSE".equals(document.fixtureOrigin())
                        || !document.rawFixtureResource().equals(
                        "visual-eval/r5p/raw/" + document.caseId() + ".png")) {
                    throw invalid("R5P2_ASSIGNMENT_DIAGNOSTIC_DRIFT");
                }
            } else if (selection == null
                    || !selection.rankSha256().equals(document.selectionRankSha256())
                    || !"R5P2_FRESH_ONE_SHOT".equals(document.fixtureOrigin())
                    || !document.rawFixtureResource().equals(
                    "visual-eval/r5p2/raw/" + document.caseId() + ".png")) {
                throw invalid("R5P2_ASSIGNMENT_SELECTION_DRIFT");
            }
            var bytes = validateFixture(document, classLoader);
            var regions = validateRegions(document);
            result.add(new CaseAssignment(
                    document.caseId(), document.cohort(), document.partition(),
                    document.difficulty(), document.failureSlices(), document.family(),
                    document.caseIdentity(), document.selectionRankSha256(),
                    document.renderIdentity(), document.rawFixtureResource(),
                    document.rawFixtureSha256(), document.fixtureOrigin(),
                    document.width(), document.height(), document.normalizationSourceReference(),
                    document.normalizationFingerprint(), regions, bytes.length));
        }
        if (new HashSet<>(result.stream().map(CaseAssignment::caseIdentity).toList()).size()
                != result.size()
                || new HashSet<>(result.stream().map(CaseAssignment::rawFixtureSha256).toList()).size()
                != result.size()
                || new HashSet<>(result.subList(8, 12).stream()
                .map(CaseAssignment::family).toList()).size() != 4) {
            throw invalid("R5P2_ASSIGNMENT_COHORT_OVERLAP");
        }
        return List.copyOf(result);
    }

    private static byte[] validateFixture(CaseDocument document, ClassLoader classLoader) {
        var bytes = resource(classLoader, document.rawFixtureResource(),
                "R5P2_ASSIGNMENT_FIXTURE_MISSING");
        if (!document.rawFixtureSha256().matches("[0-9a-f]{64}")
                || !document.rawFixtureSha256().equals(sha256(bytes))
                || !document.renderIdentity().equals("render-sha256:" + document.rawFixtureSha256())
                || !document.normalizationSourceReference().equals(
                "r5p2-raw-fixture:" + document.caseId() + ":" + document.rawFixtureSha256())
                || !document.normalizationFingerprint().equals(normalizationFingerprint(
                document.normalizationSourceReference(), bytes))) {
            throw invalid("R5P2_ASSIGNMENT_FIXTURE_DRIFT");
        }
        try {
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() != document.width()
                    || image.getHeight() != document.height()) {
                throw invalid("R5P2_ASSIGNMENT_FIXTURE_DRIFT");
            }
        } catch (IOException failure) {
            throw invalid("R5P2_ASSIGNMENT_FIXTURE_DRIFT");
        }
        return bytes;
    }

    private static List<BoundedVisualInspection.InspectionRegion> validateRegions(
            CaseDocument document
    ) {
        var source = List.copyOf(Objects.requireNonNull(document.regions(), "regions"));
        if (source.size() != 2) throw invalid("R5P2_ASSIGNMENT_REGION_DRIFT");
        var result = source.stream().map(value -> {
            var box = List.copyOf(Objects.requireNonNull(value.boundingBox(), "boundingBox"));
            if (!"view-00-overview-00".equals(value.baseViewId())
                    || value.marginPreset() != BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS
                    || value.resolutionPreset()
                    != BoundedVisualInspection.ResolutionPreset.INSPECT_LONG_EDGE_2400
                    || box.size() != 4 || box.stream().anyMatch(Objects::isNull)) {
                throw invalid("R5P2_ASSIGNMENT_REGION_DRIFT");
            }
            return new BoundedVisualInspection.InspectionRegion(
                    value.baseViewId(), new CandidateBoundingBox(
                    box.get(0), box.get(1), box.get(2), box.get(3)),
                    value.marginPreset(), value.resolutionPreset());
        }).toList();
        var split = document.caseId().startsWith("transit-board-") ? 2_900 : 2_500;
        var expected = List.of(
                List.of(200, 200, 9_800, split),
                List.of(200, split, 9_800, 9_800));
        var actual = result.stream().map(item -> List.of(
                item.boundingBox().left(), item.boundingBox().top(),
                item.boundingBox().right(), item.boundingBox().bottom())).toList();
        if (!expected.equals(actual)) throw invalid("R5P2_ASSIGNMENT_REGION_DRIFT");
        return List.copyOf(result);
    }

    private static Thresholds validateThresholds(Thresholds value) {
        if (value == null
                || !"MATCHED_LINE_GAIN_OR_CHARACTER_ERROR_REDUCTION"
                .equals(value.perCaseTargetImprovementRule())
                || value.maximumPerCaseHallucinationIncrease() != 0
                || value.minimumConfirmationLineRecallGainBps() != 500
                || value.minimumConfirmationCharacterErrorReduction() != 1
                || value.maximumConfirmationOrderRegressionBps() != 100
                || value.maximumConfirmationRepeatRegressionBps() != 100
                || value.areaOverlapBps() != R5P2SourceLineReconciliation.AREA_OVERLAP_BPS
                || value.verticalOverlapBps() != R5P2SourceLineReconciliation.VERTICAL_OVERLAP_BPS
                || !"smaller-center-in-larger-closed-open/1.0".equals(value.centerRule())) {
            throw invalid("R5P2_ASSIGNMENT_THRESHOLD_DRIFT");
        }
        return value;
    }

    private static Identities validateIdentities(Identities value, RuntimeComponents runtime) {
        var policy = RapidOcrBaselineContract.policy(RapidOcrBaselineContract.DEFAULT_TIMEOUT_MILLIS);
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
                || !("AcquisitionPolicy/1.0:" + policy.identity())
                .equals(value.acquisitionPolicyIdentity())
                || !"renderweave-r5p2-complete-branch-process/1.0"
                .equals(value.branchProcessContractIdentity())
                || !policy.capabilityIdentity().equals(value.capabilityIdentity())
                || !policy.adapterIdentity().equals(value.adapterIdentity())
                || !"401565b45944ee85929c38415e5d4255f5b5559e80b9d67ee9f83a3419af27d0"
                .equals(value.adapterSourceSha256())
                || !"renderweave-r5p2-public-branch-process-client/1.0"
                .equals(value.publicProcessClientIdentity())
                || !"465c92e971b14fefe04a9cecff214b8c9e8dd1b25a7b87db8252b6ea7c759c32"
                .equals(value.publicProcessClientSourceSha256())
                || !R5P2SourceLineReconciliation.PROJECTION_IDENTITY.equals(value.projectionIdentity())
                || !R5P2SourceLineReconciliation.POLICY_IDENTITY
                .equals(value.reconciliationPolicyIdentity())
                || !"987c9d77aca3350545718c4e055dc142514716d05826d3fb6d75ac316c86eba1"
                .equals(value.reconciliationSourceSha256())
                || !"renderweave-rapidocr-shadow-case-evaluator/1.0"
                .equals(value.caseEvaluatorIdentity())
                || !"renderweave-r5p2-paired-product-view-evaluator/1.0"
                .equals(value.evaluatorIdentity())
                || !"two-isolated-complete-paired-runs-48-processes/1.0"
                .equals(value.runProtocolIdentity())) {
            throw invalid("R5P2_ASSIGNMENT_IDENTITY_DRIFT");
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
                || !"401565b45944ee85929c38415e5d4255f5b5559e80b9d67ee9f83a3419af27d0"
                .equals(value.adapterSha256())
                || !"c05805399d7d10b1d1e32f2f52faf2a9fe6617db50f6b96221cb3b7be47e58a5"
                .equals(value.modelManifestSha256())) {
            throw invalid("R5P2_ASSIGNMENT_RUNTIME_DRIFT");
        }
        var frames = List.of(
                "os=" + value.os(), "arch=" + value.arch(),
                "java-vendor=" + value.javaVendor(), "java-runtime=" + value.javaRuntime(),
                "python=" + value.python(), "rapidocr=" + value.rapidocr(),
                "openvino=" + value.openvino(), "image-runtime=" + value.imageRuntime(),
                "adapter-sha256=" + value.adapterSha256(),
                "model-sha256=" + value.modelManifestSha256());
        var expected = "renderweave-r5p2-runtime/1.0:" + framedSha256(frames);
        if (!expected.equals(identity)) throw invalid("R5P2_ASSIGNMENT_RUNTIME_DRIFT");
    }

    private static AccessState validateAccessState(AccessState value) {
        if (value == null || !"FROZEN_PRE_RESULT".equals(value.state())
                || value.freshRawFixtureGenerations() != 4
                || value.historicalRawFixtureReuses() != 8
                || value.preFreezeGoldReads() != 0
                || value.preFreezeMetricReads() != 0
                || value.officialProducerGoldMetricReads() != 0
                || value.independentReplayGoldMetricReads() != 0
                || value.exploratoryRuns() != 0
                || value.postFreezeMutations() != 0) {
            throw invalid("R5P2_ASSIGNMENT_ACCESS_STATE_DRIFT");
        }
        return value;
    }

    private static String fixtureSetIdentity(List<CaseAssignment> cases) {
        var frames = new ArrayList<String>();
        frames.add("contract=" + FIXTURE_SET_VERSION);
        for (var item : cases) {
            frames.add("fixture=" + item.caseId() + ":" + item.rawFixtureSha256()
                    + ":" + item.width() + "x" + item.height()
                    + ":" + item.normalizationFingerprint());
        }
        return FIXTURE_SET_VERSION + ":" + framedSha256(frames);
    }

    private static String evaluationIdentity(
            String assignmentIdentity,
            String fixtureSetIdentity,
            List<CaseAssignment> cases,
            Thresholds thresholds,
            Identities identities
    ) {
        var frames = new ArrayList<String>();
        frames.add("assignment=" + assignmentIdentity);
        frames.add("fixtures=" + fixtureSetIdentity);
        frames.add("authority=" + AUTHORITY_IDENTITY);
        frames.add("baseline=" + BASELINE_REVISION);
        frames.add("process=" + identities.branchProcessContractIdentity());
        frames.add("reconciliation=" + identities.reconciliationPolicyIdentity());
        frames.add("runtime=" + identities.runtimeIdentity());
        frames.add("thresholds=" + thresholdIdentity(thresholds));
        cases.forEach(item -> frames.add("case=" + item.cohort() + ":" + item.caseIdentity()
                + ":" + item.rawFixtureSha256()));
        return EVALUATION_VERSION + ":" + framedSha256(frames);
    }

    private static String thresholdIdentity(Thresholds value) {
        var frames = List.of(
                "per-case=" + value.perCaseTargetImprovementRule(),
                "hallucination-max=" + value.maximumPerCaseHallucinationIncrease(),
                "confirmation-line-gain-bps=" + value.minimumConfirmationLineRecallGainBps(),
                "confirmation-character-reduction="
                        + value.minimumConfirmationCharacterErrorReduction(),
                "confirmation-order-regression-bps="
                        + value.maximumConfirmationOrderRegressionBps(),
                "confirmation-repeat-regression-bps="
                        + value.maximumConfirmationRepeatRegressionBps(),
                "area-overlap-bps=" + value.areaOverlapBps(),
                "vertical-overlap-bps=" + value.verticalOverlapBps(),
                "center-rule=" + value.centerRule());
        return "renderweave-r5p2-thresholds/1.0:" + framedSha256(frames);
    }

    private static String normalizationFingerprint(String sourceReference, byte[] bytes) {
        var digest = sha256Digest();
        updateFramed(digest, "image-only".getBytes(StandardCharsets.UTF_8));
        updateFramed(digest, NORMALIZATION_PROFILE_ID.getBytes(StandardCharsets.UTF_8));
        updateFramed(digest, sourceReference.getBytes(StandardCharsets.UTF_8));
        updateFramed(digest, "image/png".getBytes(StandardCharsets.UTF_8));
        updateFramed(digest, bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateFramed(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String selectionRank(String partition, String difficulty, String caseIdentity) {
        return sha256((SELECTION_POLICY + "|" + partition + "|" + difficulty + "|"
                + caseIdentity).getBytes(StandardCharsets.UTF_8));
    }

    private static String family(String caseId) {
        var family = FAMILY_SUFFIX.matcher(caseId).replaceFirst("");
        if (family.equals(caseId) || !family.matches("[a-z][a-z0-9-]{0,127}")) {
            throw invalid("R5P2_SELECTION_FAMILY_INVALID");
        }
        return family;
    }

    private static String text(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("R5P2_SELECTION_METADATA_INVALID");
        }
        return value.textValue();
    }

    private static byte[] resource(ClassLoader loader, String name, String code) {
        try (var input = loader.getResourceAsStream(name)) {
            if (input == null) throw invalid(code);
            return input.readAllBytes();
        } catch (IOException failure) {
            throw invalid(code);
        }
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static String framedSha256(List<String> values) {
        var digest = sha256Digest();
        for (var value : values) {
            var bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public enum Cohort { HISTORICAL_DIAGNOSTIC, SEALED_CONFIRMATION }

    public enum HoldoutAccessRole { OFFICIAL_PRODUCER, INDEPENDENT_REPLAY, EXPLORATORY }

    public enum HoldoutAccessStatus { PRE_FREEZE, FROZEN, OPEN, SEALED, INVALID }

    public record Selection(
            String caseId,
            String partition,
            String difficulty,
            String family,
            String caseIdentity,
            String rankSha256
    ) { }

    public record CaseAssignment(
            String caseId,
            Cohort cohort,
            LayeredEvaluationRecord.Partition partition,
            LayeredEvaluationRecord.Difficulty difficulty,
            List<LayeredEvaluationRecord.FailureSlice> failureSlices,
            String family,
            String caseIdentity,
            String selectionRankSha256,
            String renderIdentity,
            String rawFixtureResource,
            String rawFixtureSha256,
            String fixtureOrigin,
            int width,
            int height,
            String normalizationSourceReference,
            String normalizationFingerprint,
            List<BoundedVisualInspection.InspectionRegion> regions,
            long encodedBytes
    ) {
        public CaseAssignment {
            failureSlices = List.copyOf(Objects.requireNonNull(failureSlices, "failureSlices"));
            regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        }

        public boolean diagnosticVetoOnly() {
            return cohort == Cohort.HISTORICAL_DIAGNOSTIC;
        }

        public boolean contributesFreshConfirmation() {
            return cohort == Cohort.SEALED_CONFIRMATION;
        }

        public boolean mayClaimHoldoutAcceptance() { return false; }

        @Override
        public String toString() {
            return "CaseAssignment[caseId=" + caseId + ", cohort=" + cohort
                    + ", partition=" + partition + ", caseIdentity=" + caseIdentity
                    + ", rawFixtureSha256=" + rawFixtureSha256 + ", dimensions="
                    + width + "x" + height + ", payload=<redacted>]";
        }
    }

    public record Thresholds(
            String perCaseTargetImprovementRule,
            int maximumPerCaseHallucinationIncrease,
            int minimumConfirmationLineRecallGainBps,
            int minimumConfirmationCharacterErrorReduction,
            int maximumConfirmationOrderRegressionBps,
            int maximumConfirmationRepeatRegressionBps,
            int areaOverlapBps,
            int verticalOverlapBps,
            String centerRule
    ) { }

    public record Identities(
            String normalizerIdentity,
            String staticPlannerVersion,
            String staticPlannerSourceSha256,
            String actionModuleVersion,
            String actionModuleSourceSha256,
            String successorPlanVersion,
            String actionPolicyIdentity,
            String acquisitionPolicyIdentity,
            String branchProcessContractIdentity,
            String capabilityIdentity,
            String adapterIdentity,
            String adapterSourceSha256,
            String publicProcessClientIdentity,
            String publicProcessClientSourceSha256,
            String projectionIdentity,
            String reconciliationPolicyIdentity,
            String reconciliationSourceSha256,
            String caseEvaluatorIdentity,
            String evaluatorIdentity,
            String runProtocolIdentity,
            String runtimeIdentity
    ) { }

    public record RuntimeComponents(
            String os,
            String arch,
            String javaVendor,
            String javaRuntime,
            String python,
            String rapidocr,
            String openvino,
            String imageRuntime,
            String adapterSha256,
            String modelManifestSha256
    ) { }

    public record AccessState(
            String state,
            int freshRawFixtureGenerations,
            int historicalRawFixtureReuses,
            int preFreezeGoldReads,
            int preFreezeMetricReads,
            int officialProducerGoldMetricReads,
            int independentReplayGoldMetricReads,
            int exploratoryRuns,
            int postFreezeMutations
    ) { }

    public record ExternalProviderUsage(long attempts, long reservations, long costMicrosCny) {
        public boolean zeroUsage() {
            return attempts == 0 && reservations == 0 && costMicrosCny == 0;
        }
    }

    public record HoldoutAccessGrant(
            HoldoutAccessRole role,
            String assignmentIdentity,
            String holdoutCaseId,
            int confirmationOrdinal
    ) { }

    public static final class HoldoutAccessAudit {
        private final String expectedAssignmentIdentity;
        private final List<String> expectedCaseOrder;
        private final String holdoutCaseId;
        private HoldoutAccessStatus status;
        private HoldoutAccessGrant issued;
        private int goldMetricReads;

        private HoldoutAccessAudit(
                String expectedAssignmentIdentity,
                List<String> expectedCaseOrder,
                String holdoutCaseId,
                HoldoutAccessStatus status
        ) {
            this.expectedAssignmentIdentity = expectedAssignmentIdentity;
            this.expectedCaseOrder = List.copyOf(expectedCaseOrder);
            this.holdoutCaseId = holdoutCaseId;
            this.status = status;
        }

        static HoldoutAccessAudit preFreeze(String identity, String holdoutCaseId) {
            return new HoldoutAccessAudit(identity, CONFIRMATION_CASE_IDS,
                    holdoutCaseId, HoldoutAccessStatus.PRE_FREEZE);
        }

        private static HoldoutAccessAudit frozen(
                String identity, List<String> caseOrder, String holdoutCaseId
        ) {
            return new HoldoutAccessAudit(identity, caseOrder,
                    holdoutCaseId, HoldoutAccessStatus.FROZEN);
        }

        public synchronized HoldoutAccessGrant open(
                HoldoutAccessRole role,
                String assignmentIdentity,
                List<String> caseOrder
        ) {
            if (status == HoldoutAccessStatus.PRE_FREEZE) {
                status = HoldoutAccessStatus.INVALID;
                throw new IllegalStateException("R5P2_HOLDOUT_ACCESS_BEFORE_FREEZE");
            }
            if (role == HoldoutAccessRole.EXPLORATORY) {
                status = HoldoutAccessStatus.INVALID;
                throw new IllegalStateException("R5P2_HOLDOUT_EXPLORATORY_FORBIDDEN");
            }
            if (status != HoldoutAccessStatus.FROZEN
                    || !expectedAssignmentIdentity.equals(assignmentIdentity)
                    || !expectedCaseOrder.equals(caseOrder)) {
                status = HoldoutAccessStatus.INVALID;
                throw new IllegalStateException("R5P2_HOLDOUT_ACCESS_GRANT_INVALID");
            }
            issued = new HoldoutAccessGrant(role, assignmentIdentity, holdoutCaseId,
                    expectedCaseOrder.indexOf(holdoutCaseId));
            status = HoldoutAccessStatus.OPEN;
            return issued;
        }

        public synchronized void recordGoldMetricRead(
                HoldoutAccessGrant grant, String caseId
        ) {
            if (status != HoldoutAccessStatus.OPEN || grant != issued
                    || !holdoutCaseId.equals(caseId) || goldMetricReads != 0) {
                status = HoldoutAccessStatus.INVALID;
                throw new IllegalStateException("R5P2_HOLDOUT_ACCESS_EXTRA");
            }
            goldMetricReads = 1;
        }

        public synchronized void seal(HoldoutAccessGrant grant) {
            if (status != HoldoutAccessStatus.OPEN || grant != issued || goldMetricReads != 1) {
                status = HoldoutAccessStatus.INVALID;
                throw new IllegalStateException("R5P2_HOLDOUT_ACCESS_INCOMPLETE");
            }
            status = HoldoutAccessStatus.SEALED;
        }

        public synchronized HoldoutAccessStatus status() { return status; }

        public synchronized int goldMetricReads() { return goldMetricReads; }
    }

    private record Stratum(String partition, String difficulty) { }

    private record SelectionMetadata(
            String caseId,
            String partition,
            String difficulty,
            List<String> failureSlices,
            String caseIdentity,
            String family
    ) { }

    private record Document(
            String contractVersion,
            String identityVersion,
            String evaluationVersion,
            String approvedSpecIdentity,
            String authorityIdentity,
            String baselineRevision,
            String corpusVersion,
            String corpusIdentity,
            String corpusIdentityLockResource,
            String corpusIdentityLockSha256,
            String selectionPolicyIdentity,
            List<String> selectionAllowedFields,
            List<String> priorPairedAssignmentResources,
            List<String> priorPairedAssignmentSha256,
            String historicalUsagePolicy,
            String confirmationUsagePolicy,
            String normalizationProfileId,
            List<CaseDocument> caseAssignments,
            Thresholds thresholds,
            Identities identities,
            RuntimeComponents runtimeComponents,
            AccessState accessState,
            ExternalProviderUsage externalProviderUsage,
            int apiKeyReads,
            String terminalCode
    ) { }

    private record CaseDocument(
            String caseId,
            Cohort cohort,
            LayeredEvaluationRecord.Partition partition,
            LayeredEvaluationRecord.Difficulty difficulty,
            List<LayeredEvaluationRecord.FailureSlice> failureSlices,
            String family,
            String caseIdentity,
            String selectionRankSha256,
            String renderIdentity,
            String rawFixtureResource,
            String rawFixtureSha256,
            String fixtureOrigin,
            int width,
            int height,
            String normalizationSourceReference,
            String normalizationFingerprint,
            List<RegionDocument> regions
    ) { }

    private record RegionDocument(
            String baseViewId,
            List<Integer> boundingBox,
            BoundedVisualInspection.MarginPreset marginPreset,
            BoundedVisualInspection.ResolutionPreset resolutionPreset
    ) { }
}
