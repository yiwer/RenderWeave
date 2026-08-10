package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.VisualStageCorpus;
import cn.hbads.renderweave.inference.eval.visual.VisualStageReporter;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.provider.ProviderImage;
import cn.hbads.renderweave.inference.provider.ProviderInferenceRequest;
import cn.hbads.renderweave.inference.provider.ProviderUsage;
import cn.hbads.renderweave.inference.run.InferenceStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualEvaluationEvidenceVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");
    private static final String PROFILE_ID = "dashscope-qwen37-plus-product-v4";
    private final VisualStageCorpus corpus = new VisualStageCorpus();
    private final InferenceProfileRegistry.ProfileResource profile =
            new InferenceProfileRegistry().require(PROFILE_ID);

    @Test
    void independentVerifierRecognizesExactGroundingAndHybridProfileSnapshots() throws Exception {
        var registry = new InferenceProfileRegistry();
        for (var profileId : List.of(
                "dashscope-qwen37-plus-product-v4",
                "dashscope-qwen37-plus-product-v6-generic",
                "dashscope-qwen37-plus-product-v7-hybrid-generic",
                "dashscope-qwen37-flash-20260715-product-v13-generic",
                "dashscope-qwen37-plus-product-v16-generic",
                "dashscope-qwen37-plus-product-v17-generic",
                "dashscope-qwen37-plus-product-v18-generic"
        )) {
            var resource = registry.require(profileId);
            var profilePath = repositoryRoot().resolve(
                    "renderweave-inference/src/main/resources/inference-profiles/"
                            + profileId + ".json"
            );
            var process = validateProfile(
                    profilePath, profileId, resource.profile().model(), sha256(resource.snapshotJson())
            );
            assertEquals(0, process.exitCode(), process.stderr() + process.stdout());
        }
    }

    @Test
    void independentVerifierRecomputesEvidenceAndRejectsTampering(@TempDir Path directory)
            throws Exception {
        var json = JsonMapper.builder().build();
        var repository = directory.resolve("repository");
        var corpusFile = repository.resolve(
                "renderweave-inference/src/main/resources/visual-eval/v1/scenes.json");
        var profileFile = repository.resolve(
                "renderweave-inference/src/main/resources/inference-profiles/"
                        + "dashscope-qwen37-plus-product-v4.json");
        Files.createDirectories(corpusFile.getParent());
        Files.createDirectories(profileFile.getParent());
        Files.copy(repositoryRoot().resolve(
                "renderweave-inference/src/main/resources/visual-eval/v1/scenes.json"), corpusFile);
        Files.copy(repositoryRoot().resolve(
                "renderweave-inference/src/main/resources/inference-profiles/"
                        + "dashscope-qwen37-plus-product-v4.json"), profileFile);
        var maxLedger = repository.resolve(".sdlc/live/visual-evaluation-qwen38-max.json");
        var authorizationFile = repository.resolve(".sdlc/live/visual-evaluation-qwen37-plus.json");
        var flashLedger = repository.resolve(".sdlc/live/visual-evaluation-qwen37-flash.json");
        Files.createDirectories(maxLedger.getParent());
        Files.writeString(maxLedger, "{}\n");
        Files.writeString(authorizationFile, "{}\n");
        Files.writeString(flashLedger, "{}\n");
        git(repository, "init");
        git(repository, "config", "user.email", "visual-verifier@example.test");
        git(repository, "config", "user.name", "Visual Verifier Test");
        git(repository, "add", ".");
        git(repository, "commit", "-m", "freeze inputs");
        var identity = new VisualEvaluationIdentity(
                repository, List.of(maxLedger, authorizationFile, flashLedger)
        ).current();
        var authorization = authorization(identity);
        Files.writeString(authorizationFile,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(authorization));
        git(repository, "add", ".sdlc/live/visual-evaluation-qwen37-plus.json");
        git(repository, "commit", "-m", "open selected ledger");
        assertEquals(identity, new VisualEvaluationIdentity(
                repository, List.of(maxLedger, authorizationFile, flashLedger)
        ).current());
        var evidence = directory.resolve("evidence");
        var goalDirectory = evidence.resolve("goal");
        var journalDirectory = evidence.resolve("journal");
        var budget = new VisualEvaluationGoalBudget(goalDirectory, json, NOW);
        var journal = new VisualEvaluationJournal(journalDirectory, authorization, corpus, json, NOW);
        var gold = corpus.require(authorization.caseIds().getFirst());
        var runId = UUID.randomUUID();
        try (var ignored = journal.acquireBatchLease(NOW)) {
            var executionId = journal.beginAssignment(gold.caseId(), NOW);
            var assignment = PROFILE_ID + "|" + gold.caseId();
            journal.bindRun(assignment, executionId, runId, NOW);
            var reservation = budget.reserve(authorization, request(runId), NOW);
            budget.settle(UUID.fromString(reservation.reservationId()), new ProviderUsage(120, 80),
                    1_000, NOW.plusSeconds(1));
            journal.completeCase(
                    assignment, executionId, runId,
                    VisualEvaluationJournalTest.exactResult(gold, 1),
                    List.of(new VisualEvaluationJournal.AttemptResult(
                            reservation.reservationId(), 0, "OBSERVE", "LIVE_OUTPUT_ACCEPTED",
                            "qwen3.7-plus", 120L, 80L, 1_000L, 200,
                            Map.of("LIVE_OUTPUT_ACCEPTED", 1)
                    )), budget, NOW.plusSeconds(2)
            );
        }
        var reportFile = directory.resolve("report.json");
        var report = new VisualStageReporter().report(corpus, journal.completedResults());
        var originalReport = json.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Files.writeString(reportFile, originalReport);

        var pass = verify(repository, corpusFile, profileFile,
                List.of(maxLedger, authorizationFile, flashLedger), authorizationFile,
                journalDirectory, goalDirectory, reportFile, false);
        assertEquals(0, pass.exitCode(), pass.stderr() + pass.stdout()
                + " expectedProfile=" + authorization.profileSnapshotSha256());
        assertTrue(pass.stdout().contains("\"result\":\"PASS\""));

        var goalGuardFile = goalDirectory.resolve("goal-budget.guard.json");
        var currentGoalGuard = Files.readString(goalGuardFile, StandardCharsets.UTF_8);
        Files.writeString(goalGuardFile,
                json.writeValueAsString(VisualEvaluationGoalBudget.Guard.legacy()),
                StandardCharsets.UTF_8);
        var legacyGuardPass = verify(repository, corpusFile, profileFile,
                List.of(maxLedger, authorizationFile, flashLedger), authorizationFile,
                journalDirectory, goalDirectory, reportFile, false);
        assertEquals(0, legacyGuardPass.exitCode(),
                legacyGuardPass.stderr() + legacyGuardPass.stdout());
        Files.writeString(goalGuardFile, currentGoalGuard, StandardCharsets.UTF_8);

        Files.writeString(reportFile, originalReport.replaceFirst(
                "\\\"providerCalls\\\"\\s*:\\s*1", "\"providerCalls\" : 2"));
        assertNotEquals(0, verify(repository, corpusFile, profileFile,
                List.of(maxLedger, authorizationFile, flashLedger), authorizationFile,
                journalDirectory, goalDirectory, reportFile, false).exitCode());

        Files.writeString(reportFile, originalReport);
        var goalFile = goalDirectory.resolve("goal-budget.json");
        var originalGoal = Files.readString(goalFile);
        Files.writeString(goalFile, originalGoal.replaceFirst(
                "(\\\"actualInputTokens\\\"\\s*:\\s*)120", "$1\"120\""));
        assertNotEquals(0, verify(repository, corpusFile, profileFile,
                List.of(maxLedger, authorizationFile, flashLedger), authorizationFile,
                journalDirectory, goalDirectory, reportFile, false).exitCode());
        Files.writeString(goalFile, originalGoal);

        Files.writeString(reportFile, originalReport.replaceFirst(
                "\\{", "{\"reportVersion\":\"duplicate\","));
        assertNotEquals(0, verify(repository, corpusFile, profileFile,
                List.of(maxLedger, authorizationFile, flashLedger), authorizationFile,
                journalDirectory, goalDirectory, reportFile, false).exitCode());
        Files.writeString(reportFile, originalReport);

        assertNotEquals(0, verify(repository, corpusFile, profileFile,
                List.of(maxLedger, authorizationFile, flashLedger), authorizationFile,
                journalDirectory, goalDirectory, reportFile, true).exitCode());
    }

    private VerificationProcess verify(
            Path repository,
            Path corpusFile,
            Path profileFile,
            List<Path> excludedLedgers,
            Path authorization,
            Path journal,
            Path goal,
            Path report,
            boolean requireComplete
    ) throws Exception {
        var root = repositoryRoot();
        var command = new ArrayList<>(List.of(
                "python", root.resolve("tools/verify_visual_eval_evidence.py").toString(),
                "--corpus", corpusFile.toString(),
                "--profile", profileFile.toString(),
                "--repository-root", repository.toString(),
                "--authorization", authorization.toString(),
                "--journal", journal.resolve("state.json").toString(),
                "--journal-guard", journal.resolve("state.guard.json").toString(),
                "--goal-budget", goal.resolve("goal-budget.json").toString(),
                "--goal-guard", goal.resolve("goal-budget.guard.json").toString(),
                "--report", report.toString()
        ));
        for (var ledger : excludedLedgers) {
            command.add("--excluded-authorization");
            command.add(ledger.toString());
        }
        if (requireComplete) command.add("--require-complete");
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(false);
        List.of("DASHSCOPE_API_KEY", "DASHSCOPE_API_KEY_FILE", "RENDERWEAVE_RUN_LIVE_CANARY",
                "RENDERWEAVE_RUN_LIVE_CERTIFICATION", "RENDERWEAVE_RUN_VISUAL_EVALUATION")
                .forEach(key -> builder.environment().remove(key));
        builder.environment().put("RENDERWEAVE_LIVE_AI_ENABLED", "false");
        builder.environment().put("RENDERWEAVE_LIVE_UPLOAD_ENABLED", "false");
        var process = builder.start();
        var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new VerificationProcess(process.waitFor(), stdout, stderr);
    }

    private VerificationProcess validateProfile(
            Path profilePath,
            String profileId,
            String model,
            String snapshotSha256
    ) throws Exception {
        var script = "import sys; from pathlib import Path; "
                + "from tools.verify_visual_eval_evidence import validate_profile; "
                + "validate_profile(Path(sys.argv[1]), {"
                + "'profileId': sys.argv[2], 'model': sys.argv[3], "
                + "'profileSnapshotSha256': sys.argv[4]})";
        var builder = new ProcessBuilder(
                "python", "-c", script, profilePath.toString(), profileId, model, snapshotSha256
        ).directory(repositoryRoot().toFile()).redirectErrorStream(false);
        List.of("DASHSCOPE_API_KEY", "DASHSCOPE_API_KEY_FILE", "RENDERWEAVE_RUN_LIVE_CANARY",
                "RENDERWEAVE_RUN_LIVE_CERTIFICATION", "RENDERWEAVE_RUN_VISUAL_EVALUATION")
                .forEach(key -> builder.environment().remove(key));
        builder.environment().put("RENDERWEAVE_LIVE_AI_ENABLED", "false");
        builder.environment().put("RENDERWEAVE_LIVE_UPLOAD_ENABLED", "false");
        var process = builder.start();
        var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new VerificationProcess(process.waitFor(), stdout, stderr);
    }

    private VisualEvaluationAuthorization authorization(String evaluationIdentity) {
        return new VisualEvaluationAuthorization(
                VisualEvaluationAuthorization.VERSION, "visual-verifier", "OPEN", "BASELINE",
                VisualEvaluationAuthorization.INPUT_CLASSIFICATION, VisualStageCorpus.VERSION,
                corpus.sourceSha256(), evaluationIdentity,
                PROFILE_ID, sha256(profile.snapshotJson()), "qwen3.7-plus",
                List.of(corpus.cases().getFirst().caseId()), 8, 500_000, 4_000_000, 1,
                "yiwer", NOW.minusSeconds(60).toString(), NOW.plusSeconds(43_200).toString(),
                "Repository synthetic visual evaluation"
        );
    }

    private ProviderInferenceRequest request(UUID runId) {
        return new ProviderInferenceRequest(
                runId, 0, InferenceStage.OBSERVE, profile.profile(),
                "Return one bounded JSON object.", "{}",
                List.of(new ProviderImage("c".repeat(64), "image/png", new byte[]{1}))
        );
    }

    private static Path repositoryRoot() {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(
                current.resolve("tools/verify_visual_eval_evidence.py"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Repository root is unavailable");
        return current;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void git(Path repository, String... arguments) throws Exception {
        var command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        var builder = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true);
        List.of("DASHSCOPE_API_KEY", "DASHSCOPE_API_KEY_FILE", "RENDERWEAVE_RUN_LIVE_CANARY",
                "RENDERWEAVE_RUN_LIVE_CERTIFICATION", "RENDERWEAVE_RUN_VISUAL_EVALUATION")
                .forEach(key -> builder.environment().remove(key));
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
    }

    private record VerificationProcess(int exitCode, String stdout, String stderr) { }
}
