package cn.hbads.renderweave.inference.eval.visual;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredLocalVisualDiffTest {
    @TempDir
    Path temporaryDirectory;

    private final LayeredVisualCorpus corpus = new LayeredVisualCorpus();

    @Test
    void exactSyntheticCorpusCaseProducesOnlyLocalDiagnosticArtifacts() throws Exception {
        var workspace = workspace("allowed-workspace");
        var evaluationCase = corpus.cases().getFirst();
        var prediction = LayeredSyntheticReplay.perfect(evaluationCase);
        var renderer = new LayeredLocalVisualDiff(workspace);

        var first = renderer.generate(corpus,
                new LayeredLocalVisualDiff.Request(corpus.corpusIdentity(), evaluationCase.caseId()),
                prediction);
        var second = renderer.generate(corpus,
                new LayeredLocalVisualDiff.Request(corpus.corpusIdentity(), evaluationCase.caseId()),
                prediction);

        assertEquals(first.receipt(), second.receipt());
        assertEquals(first.localDirectory(), second.localDirectory());
        assertTrue(first.localDirectory().startsWith(workspace.resolve(".scratch/layered-visual-diff")));
        assertFalse(first.localDirectory().startsWith(workspace.resolve(".sdlc/evidence")));
        assertTrue(Files.isRegularFile(first.localDirectory().resolve("overlay.png")));
        assertTrue(Files.isRegularFile(first.localDirectory().resolve("manifest.json")));

        var image = ImageIO.read(first.localDirectory().resolve("overlay.png").toFile());
        assertNotNull(image);
        assertEquals(evaluationCase.renderCase().width(), image.getWidth());
        assertEquals(evaluationCase.renderCase().height(), image.getHeight());

        var manifest = Files.readString(first.localDirectory().resolve("manifest.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("LOCAL_DIAGNOSTIC_ONLY"));
        assertTrue(manifest.contains("human_review_pending"));
        assertTrue(manifest.contains("\"judgement\":\"J0\""));
        assertTrue(manifest.contains("\"automatedEvidenceLevel\":\"A1\""));
        assertTrue(manifest.contains("\"providerAttempts\":0"));
        assertTrue(manifest.contains("\"providerReservations\":0"));
        assertTrue(manifest.contains("\"externalProviderCostMicrosCny\":0"));
        assertFalse(manifest.contains(workspace.toString()));

        var payloadSafe = (first.receipt().toString() + manifest).toLowerCase();
        for (var forbidden : new String[]{
                "ignore prior instructions", "ocrtext", "ocr_text", "prompt", "rootdocument",
                "root_document", "providerinput", "provideroutput", "data:image", "base64"
        }) {
            assertFalse(payloadSafe.contains(forbidden), forbidden);
        }
        assertFalse(first.receipt().toString().contains("overlay.png"));
        assertFalse(first.toString().contains(workspace.toString()));
        assertEquals("LOCAL_VISUAL_DIFF_GENERATED", first.receipt().code());
        assertEquals(1, first.receipt().generatedCount());
        assertEquals(0, first.receipt().providerAttempts());
        assertEquals(0, first.receipt().providerReservations());
        assertEquals(0, first.receipt().externalProviderCostMicrosCny());
    }

    @Test
    void allNonCorpusSelectorsAndExternalWorkspacePathsFailClosed() throws Exception {
        var workspace = workspace("strict-workspace");
        var renderer = new LayeredLocalVisualDiff(workspace);
        var evaluationCase = corpus.cases().getFirst();
        var prediction = LayeredSyntheticReplay.perfect(evaluationCase);
        var changedIdentity = corpus.corpusIdentity().substring(0, corpus.corpusIdentity().length() - 1) + "0";

        assertCode("LOCAL_DIFF_CORPUS_NOT_ALLOWLISTED", () -> renderer.generate(corpus,
                new LayeredLocalVisualDiff.Request(changedIdentity, evaluationCase.caseId()), prediction));
        assertCode("LOCAL_DIFF_CASE_NOT_ALLOWLISTED", () -> renderer.generate(corpus,
                new LayeredLocalVisualDiff.Request(corpus.corpusIdentity(), "user-artifact-123"), prediction));
        assertCode("LOCAL_DIFF_CASE_NOT_ALLOWLISTED", () -> renderer.generate(corpus,
                new LayeredLocalVisualDiff.Request(corpus.corpusIdentity(), "live-run-123"), prediction));
        assertCode("LOCAL_DIFF_CASE_ID_INVALID", () -> new LayeredLocalVisualDiff.Request(
                corpus.corpusIdentity(), "https://example.invalid/image.png"));
        assertCode("LOCAL_DIFF_CASE_ID_INVALID", () -> new LayeredLocalVisualDiff.Request(
                corpus.corpusIdentity(), "C:\\outside\\image.png"));

        var external = temporaryDirectory.resolve("external-output");
        Files.createDirectories(external);
        assertCode("LOCAL_DIFF_WORKSPACE_INVALID", () -> new LayeredLocalVisualDiff(external));
        assertCode("LOCAL_DIFF_LICENSE_NOT_ALLOWLISTED",
                () -> LayeredLocalVisualDiff.requireAllowedLicense(null));
        assertDoesNotThrow(() -> LayeredLocalVisualDiff.requireAllowedLicense(
                LayeredVisualAnnotation.SourceLicense.SYNTHETIC));
        assertDoesNotThrow(() -> LayeredLocalVisualDiff.requireAllowedLicense(
                LayeredVisualAnnotation.SourceLicense.CC0));
    }

    @Test
    void predictionIdentityAndProviderBoundariesFailBeforeAnyArtifactIsWritten() throws Exception {
        var workspace = workspace("provider-workspace");
        var renderer = new LayeredLocalVisualDiff(workspace);
        var evaluationCase = corpus.cases().getFirst();
        var exact = LayeredSyntheticReplay.perfect(evaluationCase);
        var differentCase = corpus.cases().get(1);
        var wrongCase = LayeredSyntheticReplay.perfect(differentCase);
        var paidRuntime = new LayeredVisualPrediction.Runtime(
                exact.runtime().scriptedCalls(), 1, 1, 1, 1, exact.runtime().latencyMicros(),
                exact.runtime().recoveryCode(), exact.runtime().recoveryCount(),
                exact.runtime().acceptedStageReplayCount(), 1, 1, 1);
        var paid = new LayeredVisualPrediction(
                exact.caseId(), exact.ocrLines(), exact.regions(), exact.evidence(), exact.precedenceEdges(),
                exact.repeatGroups(), exact.entities(), exact.relationships(), exact.bindings(), exact.candidate(),
                exact.confidence(), paidRuntime);
        var request = new LayeredLocalVisualDiff.Request(corpus.corpusIdentity(), evaluationCase.caseId());

        assertCode("LOCAL_DIFF_PREDICTION_CASE_MISMATCH",
                () -> renderer.generate(corpus, request, wrongCase));
        assertCode("LOCAL_DIFF_EXTERNAL_PROVIDER_USAGE",
                () -> renderer.generate(corpus, request, paid));
        assertFalse(Files.exists(workspace.resolve(".scratch/layered-visual-diff")));
    }

    private Path workspace(String name) throws Exception {
        var workspace = temporaryDirectory.resolve(name);
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("CONSTITUTION.md"), "local test workspace", StandardCharsets.UTF_8);
        return workspace.toRealPath();
    }

    private static void assertCode(String code, ThrowingAction action) {
        var failure = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals(code, failure.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
