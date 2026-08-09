package cn.hbads.renderweave.inference.profile;

import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateValidationContext;
import cn.hbads.renderweave.inference.candidate.CandidateValidator;
import cn.hbads.renderweave.inference.eval.LiveCandidateEvaluator;
import cn.hbads.renderweave.inference.eval.LiveEvaluationCorpus;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.StrictJsonSampleProfiler;
import cn.hbads.renderweave.inference.replay.ReplayCandidateProfiler;
import cn.hbads.renderweave.inference.replay.ReplayCase;
import cn.hbads.renderweave.inference.replay.ReplayCorpus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedPipelineEvaluationTest {
    private final ReplayCorpus replay = new ReplayCorpus();
    private final LiveEvaluationCorpus live = new LiveEvaluationCorpus();
    private final StrictJsonSampleProfiler reducer = new StrictJsonSampleProfiler();
    private final JsonStructuralProfiler structuralProfiler = new JsonStructuralProfiler();
    private final JsonCandidateProfiler jsonProfiler = new JsonCandidateProfiler();
    private final ReplayCandidateProfiler replayProfiler = new ReplayCandidateProfiler(8_000);
    private final JsonGroundedCandidateComposer composer = new JsonGroundedCandidateComposer();
    private final CandidateValidator validator = new CandidateValidator();
    private final LiveCandidateEvaluator evaluator = new LiveCandidateEvaluator();

    @Test
    void allJsonOnlyCasesAreExactWithoutAProviderCandidate() {
        var evaluated = 0;
        for (var fixture : replay.cases()) {
            if (fixture.mode() != InferenceMode.JSON_ONLY) continue;
            evaluated++;
            var runId = runId(fixture);
            var profile = jsonProfile(fixture);
            var result = jsonProfiler.inferLive(
                    runId, fixture.rootSchemaKey(), fixture.displayName(), profile
            );
            var problems = validate(result, Set.of(), profile);
            var evaluation = evaluator.evaluate(
                    live.require("live-" + fixture.fixtureId()), result.candidate(), problems
            );
            assertTrue(evaluation.passed(), () -> fixture.fixtureId() + " -> " + evaluation
                    + " problems=" + problems);
        }
        assertEquals(20, evaluated);
    }

    @Test
    void allCombinedCasesRemainExactAfterDeterministicGroundingAndVisualOverlay() {
        var evaluated = 0;
        for (var fixture : replay.cases()) {
            if (fixture.mode() != InferenceMode.COMBINED) continue;
            evaluated++;
            var runId = runId(fixture);
            var artifactIds = imageArtifactIds(fixture.imageCount());
            var profile = jsonProfile(fixture);
            var visualProposal = replayProfiler.infer(
                    runId, fixture, artifactIds, profile
            ).candidate();
            var result = composer.compose(
                    runId,
                    fixture.rootSchemaKey(),
                    fixture.displayName(),
                    profile,
                    Set.copyOf(artifactIds),
                    visualProposal,
                    8_000
            );
            var problems = validate(result, Set.copyOf(artifactIds), profile);
            var evaluation = evaluator.evaluate(
                    live.require("live-" + fixture.fixtureId()), result.candidate(), problems
            );
            assertTrue(evaluation.passed(), () -> fixture.fixtureId() + " -> " + evaluation);
        }
        assertEquals(20, evaluated);
    }

    private List<CandidateProblem> validate(
            CandidateProfileResult result,
            Set<String> imageArtifactIds,
            JsonStructuralProfile profile
    ) {
        var problems = new ArrayList<>(result.semanticProblems());
        problems.addAll(validator.validate(
                result.candidate(),
                CandidateValidationContext.liveProviderOutput(imageArtifactIds, profile, 8_000)
        ));
        return List.copyOf(problems);
    }

    private JsonStructuralProfile jsonProfile(ReplayCase fixture) {
        var inputs = fixture.jsonSamples().stream().map(sample -> new InferenceInput.BinaryInput(
                fixture.fixtureId() + ".json",
                "application/json",
                sample.getBytes(StandardCharsets.UTF_8)
        )).toList();
        return structuralProfiler.profile(reducer.profile(inputs));
    }

    private static UUID runId(ReplayCase fixture) {
        return UUID.nameUUIDFromBytes(
                ("grounded-pipeline:" + fixture.fixtureId()).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static List<String> imageArtifactIds(int count) {
        var ids = new ArrayList<String>();
        for (var index = 0; index < count; index++) {
            var bytes = new byte[32];
            bytes[31] = (byte) (index + 1);
            ids.add(HexFormat.of().formatHex(bytes));
        }
        return List.copyOf(ids);
    }
}
