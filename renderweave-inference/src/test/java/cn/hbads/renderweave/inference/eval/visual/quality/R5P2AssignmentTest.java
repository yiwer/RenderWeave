package cn.hbads.renderweave.inference.eval.visual.quality;

import cn.hbads.renderweave.inference.eval.visual.LayeredEvaluationRecord;
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.InputNormalizer;
import cn.hbads.renderweave.inference.live.BoundedVisualInspection;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R5P2AssignmentTest {
    private static final String MANIFEST = "visual-eval/r5p2/assignment-v1.json";
    private static final String IDENTITY_LOCK = "visual-eval/v2/identity-lock.json";
    private static final String OLD_R5P = "visual-eval/r5p/paired-view-assignment-v1.json";
    private static final List<String> DIAGNOSTIC = List.of(
            "transit-board-v3", "restaurant-menu-v3", "hospital-schedule-v3",
            "transit-board-v5", "transit-board-v2", "invoice-lines-v3",
            "school-timetable-v4", "building-directory-v5");
    private static final List<String> CONFIRMATION = List.of(
            "weather-forecast-v3", "warehouse-inventory-v2",
            "event-agenda-v4", "product-catalog-v5");
    private static final List<String> RANKS = List.of(
            "19f8156bddc9fd7a08e8324e6e3e165060207060fe49c972e51613dabcd1068d",
            "25fc1c7bc6c9f070c90893c9839c5c9859db0e5fd9492b0bcb4ad9be02251535",
            "2552e253b354d65e1e8c5d570f696104c9bf62715e6db11cf2e4453bad15417e",
            "5d7decfb23a7b8090ddc032ead18c40c1d6c7fd2852557c56447cfa58351c3bc");

    @Test
    void freezesEightDiagnosticVetoesAndFourFreshConfirmationCases() {
        var assignment = R5P2Assignment.load();

        assertEquals(DIAGNOSTIC, assignment.diagnosticCases().stream()
                .map(R5P2Assignment.CaseAssignment::caseId).toList());
        assertTrue(assignment.diagnosticCases().stream().allMatch(item ->
                item.diagnosticVetoOnly() && !item.contributesFreshConfirmation()
                        && !item.mayClaimHoldoutAcceptance()));
        assertEquals(CONFIRMATION, assignment.confirmationCases().stream()
                .map(R5P2Assignment.CaseAssignment::caseId).toList());
        assertEquals(RANKS, assignment.confirmationCases().stream()
                .map(R5P2Assignment.CaseAssignment::selectionRankSha256).toList());
        assertEquals(List.of(
                        LayeredEvaluationRecord.Partition.DEV,
                        LayeredEvaluationRecord.Partition.DEV,
                        LayeredEvaluationRecord.Partition.DEV,
                        LayeredEvaluationRecord.Partition.HOLDOUT),
                assignment.confirmationCases().stream()
                        .map(R5P2Assignment.CaseAssignment::partition).toList());
        assertEquals(4, assignment.confirmationCases().stream()
                .map(R5P2Assignment.CaseAssignment::family).distinct().count());
        assertTrue(assignment.confirmationCases().stream().allMatch(item ->
                !item.diagnosticVetoOnly() && item.contributesFreshConfirmation()
                        && !item.mayClaimHoldoutAcceptance()));
        assertEquals("R5P2_ASSIGNMENT_FROZEN", assignment.terminalCode());
        assertEquals("FrozenR5P2Assignment/1.0", assignment.contractVersion());
        assertEquals(
                "renderweave-r5p2-frozen-assignment/1.0:"
                        + "74ec12bc198db1f9597391102a44676918b4c6122851a7b50d338446fe5f7cbd",
                assignment.identity());
        assertEquals(
                "renderweave-r5p2-repository-raster-fixture-set/1.0:"
                        + "3e425016eb01d824391deaa91059e13ef0230f3a444a004e2a28504f1d1e7d92",
                assignment.fixtureSetIdentity());
        assertEquals(
                "renderweave-r5p2-thresholds/1.0:"
                        + "ab91362c4738a5feaadc67053604ddfefa861b16012f0523a088e3964430a8e1",
                assignment.thresholdIdentity());
        assertEquals(
                "renderweave-r5p2-paired-product-view-evaluation/1.0:"
                        + "b5a9fb0d38e9b4e2d06b4be93d272bb6704d6ef56d81fa18f1a593d22a946558",
                assignment.evaluationIdentity());
    }

    @Test
    void independentlyRecomputesTheMetadataOnlySelection() throws Exception {
        var loader = R5P2Assignment.class.getClassLoader();
        byte[] identityLock;
        try (var input = loader.getResourceAsStream(IDENTITY_LOCK)) {
            identityLock = input.readAllBytes();
        }
        var selected = R5P2Assignment.recomputeSelection(identityLock);

        assertEquals(CONFIRMATION, selected.stream()
                .map(R5P2Assignment.Selection::caseId).toList());
        assertEquals(RANKS, selected.stream()
                .map(R5P2Assignment.Selection::rankSha256).toList());

        var poisoned = new String(identityLock, StandardCharsets.UTF_8).replace(
                "\"caseId\" : \"weather-forecast-v3\"",
                "\"caseId\" : \"weather-forecast-v3\", \"ocr\" : \"forbidden\"");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> R5P2Assignment.recomputeSelection(
                        poisoned.getBytes(StandardCharsets.UTF_8)));
        assertEquals("R5P2_SELECTION_FORBIDDEN_METADATA", failure.getMessage());
    }

    @Test
    void bindsOneRawFixtureAndOneProductNormalizationFingerprintPerCase() throws Exception {
        var assignment = R5P2Assignment.load();
        var loader = R5P2Assignment.class.getClassLoader();
        var freshFixtureCount = 0;
        for (var item : assignment.cases()) {
            byte[] bytes;
            try (var input = loader.getResourceAsStream(item.rawFixtureResource())) {
                assertTrue(input != null, item.rawFixtureResource());
                bytes = input.readAllBytes();
            }
            assertEquals(item.rawFixtureSha256(), R5P2Assignment.sha256(bytes));
            assertEquals("render-sha256:" + item.rawFixtureSha256(), item.renderIdentity());
            assertFalse(item.rawFixtureResource().contains("baseline"));
            assertFalse(item.rawFixtureResource().contains("successor"));
            if (item.rawFixtureResource().startsWith("visual-eval/r5p2/raw/")) {
                freshFixtureCount++;
            }

            var store = new MemoryBlobStore();
            var normalized = new InputNormalizer(store).normalize(new InferenceInput(
                    InferenceMode.IMAGE_ONLY,
                    R5P2Assignment.NORMALIZATION_PROFILE_ID,
                    item.normalizationSourceReference(),
                    true,
                    List.of(new InferenceInput.BinaryInput(
                            item.caseId() + ".png", "image/png", bytes)),
                    List.of()));
            assertEquals(item.normalizationFingerprint(), normalized.inputFingerprint());
            assertEquals(1, normalized.artifacts().size());
            assertEquals(item.width(), normalized.artifacts().getFirst().width());
            assertEquals(item.height(), normalized.artifacts().getFirst().height());
            assertEquals(1, store.writeCalls);
        }
        assertEquals(4, freshFixtureCount);
    }

    @Test
    void freezesUniformRegionsThresholdsRuntimeProcessAndReconciliationIdentities() {
        var assignment = R5P2Assignment.load();

        for (var item : assignment.cases()) {
            assertEquals(2, item.regions().size());
            var split = item.caseId().startsWith("transit-board-") ? 2_900 : 2_500;
            assertEquals(List.of(
                    List.of(200, 200, 9_800, split),
                    List.of(200, split, 9_800, 9_800)),
                    item.regions().stream().map(region -> List.of(
                            region.boundingBox().left(), region.boundingBox().top(),
                            region.boundingBox().right(), region.boundingBox().bottom())).toList());
            assertTrue(item.regions().stream().allMatch(region ->
                    "view-00-overview-00".equals(region.baseViewId())
                            && region.marginPreset()
                            == BoundedVisualInspection.MarginPreset.TIGHT_0000_BPS
                            && region.resolutionPreset()
                            == BoundedVisualInspection.ResolutionPreset.INSPECT_LONG_EDGE_2400));
        }
        assertTrue(assignment.confirmationCases().stream().allMatch(item ->
                item.regions().getFirst().boundingBox().bottom() == 2_500
                        && item.regions().get(1).boundingBox().top() == 2_500));
        assertEquals(5_000, assignment.thresholds().areaOverlapBps());
        assertEquals(8_000, assignment.thresholds().verticalOverlapBps());
        assertEquals(R5P2SourceLineReconciliation.POLICY_IDENTITY,
                assignment.identities().reconciliationPolicyIdentity());
        assertEquals("renderweave-r5p2-complete-branch-process/1.0",
                assignment.identities().branchProcessContractIdentity());
        assertEquals(
                "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1",
                assignment.identities().capabilityIdentity());
        assertEquals("two-isolated-complete-paired-runs-48-processes/1.0",
                assignment.identities().runProtocolIdentity());
        assertEquals("FROZEN_PRE_RESULT", assignment.accessState().state());
        assertEquals(0, assignment.accessState().preFreezeGoldReads());
        assertEquals(0, assignment.accessState().preFreezeMetricReads());
        assertEquals(0, assignment.accessState().exploratoryRuns());
        assertEquals(4, assignment.accessState().freshRawFixtureGenerations());
        assertTrue(assignment.externalProviderUsage().zeroUsage());
        assertEquals(0, assignment.apiKeyReads());
    }

    @Test
    void holdoutAccessorFailsBeforeFreezeAndAdmitsOnlyOneExactOfficialSession() {
        var assignment = R5P2Assignment.load();
        var preFreeze = R5P2Assignment.HoldoutAccessAudit.preFreeze(
                assignment.identity(), "product-catalog-v5");
        var early = assertThrows(IllegalStateException.class, () -> preFreeze.open(
                R5P2Assignment.HoldoutAccessRole.OFFICIAL_PRODUCER,
                assignment.identity(), CONFIRMATION));
        assertEquals("R5P2_HOLDOUT_ACCESS_BEFORE_FREEZE", early.getMessage());
        assertEquals(R5P2Assignment.HoldoutAccessStatus.INVALID, preFreeze.status());

        for (var role : List.of(
                R5P2Assignment.HoldoutAccessRole.OFFICIAL_PRODUCER,
                R5P2Assignment.HoldoutAccessRole.INDEPENDENT_REPLAY)) {
            var audit = assignment.newHoldoutAccessAudit();
            var grant = audit.open(role, assignment.identity(), CONFIRMATION);
            audit.recordGoldMetricRead(grant, "product-catalog-v5");
            audit.seal(grant);
            assertEquals(R5P2Assignment.HoldoutAccessStatus.SEALED, audit.status());
            assertEquals(1, audit.goldMetricReads());
            var extra = assertThrows(IllegalStateException.class,
                    () -> audit.recordGoldMetricRead(grant, "product-catalog-v5"));
            assertEquals("R5P2_HOLDOUT_ACCESS_EXTRA", extra.getMessage());
            assertEquals(R5P2Assignment.HoldoutAccessStatus.INVALID, audit.status());
        }

        var exploratory = assignment.newHoldoutAccessAudit();
        var rejected = assertThrows(IllegalStateException.class, () -> exploratory.open(
                R5P2Assignment.HoldoutAccessRole.EXPLORATORY,
                assignment.identity(), CONFIRMATION));
        assertEquals("R5P2_HOLDOUT_EXPLORATORY_FORBIDDEN", rejected.getMessage());
    }

    @Test
    void rejectsSelectionSubstitutionPriorPairedOverlapAndPostFreezeMutation() {
        assertManifestMutation(
                "\"selectionRankSha256\": \"19f8156bddc9fd7a08e8324e6e3e165060207060fe49c972e51613dabcd1068d\"",
                "\"selectionRankSha256\": \"09f8156bddc9fd7a08e8324e6e3e165060207060fe49c972e51613dabcd1068d\"",
                "R5P2_ASSIGNMENT_SELECTION_DRIFT");
        assertManifestMutation(
                "\"partition\": \"HOLDOUT\",\n      \"difficulty\": \"NOISY\",\n"
                        + "      \"failureSlices\": [\"REPEATED_LIST\"],\n"
                        + "      \"family\": \"product-catalog\"",
                "\"partition\": \"HOLDOUT\",\n      \"difficulty\": \"LOW_CONTRAST\",\n"
                        + "      \"failureSlices\": [\"REPEATED_LIST\"],\n"
                        + "      \"family\": \"product-catalog\"",
                "R5P2_ASSIGNMENT_METADATA_DRIFT");
        assertManifestMutation(
                "\"terminalCode\": \"R5P2_ASSIGNMENT_FROZEN\"",
                "\"observedResult\": \"PASS\",\n  \"terminalCode\": \"R5P2_ASSIGNMENT_FROZEN\"",
                "R5P2_ASSIGNMENT_INVALID");

        var loader = R5P2Assignment.class.getClassLoader();
        var old = resourceText(loader, OLD_R5P);
        var overlapped = old.replace(
                "\"caseAssignments\": [",
                "\"caseAssignments\": [{\"caseId\":\"weather-forecast-v3\"},");
        var overlapLoader = overriding(loader, Map.of(
                OLD_R5P, overlapped.getBytes(StandardCharsets.UTF_8)));
        var overlap = assertThrows(IllegalArgumentException.class,
                () -> R5P2Assignment.load(overlapLoader));
        assertEquals("R5P2_ASSIGNMENT_PRIOR_PAIRED_OVERLAP", overlap.getMessage());
    }

    private static void assertManifestMutation(String before, String after, String code) {
        var loader = R5P2Assignment.class.getClassLoader();
        var original = resourceText(loader, MANIFEST);
        var mutated = original.replace(before, after);
        if (mutated.equals(original)) throw new AssertionError("mutation anchor missing");
        var failure = assertThrows(IllegalArgumentException.class, () -> R5P2Assignment.load(
                overriding(loader, Map.of(MANIFEST, mutated.getBytes(StandardCharsets.UTF_8)))));
        assertEquals(code, failure.getMessage());
    }

    private static String resourceText(ClassLoader loader, String resource) {
        try (var input = loader.getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("resource missing: " + resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static ClassLoader overriding(ClassLoader parent, Map<String, byte[]> resources) {
        return new ClassLoader(parent) {
            @Override
            public InputStream getResourceAsStream(String name) {
                var bytes = resources.get(name);
                return bytes == null ? super.getResourceAsStream(name)
                        : new ByteArrayInputStream(bytes);
            }
        };
    }

    private static final class MemoryBlobStore implements BlobStore {
        private final Map<String, byte[]> values = new LinkedHashMap<>();
        private int writeCalls;

        @Override
        public WriteReceipt write(String artifactId, byte[] bytes) {
            writeCalls++;
            var locator = "r5p2-test:" + artifactId;
            return new WriteReceipt(locator, values.putIfAbsent(locator, bytes.clone()) == null);
        }

        @Override
        public byte[] read(String locator) {
            return values.get(locator).clone();
        }

        @Override
        public void delete(String locator) {
            values.remove(locator);
        }
    }
}
