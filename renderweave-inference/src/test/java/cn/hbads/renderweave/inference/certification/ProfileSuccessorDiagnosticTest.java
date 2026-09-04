package cn.hbads.renderweave.inference.certification;

import cn.hbads.renderweave.inference.provider.ProfileRunBudgetPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileSuccessorDiagnosticTest {
    private static final Instant T0 = Instant.parse("2026-08-17T12:00:00Z");
    private static final UUID CYCLE_ID =
            UUID.fromString("47474747-4747-4747-4747-474747474747");
    private static final String PROFILE_SHA =
            "a9fe98e1cfa4b7cc126db1f74601fdebe60526a1c999924daf189ed5f1ac5eb0";
    private static final AuthorizedCertificationCase DIAGNOSTIC_CASE =
            new AuthorizedCertificationCase(
                    "v46-failed-route-82",
                    ProfileSuccessorDiagnosticManifest.V46_FAILED_ARTIFACT_SHA256
            );

    @Test
    void exactDiagnosticJ1ProducesProviderZeroProofOnly() {
        var manifest = manifest();
        var authorization = authorization(manifest, 5, 100_000, 3_000_000,
                T0.plusSeconds(1), T0.plusSeconds(2 * 60 * 60));

        var proof = new ImageOnlyCertificationPreflight()
                .requireProfileSuccessorDiagnosticProviderZeroProof(
                        authorization, manifest, T0.plusSeconds(2));

        assertEquals(CertificationStage.PROFILE_SUCCESSOR_DIAGNOSTIC_1, proof.stage());
        assertEquals(PROFILE_SHA, proof.profileSha256());
        assertEquals(0, proof.providerAttempts());
        assertEquals(0, proof.providerReservations());
        assertEquals(0, proof.providerCostMicrosCny());
        assertEquals(0, proof.apiKeyReads());
        assertFalse(proof.grantsProviderEgress());
    }

    @Test
    void diagnosticCapsWindowArtifactAndScopeFailClosed() {
        var manifest = manifest();
        var preflight = new ImageOnlyCertificationPreflight();
        assertReason("PROFILE_SUCCESSOR_DIAGNOSTIC_CAPS_MISMATCH", () -> preflight
                .requireProfileSuccessorDiagnosticProviderZeroProof(
                        authorization(manifest, 4, 100_000, 3_000_000,
                                T0.plusSeconds(1), T0.plusSeconds(10)),
                        manifest, T0.plusSeconds(2)));
        assertReason("PROFILE_SUCCESSOR_DIAGNOSTIC_CAPS_MISMATCH", () -> preflight
                .requireProfileSuccessorDiagnosticProviderZeroProof(
                        authorization(manifest, 5, 100_001, 3_000_000,
                                T0.plusSeconds(1), T0.plusSeconds(10)),
                        manifest, T0.plusSeconds(2)));
        assertReason("PROFILE_SUCCESSOR_DIAGNOSTIC_AUTHORIZATION_WINDOW_INVALID", () ->
                preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                        authorization(manifest, 5, 100_000, 3_000_000,
                                T0.plusSeconds(1), T0.plusSeconds(2 * 60 * 60 + 2)),
                        manifest, T0.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class, () ->
                ProfileSuccessorDiagnosticManifest.create(
                        CYCLE_ID, PROFILE_SHA, normalizationIdentity(),
                        new AuthorizedCertificationCase("different-case", "f".repeat(64)), T0));
    }

    @Test
    void diagnosticPassRequiresReviewRequiredAndManualAcceptanceWithoutCertificationCredit() {
        var manifest = manifest();
        var evaluator = new ProfileSuccessorDiagnosticEvaluator();

        assertTrue(evaluator.evaluate(manifest, verdict(
                CertificationTerminalState.REVIEW_REQUIRED, true)).passed());
        assertFalse(evaluator.evaluate(manifest, verdict(
                CertificationTerminalState.REVIEW_REQUIRED, false)).passed());
        assertFalse(evaluator.evaluate(manifest, verdict(
                CertificationTerminalState.COMPLETED, true)).passed());
        assertThrows(IllegalArgumentException.class, () -> new CertificationStageOutcome(
                CertificationStage.PROFILE_SUCCESSOR_DIAGNOSTIC_1, 1, 1,
                "renderweave-image-only-certification-stage-evidence/1.0:" + "a".repeat(64)));
    }

    @Test
    void preparedDiagnosticIdentitiesReplayExactly() {
        var prepared = ProfileSuccessorDiagnosticManifest.create(
                UUID.fromString("4ae94545-2c95-41dc-934e-1661aeb6c121"),
                PROFILE_SHA,
                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                        + ":b3074928fc15b37ba6ccb0900fde549dd2733db33d688b559c66320546fd5e04",
                DIAGNOSTIC_CASE,
                Instant.parse("2026-08-17T12:34:00Z")
        );

        assertEquals(ProfileSuccessorDiagnosticManifest.EVALUATOR_VERSION
                        + ":b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e",
                prepared.evaluatorIdentity());
        assertEquals(ProfileSuccessorDiagnosticManifest.VERSION
                        + ":5fc9b6517744f2c31a043c713d230e2b40667a62e6baf6d0486371913f17c78c",
                prepared.manifestIdentity());
    }

    @Test
    void v48CreatesFreshProfileBoundIdentitiesWithoutChangingTheV47Replay() {
        var prepared = ProfileSuccessorDiagnosticManifest.createForProfile(
                ProfileRunBudgetPolicy.IMAGE_ONLY_V48_PROFILE_ID,
                UUID.fromString("48484848-4848-4848-4848-484848484848"),
                "22f40ef4c865e11778eef4558c20c383e6611e068d8d08be0d080650074d4470",
                normalizationIdentity(), DIAGNOSTIC_CASE, T0
        );

        assertEquals(ProfileRunBudgetPolicy.IMAGE_ONLY_V48_PROFILE_ID, prepared.profileId());
        assertTrue(prepared.manifestIdentity().matches(
                java.util.regex.Pattern.quote(ProfileSuccessorDiagnosticManifest.VERSION)
                        + ":[0-9a-f]{64}"
        ));
        assertEquals(ProfileSuccessorDiagnosticManifest.EVALUATOR_VERSION
                        + ":b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e",
                prepared.evaluatorIdentity());
        assertThrows(IllegalArgumentException.class, () ->
                ProfileSuccessorDiagnosticManifest.createForProfile(
                        ProfileRunBudgetPolicy.IMAGE_ONLY_V47_PROFILE_ID,
                        prepared.cycleId(), prepared.profileSha256(),
                        prepared.normalizationIdentity(), DIAGNOSTIC_CASE, T0
                ));
    }

    @Test
    void v48ExactAuthorizationPreflightIsProviderZeroAndFailsClosedWithoutJ1() {
        var manifest = ProfileSuccessorDiagnosticManifest.createForProfile(
                ProfileRunBudgetPolicy.IMAGE_ONLY_V48_PROFILE_ID,
                UUID.fromString("4e1f41b7-7c42-40d8-afd6-9fe3a35cc54d"),
                "22f40ef4c865e11778eef4558c20c383e6611e068d8d08be0d080650074d4470",
                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                        + ":052e77dabb723f07e76b092e3da8afe1b5a56f7a40dc094451c18c42ee4f9aaa",
                DIAGNOSTIC_CASE,
                Instant.parse("2026-08-17T17:46:31Z")
        );
        var effectiveAt = Instant.parse("2026-08-17T18:00:00Z");
        assertEquals(ProfileSuccessorDiagnosticManifest.VERSION
                        + ":7d14e0b85bf07fc67ae20f0399e00be17a86511822fdae15d180a0a1171ecea7",
                manifest.manifestIdentity());
        assertEquals(ProfileSuccessorDiagnosticManifest.EVALUATOR_VERSION
                        + ":b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e",
                manifest.evaluatorIdentity());
        var authorization = authorization(
                manifest, 5, 100_000, 3_000_000,
                effectiveAt, effectiveAt.plusSeconds(2 * 60 * 60)
        );
        var preflight = new ImageOnlyCertificationPreflight();

        var proof = preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                authorization, manifest, effectiveAt.plusSeconds(1)
        );
        assertEquals(0, proof.providerAttempts());
        assertEquals(0, proof.providerReservations());
        assertEquals(0, proof.providerCostMicrosCny());
        assertEquals(0, proof.apiKeyReads());
        assertFalse(proof.grantsProviderEgress());
        assertReason("CERTIFICATION_AUTHORIZATION_REQUIRED", () ->
                preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                        null, manifest, effectiveAt.plusSeconds(1)
                ));
        assertReason("PROFILE_SUCCESSOR_DIAGNOSTIC_CAPS_MISMATCH", () ->
                preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                        authorization(manifest, 6, 100_000, 3_000_000,
                                effectiveAt, effectiveAt.plusSeconds(2 * 60 * 60)),
                        manifest, effectiveAt.plusSeconds(1)
                ));
    }

    @Test
    void v49FreshManifestRequiresTheExactNormalizationIdentityInJ1() {
        var manifest = ProfileSuccessorDiagnosticManifest.createForProfile(
                ProfileRunBudgetPolicy.IMAGE_ONLY_V49_PROFILE_ID,
                UUID.fromString("432fdfeb-c5ab-4cff-92f4-e066a0d98c8c"),
                "acffdd4dd56ca2f1f7260fc5d37aa48ca3da488a0ae2718f2095bf1530e86eaf",
                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                        + ":3096deba42aeab03be175074e6717ccf6898d4a628950d19eaa6891674d62375",
                DIAGNOSTIC_CASE,
                Instant.parse("2026-08-18T03:50:00Z")
        );
        assertEquals(ProfileSuccessorDiagnosticManifest.VERSION
                        + ":8ff24a6161223f9e1c8bfb586ffd89421a1ee0ad393622e72870848509f0c8e2",
                manifest.manifestIdentity());
        var effectiveAt = Instant.parse("2026-08-18T04:00:00Z");
        var exact = authorization(
                manifest, 5, 100_000, 3_000_000,
                effectiveAt, effectiveAt.plusSeconds(2 * 60 * 60));
        var preflight = new ImageOnlyCertificationPreflight();

        assertFalse(preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                exact, manifest, effectiveAt.plusSeconds(1)).grantsProviderEgress());
        assertReason("PROFILE_SUCCESSOR_DIAGNOSTIC_NORMALIZATION_MISMATCH", () ->
                preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                        withNormalization(exact, null), manifest, effectiveAt.plusSeconds(1)));
        assertReason("PROFILE_SUCCESSOR_DIAGNOSTIC_NORMALIZATION_MISMATCH", () ->
                preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                        withNormalization(exact,
                                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                                        + ":" + "f".repeat(64)),
                        manifest, effectiveAt.plusSeconds(1)));
    }

    @Test
    void v50FreshManifestAndJ1BindTheCanonicalizerNormalizationIdentity() {
        var manifest = ProfileSuccessorDiagnosticManifest.createForProfile(
                ProfileRunBudgetPolicy.IMAGE_ONLY_V50_PROFILE_ID,
                UUID.fromString("82f1d86b-065b-4357-924e-19945daf1077"),
                "62f333aee7096f09d6d04dea004641e8b0a9c425ee133d09a563594d81200691",
                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                        + ":146c27620edad71fd40618772c3c1fc8613684d83b91bf20edc5d944b7a4b8b4",
                DIAGNOSTIC_CASE,
                Instant.parse("2026-08-18T04:45:29Z")
        );
        assertEquals(ProfileSuccessorDiagnosticManifest.VERSION
                        + ":4715941eb4cfe8ae6d44e8943a8ec2592ad290f044f3b05ea362ec5afb6ac76e",
                manifest.manifestIdentity());
        var effectiveAt = Instant.parse("2026-08-18T04:50:00Z");
        var exact = authorization(
                manifest, 5, 100_000, 3_000_000,
                effectiveAt, effectiveAt.plusSeconds(2 * 60 * 60));
        var preflight = new ImageOnlyCertificationPreflight();

        assertFalse(preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                exact, manifest, effectiveAt.plusSeconds(1)).grantsProviderEgress());
        assertReason("PROFILE_SUCCESSOR_DIAGNOSTIC_NORMALIZATION_MISMATCH", () ->
                preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                        withNormalization(exact, null), manifest, effectiveAt.plusSeconds(1)));
        assertReason("PROFILE_SUCCESSOR_DIAGNOSTIC_NORMALIZATION_MISMATCH", () ->
                preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                        withNormalization(exact,
                                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                                        + ":" + "f".repeat(64)),
                        manifest, effectiveAt.plusSeconds(1)));
    }

    @Test
    void v51FreshManifestAndJ1ReplayTheParentContainmentProvenanceBinding() {
        var manifest = ProfileSuccessorDiagnosticManifest.createForProfile(
                ProfileRunBudgetPolicy.IMAGE_ONLY_V51_PROFILE_ID,
                UUID.fromString("7d929b74-47ca-40a7-bfd5-061e070c2bd2"),
                "972001414977a7cc788def6e8e106b2c7f146a306d1fa328d48ff053d472d3bd",
                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                        + ":632c601ccdcbd561fcb9502777a888712a564a81352f9f19b163b4a0e9a6b4cc",
                DIAGNOSTIC_CASE,
                Instant.parse("2026-08-18T06:23:51Z")
        );
        assertEquals(ProfileSuccessorDiagnosticManifest.VERSION
                        + ":e2a01f1d788c52bcf87838a242201a32d1b28dec741640abd6b6a2be8d690925",
                manifest.manifestIdentity());
        assertEquals(ProfileSuccessorDiagnosticManifest.EVALUATOR_VERSION
                        + ":b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e",
                manifest.evaluatorIdentity());
        var effectiveAt = Instant.parse("2026-08-18T06:30:00Z");
        var exact = authorization(
                manifest, 5, 100_000, 3_000_000,
                effectiveAt, effectiveAt.plusSeconds(2 * 60 * 60));
        var preflight = new ImageOnlyCertificationPreflight();

        assertFalse(preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                exact, manifest, effectiveAt.plusSeconds(1)).grantsProviderEgress());
        assertReason("PROFILE_SUCCESSOR_DIAGNOSTIC_NORMALIZATION_MISMATCH", () ->
                preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                        withNormalization(exact, null), manifest, effectiveAt.plusSeconds(1)));
        assertReason("PROFILE_SUCCESSOR_DIAGNOSTIC_NORMALIZATION_MISMATCH", () ->
                preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                        withNormalization(exact,
                                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                                        + ":" + "f".repeat(64)),
                        manifest, effectiveAt.plusSeconds(1)));
    }

    @Test
    void v52FreshManifestAndJ1BindTheItemParentEnvelopeSuccessor() {
        var manifest = ProfileSuccessorDiagnosticManifest.createForProfile(
                ProfileRunBudgetPolicy.IMAGE_ONLY_V52_PROFILE_ID,
                UUID.fromString("981d7262-d802-45bb-96ce-d34b4468f9f9"),
                "d8014b605dfa01a5aa1e6062696c61eb896da9e146b2a6ab3c5dae3ca9957332",
                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                        + ":e0e505c515ff3c7c7bac57e0ddc19e714721e301fd2216830bc6ac82f98cae35",
                DIAGNOSTIC_CASE,
                Instant.parse("2026-08-18T07:17:59Z")
        );
        assertEquals(ProfileSuccessorDiagnosticManifest.VERSION
                        + ":4a81d0718abb9b8db3e95052a4c268767c9ac01ce9ed90f117894dc1aed63d20",
                manifest.manifestIdentity());
        assertEquals(ProfileSuccessorDiagnosticManifest.EVALUATOR_VERSION
                        + ":b2167261ae9d1e3775c91d06d90c57c47c16284d11b685e81aa5073de655f37e",
                manifest.evaluatorIdentity());
        var effectiveAt = Instant.parse("2026-08-18T07:20:00Z");
        var exact = authorization(
                manifest, 5, 100_000, 3_000_000,
                effectiveAt, effectiveAt.plusSeconds(2 * 60 * 60));
        var preflight = new ImageOnlyCertificationPreflight();

        assertFalse(preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                exact, manifest, effectiveAt.plusSeconds(1)).grantsProviderEgress());
        assertReason("PROFILE_SUCCESSOR_DIAGNOSTIC_NORMALIZATION_MISMATCH", () ->
                preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                        withNormalization(exact, null), manifest, effectiveAt.plusSeconds(1)));
        assertReason("PROFILE_SUCCESSOR_DIAGNOSTIC_NORMALIZATION_MISMATCH", () ->
                preflight.requireProfileSuccessorDiagnosticProviderZeroProof(
                        withNormalization(exact,
                                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                                        + ":" + "f".repeat(64)),
                        manifest, effectiveAt.plusSeconds(1)));
    }

    @Test
    void v51PaidDiagnosticIsClosedAndCannotGrantProviderEgressAgain() throws Exception {
        var repository = repositoryRoot();
        var authorization = new ImageOnlyCertificationAuthorizationJsonCodec().read(
                Files.readAllBytes(repository.resolve(
                        "plans/live-canary-authorizations/"
                                + "20260818-image-only-v51-diagnostic-7d929b74.json")));
        var manifest = ProfileSuccessorDiagnosticManifest.createForProfile(
                authorization.profileId(), authorization.cycleId(), authorization.profileSha256(),
                authorization.normalizationIdentity(), authorization.cases().getFirst(),
                Instant.parse("2026-08-18T06:23:51Z"));

        assertEquals(AuthorizationStatus.CLOSED, authorization.status());
        assertEquals(ProfileRunBudgetPolicy.IMAGE_ONLY_V51_PROFILE_ID,
                authorization.profileId());
        assertEquals(ProfileSuccessorDiagnosticManifest.VERSION
                        + ":e2a01f1d788c52bcf87838a242201a32d1b28dec741640abd6b6a2be8d690925",
                authorization.manifestIdentity());
        assertEquals(ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                        + ":632c601ccdcbd561fcb9502777a888712a564a81352f9f19b163b4a0e9a6b4cc",
                authorization.normalizationIdentity());
        assertEquals(manifest.manifestIdentity(), authorization.manifestIdentity());
        assertEquals(manifest.evaluatorIdentity(), authorization.evaluatorIdentity());
        assertEquals(1, authorization.maximumRuns());
        assertEquals(5, authorization.maximumProviderCalls());
        assertEquals(100_000, authorization.maximumModelTokens());
        assertEquals(3_000_000, authorization.maximumCostMicrosCny());
        assertEquals(Instant.parse("2026-08-18T06:40:42.959996Z"),
                authorization.closedAt());
        assertEquals("PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_"
                        + "VISUAL_GROUNDING_PARENT_CONTAINMENT_CLASSIFIED",
                authorization.closureReason());
        assertReason("CERTIFICATION_AUTHORIZATION_NOT_OPEN", () ->
                new ImageOnlyCertificationPreflight()
                        .requireProfileSuccessorDiagnosticProviderZeroProof(
                                authorization, manifest,
                                authorization.closedAt().plusSeconds(1)));
    }

    @Test
    void paidDiagnosticFailureIsClosedPayloadFreeAndCannotBeReopened() throws Exception {
        var repository = repositoryRoot();
        var authorizationBytes = Files.readAllBytes(repository.resolve(
                "plans/live-canary-authorizations/"
                        + "20260817-image-only-v47-diagnostic-4ae94545.json"));
        var authorization = new ImageOnlyCertificationAuthorizationJsonCodec()
                .read(authorizationBytes);
        assertEquals(AuthorizationStatus.CLOSED, authorization.status());
        assertEquals(CertificationStage.PROFILE_SUCCESSOR_DIAGNOSTIC_1,
                authorization.stage());
        assertEquals(null, authorization.normalizationIdentity());
        assertEquals(1, authorization.maximumRuns());
        assertEquals(5, authorization.maximumProviderCalls());
        assertEquals(100_000, authorization.maximumModelTokens());
        assertEquals(3_000_000, authorization.maximumCostMicrosCny());
        assertEquals(Instant.parse("2026-08-17T13:03:07.691859Z"),
                authorization.closedAt());
        assertEquals(
                "PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_VISUAL_GROUNDING_REGION_INVALID",
                authorization.closureReason());

        var terminalBytes = Files.readAllBytes(repository.resolve(
                "plans/image-only-profile-successor-diagnostics/"
                        + "4ae94545-2c95-41dc-934e-1661aeb6c121-terminal.json"));
        var terminal = new ObjectMapper().readTree(terminalBytes);
        assertEquals("FAILED", terminal.path("result").asText());
        assertEquals("TERMINAL_CLOSED", terminal.path("lifecycle").asText());
        assertFalse(terminal.path("scoring").asBoolean(true));
        assertEquals("VISUAL_GROUNDING_REGION_INVALID",
                terminal.path("terminalReason").asText());
        assertEquals(3, terminal.path("providerCalls").asInt());
        assertEquals(39_665, terminal.path("modelTokens").asLong());
        assertEquals(661_812, terminal.path("costMicrosCny").asLong());
        assertEquals(0, terminal.path("unsettledReservations").asInt());
        assertEquals(3, terminal.path("equivalentRejectBreaker")
                .path("countedRejectedAttempts").asInt());
        assertFalse(terminal.path("equivalentRejectBreaker")
                .path("fourthReservationIssued").asBoolean(true));
        assertFalse(terminal.path("diagnosticPassed").asBoolean(true));
        assertEquals(0, terminal.path("certificationCredit").asInt());
        assertFalse(terminal.path("nextStageUnlocked").asBoolean(true));
        assertFalse(terminal.path("automaticRerunAllowed").asBoolean(true));
        assertFalse(terminal.path("candidateApplied").asBoolean(true));
        assertFalse(terminal.path("staticSchemaPublished").asBoolean(true));
        assertPayloadFree(authorizationBytes);
        assertPayloadFree(terminalBytes);

        var prepared = ProfileSuccessorDiagnosticManifest.create(
                authorization.cycleId(), authorization.profileSha256(),
                ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                        + ":b3074928fc15b37ba6ccb0900fde549dd2733db33d688b559c66320546fd5e04",
                authorization.cases().getFirst(),
                Instant.parse("2026-08-17T12:34:00Z"));
        assertReason("CERTIFICATION_AUTHORIZATION_NOT_OPEN", () ->
                new ImageOnlyCertificationPreflight()
                        .requireProfileSuccessorDiagnosticProviderZeroProof(
                                authorization, prepared, authorization.closedAt().plusSeconds(1)));
    }

    @Test
    void v49PaidDiagnosticIsClosedWithExactNormalizationAndImmutableNegativeTerminal()
            throws Exception {
        var repository = repositoryRoot();
        var authorizationBytes = Files.readAllBytes(repository.resolve(
                "plans/live-canary-authorizations/"
                        + "20260818-image-only-v49-diagnostic-432fdfeb.json"));
        var authorization = new ImageOnlyCertificationAuthorizationJsonCodec()
                .read(authorizationBytes);
        assertEquals(AuthorizationStatus.CLOSED, authorization.status());
        assertEquals(ProfileRunBudgetPolicy.IMAGE_ONLY_V49_PROFILE_ID,
                authorization.profileId());
        assertEquals(ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                        + ":3096deba42aeab03be175074e6717ccf6898d4a628950d19eaa6891674d62375",
                authorization.normalizationIdentity());
        assertEquals(5, authorization.maximumProviderCalls());
        assertEquals(100_000, authorization.maximumModelTokens());
        assertEquals(3_000_000, authorization.maximumCostMicrosCny());
        assertEquals("PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_"
                        + "VISUAL_GROUNDING_REGION_FIELDS_INVALID",
                authorization.closureReason());

        var terminalBytes = Files.readAllBytes(repository.resolve(
                "plans/image-only-profile-successor-diagnostics/"
                        + "432fdfeb-c5ab-4cff-92f4-e066a0d98c8c-terminal.json"));
        var terminal = new ObjectMapper().readTree(terminalBytes);
        assertEquals("FAILED", terminal.path("result").asText());
        assertEquals("TERMINAL_CLOSED", terminal.path("lifecycle").asText());
        assertEquals("VISUAL_GROUNDING_REGION_FIELDS_INVALID",
                terminal.path("terminalReason").asText());
        assertEquals(5, terminal.path("providerCalls").asInt());
        assertEquals(67_373, terminal.path("modelTokens").asLong());
        assertEquals(1_086_900, terminal.path("costMicrosCny").asLong());
        assertEquals(0, terminal.path("unsettledReservations").asInt());
        assertEquals(3, terminal.path("equivalentRejectBreaker")
                .path("countedEquivalentRejectedAttempts").asInt());
        assertFalse(terminal.path("equivalentRejectBreaker")
                .path("nextReservationIssued").asBoolean(true));
        assertFalse(terminal.path("hardCap")
                .path("sixthReservationIssued").asBoolean(true));
        assertFalse(terminal.path("diagnosticPassed").asBoolean(true));
        assertEquals(0, terminal.path("certificationCredit").asInt());
        assertFalse(terminal.path("nextStageUnlocked").asBoolean(true));
        assertFalse(terminal.path("automaticRerunAllowed").asBoolean(true));
        assertFalse(terminal.path("reviewPackCreated").asBoolean(true));
        assertFalse(terminal.path("candidateApplied").asBoolean(true));
        assertFalse(terminal.path("staticSchemaPublished").asBoolean(true));
        assertFalse(terminal.path("productionDeployed").asBoolean(true));
        assertPayloadFree(authorizationBytes);
        assertPayloadFree(terminalBytes);
    }

    @Test
    void v50PaidDiagnosticIsClosedAfterTheThreeEquivalentContainmentRejects()
            throws Exception {
        var repository = repositoryRoot();
        var authorizationBytes = Files.readAllBytes(repository.resolve(
                "plans/live-canary-authorizations/"
                        + "20260818-image-only-v50-diagnostic-82f1d86b.json"));
        var authorization = new ImageOnlyCertificationAuthorizationJsonCodec()
                .read(authorizationBytes);
        assertEquals(AuthorizationStatus.CLOSED, authorization.status());
        assertEquals(ProfileRunBudgetPolicy.IMAGE_ONLY_V50_PROFILE_ID,
                authorization.profileId());
        assertEquals(ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION
                        + ":146c27620edad71fd40618772c3c1fc8613684d83b91bf20edc5d944b7a4b8b4",
                authorization.normalizationIdentity());
        assertEquals("PROFILE_SUCCESSOR_DIAGNOSTIC_FAILED_"
                        + "VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID",
                authorization.closureReason());

        var terminalBytes = Files.readAllBytes(repository.resolve(
                "plans/image-only-profile-successor-diagnostics/"
                        + "82f1d86b-065b-4357-924e-19945daf1077-terminal.json"));
        var terminal = new ObjectMapper().readTree(terminalBytes);
        assertEquals("FAILED", terminal.path("result").asText());
        assertEquals("TERMINAL_CLOSED", terminal.path("lifecycle").asText());
        assertEquals("VISUAL_GROUNDING_PARENT_CONTAINMENT_INVALID",
                terminal.path("terminalReason").asText());
        assertEquals(3, terminal.path("providerCalls").asInt());
        assertEquals(40_400, terminal.path("modelTokens").asLong());
        assertEquals(645_000, terminal.path("costMicrosCny").asLong());
        assertEquals(0, terminal.path("unsettledReservations").asInt());
        assertEquals(3, terminal.path("equivalentRejectBreaker")
                .path("countedEquivalentRejectedAttempts").asInt());
        assertFalse(terminal.path("equivalentRejectBreaker")
                .path("nextReservationIssued").asBoolean(true));
        assertFalse(terminal.path("hardCap")
                .path("fourthReservationIssued").asBoolean(true));
        assertFalse(terminal.path("diagnosticPassed").asBoolean(true));
        assertEquals(0, terminal.path("certificationCredit").asInt());
        assertFalse(terminal.path("nextStageUnlocked").asBoolean(true));
        assertFalse(terminal.path("automaticRerunAllowed").asBoolean(true));
        assertFalse(terminal.path("reviewPackCreated").asBoolean(true));
        assertFalse(terminal.path("candidateApplied").asBoolean(true));
        assertFalse(terminal.path("staticSchemaPublished").asBoolean(true));
        assertFalse(terminal.path("productionDeployed").asBoolean(true));
        assertPayloadFree(authorizationBytes);
        assertPayloadFree(terminalBytes);
    }

    private static CertificationCaseVerdict verdict(
            CertificationTerminalState state,
            boolean manuallyAccepted
    ) {
        return new CertificationCaseVerdict(
                DIAGNOSTIC_CASE.caseId(), state, manuallyAccepted, 9_000,
                List.of("route_name")
        );
    }

    private static ProfileSuccessorDiagnosticManifest manifest() {
        return ProfileSuccessorDiagnosticManifest.create(
                CYCLE_ID, PROFILE_SHA, normalizationIdentity(), DIAGNOSTIC_CASE, T0
        );
    }

    private static String normalizationIdentity() {
        return ProfileSuccessorDiagnosticManifest.NORMALIZATION_VERSION + ":" + "a".repeat(64);
    }

    private static ImageOnlyCertificationAuthorization authorization(
            ProfileSuccessorDiagnosticManifest manifest,
            int maximumCalls,
            long maximumTokens,
            long maximumCost,
            Instant effectiveAt,
            Instant expiresAt
    ) {
        return new ImageOnlyCertificationAuthorization(
                ImageOnlyCertificationAuthorization.VERSION,
                "iopa-v47-diagnostic-test", AuthorizationStatus.OPEN,
                manifest.cycleId(), CertificationStage.PROFILE_SUCCESSOR_DIAGNOSTIC_1,
                manifest.profileId(), manifest.profileSha256(),
                manifest.manifestIdentity(), manifest.evaluatorIdentity(),
                manifest.normalizationIdentity(),
                "DASHSCOPE", "qwen3.8-max",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                manifest.inputProvenance(), manifest.dataClassification(),
                List.of(manifest.diagnosticCase()),
                1, maximumCalls, maximumTokens, maximumCost,
                5, 3_000_000L,
                effectiveAt, expiresAt, "owner:renderweave", effectiveAt,
                "IMAGE_ONLY_PROFILE_SUCCESSOR_DIAGNOSTIC_1", null, null
        );
    }

    private static ImageOnlyCertificationAuthorization withNormalization(
            ImageOnlyCertificationAuthorization source,
            String normalizationIdentity
    ) {
        return new ImageOnlyCertificationAuthorization(
                source.version(), source.authorizationId(), source.status(), source.cycleId(),
                source.stage(), source.profileId(), source.profileSha256(), source.manifestIdentity(),
                source.evaluatorIdentity(), normalizationIdentity, source.provider(), source.model(),
                source.providerBaseUrl(), source.inputProvenance(), source.dataClassification(),
                source.cases(), source.maximumRuns(), source.maximumProviderCalls(),
                source.maximumModelTokens(), source.maximumCostMicrosCny(),
                source.maximumProviderCallsPerRun(), source.maximumCostPerRunMicrosCny(),
                source.effectiveAt(), source.expiresAt(), source.approvedBy(), source.approvedAt(),
                source.approvalScope(), source.closedAt(), source.closureReason());
    }

    private static void assertReason(String expected, Runnable action) {
        var failure = assertThrows(CertificationAuthorizationViolation.class, action::run);
        assertEquals(expected, failure.reasonCode());
    }

    private static void assertPayloadFree(byte[] bytes) {
        var text = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(text.contains("F:\\"));
        assertFalse(text.contains("data:image"));
        assertFalse(text.contains("providerRequest"));
        assertFalse(text.contains("providerResponse"));
        assertFalse(text.contains("modelOutput"));
        assertFalse(text.contains("candidateJson"));
        assertFalse(text.contains("rootDocument"));
    }

    private static Path repositoryRoot() {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return Files.isDirectory(current.resolve("plans"))
                ? current : current.getParent();
    }
}
