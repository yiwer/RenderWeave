package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateReviewService;
import cn.hbads.renderweave.inference.candidate.CandidateReviewSnapshot;
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.replay.ReplayInferenceWorker;
import cn.hbads.renderweave.inference.run.InferenceRunService;
import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inference-runs")
final class InferenceController {
    private final InferenceRunService runService;
    private final InferenceRunStore runStore;
    private final InferenceReplayStore replayStore;
    private final ReplayInferenceWorker worker;
    private final CandidateReviewService reviews;
    private final ReplayFixtureInputFactory fixtureInputs;
    private final BlobStore blobStore;
    private final ObjectMapper json;
    private final CandidateJsonCodec candidateCodec = new CandidateJsonCodec();
    private final InferenceProfileRegistry profiles = new InferenceProfileRegistry();

    InferenceController(
            InferenceRunService runService,
            InferenceRunStore runStore,
            InferenceReplayStore replayStore,
            ReplayInferenceWorker worker,
            CandidateReviewService reviews,
            ReplayFixtureInputFactory fixtureInputs,
            BlobStore blobStore,
            ObjectMapper json
    ) {
        this.runService = runService;
        this.runStore = runStore;
        this.replayStore = replayStore;
        this.worker = worker;
        this.reviews = reviews;
        this.fixtureInputs = fixtureInputs;
        this.blobStore = blobStore;
        this.json = json;
    }

    @GetMapping("/replay-fixtures")
    ReplayFixtureListResponse fixtures() {
        var items = fixtureInputs.fixtures().stream()
                .map(fixture -> new ReplayFixtureResponse(
                        fixture.fixtureId(), fixture.mode().name(), fixture.scenario(),
                        fixture.imageCount(), fixture.jsonSamples().size(),
                        fixture.expectedSchemaCount(), fixture.expectedProblemCodes()
                ))
                .toList();
        return new ReplayFixtureListResponse(
                "replay-v1", "REPLAY", false, "REPLAY_ONLY", items
        );
    }

    @PostMapping
    ResponseEntity<InferenceRunResponse> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateReplayRunRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidInferenceApiRequestException("Idempotency-Key is required");
        }
        if (request.fixtureId() == null || request.fixtureId().isBlank()) {
            throw new InvalidInferenceApiRequestException("fixtureId is required");
        }
        if (!Boolean.TRUE.equals(request.externalTransferConfirmed())) {
            throw new InvalidInferenceApiRequestException("Explicit input-scope confirmation is required");
        }
        final cn.hbads.renderweave.inference.input.InferenceInput input;
        try {
            input = fixtureInputs.create(request.fixtureId(), true);
        } catch (IllegalArgumentException exception) {
            throw new InvalidInferenceApiRequestException("Unknown replay fixture", exception);
        }
        var profile = profiles.require(input.profileId());
        var created = runService.create(idempotencyKey, input, profile.snapshotJson());
        var run = created.run();
        if (created.created()) {
            run = worker.process(run.runId(), "http-replay-" + run.runId()).orElse(run);
        }
        var response = toRunResponse(run);
        return ResponseEntity
                .status(created.created() ? 201 : 200)
                .location(URI.create("/api/v1/inference-runs/" + run.runId()))
                .body(response);
    }

    @GetMapping("/{runId}")
    InferenceRunResponse get(@PathVariable UUID runId) {
        return toRunResponse(runStore.find(runId)
                .orElseThrow(() -> new cn.hbads.renderweave.inference.run.InferenceRunNotFoundException(runId)));
    }

    @GetMapping("/{runId}/candidate")
    CandidateReviewResponse candidate(@PathVariable UUID runId) {
        return toReviewResponse(reviews.get(runId));
    }

    @PutMapping("/{runId}/candidate")
    CandidateReviewResponse saveCandidate(
            @PathVariable UUID runId,
            @RequestBody SaveCandidateRequest request
    ) {
        if (request.expectedCandidateRevision() == null || request.expectedCandidateRevision() < 0) {
            throw new InvalidInferenceApiRequestException(
                    "expectedCandidateRevision must be a non-negative integer"
            );
        }
        if (request.candidate() == null) {
            throw new InvalidInferenceApiRequestException("candidate is required");
        }
        return toReviewResponse(reviews.save(
                runId, request.expectedCandidateRevision(), parseCandidate(request.candidate())
        ));
    }

    @GetMapping("/{runId}/artifacts/{artifactId}")
    ResponseEntity<byte[]> image(
            @PathVariable UUID runId,
            @PathVariable String artifactId
    ) {
        var run = runStore.find(runId)
                .orElseThrow(() -> new cn.hbads.renderweave.inference.run.InferenceRunNotFoundException(runId));
        var artifact = run.inputs().stream()
                .map(input -> input.artifact())
                .filter(item -> item.kind() == NormalizedArtifact.Kind.IMAGE)
                .filter(item -> item.artifactId().equals(artifactId))
                .findFirst()
                .orElseThrow(() -> new cn.hbads.renderweave.inference.candidate.InferenceCandidateNotFoundException(runId));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(blobStore.read(artifact.locator()));
    }

    private CandidateBundle parseCandidate(JsonNode value) {
        try {
            return candidateCodec.parse(json.writeValueAsString(value));
        } catch (JacksonException exception) {
            throw new InvalidInferenceApiRequestException("candidate cannot be encoded", exception);
        }
    }

    private CandidateReviewResponse toReviewResponse(CandidateReviewSnapshot review) {
        return new CandidateReviewResponse(
                toRunResponse(review.run()),
                review.candidateRevision(),
                tree(candidateCodec.write(review.original())),
                tree(candidateCodec.write(review.current())),
                review.problems().stream().map(InferenceController::toProblem).toList(),
                review.run().inputs().stream()
                        .filter(input -> input.kind() == NormalizedArtifact.Kind.IMAGE)
                        .map(input -> new InferenceImageResponse(
                                input.artifact().artifactId(), input.ordinal(),
                                input.artifact().width(), input.artifact().height(),
                                "/api/v1/inference-runs/" + review.run().runId()
                                        + "/artifacts/" + input.artifact().artifactId()
                        ))
                        .toList(),
                fixtureInputs.fixtures().stream()
                        .filter(fixture -> fixture.fixtureId().equals(review.run().replayFixtureId()))
                        .findFirst().map(fixture -> fixture.jsonSamples().size()).orElse(0)
        );
    }

    private InferenceRunResponse toRunResponse(InferenceRunSnapshot run) {
        return new InferenceRunResponse(
                run.runId(), run.mode().name(), run.state().name(), run.stage().name(), run.sequence(),
                run.profileId(), run.replayFixtureId(), run.cancellationRequested(),
                run.failureCode().orElse(null),
                replayStore.findCandidate(run.runId()).map(snapshot -> snapshot.revision()).orElse(null),
                run.createdAt(), run.updatedAt(), run.finishedAt().orElse(null)
        );
    }

    private JsonNode tree(String value) {
        try {
            return json.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored candidate could not be rendered", exception);
        }
    }

    private static CandidateProblemResponse toProblem(CandidateProblem problem) {
        return new CandidateProblemResponse(
                problem.code(), problem.severity().name(), problem.itemId(), problem.pointer(), problem.args()
        );
    }

    record CreateReplayRunRequest(String fixtureId, Boolean externalTransferConfirmed) { }

    record SaveCandidateRequest(Long expectedCandidateRevision, JsonNode candidate) { }

    record ReplayFixtureListResponse(
            String profileId,
            String provider,
            boolean networkAllowed,
            String certification,
            List<ReplayFixtureResponse> items
    ) { }

    record ReplayFixtureResponse(
            String fixtureId,
            String mode,
            String scenario,
            int imageCount,
            int jsonSampleCount,
            int expectedSchemaCount,
            List<String> expectedProblemCodes
    ) { }

    record InferenceRunResponse(
            UUID runId,
            String mode,
            String state,
            String stage,
            long sequence,
            String profileId,
            String replayFixtureId,
            boolean cancellationRequested,
            String failureCode,
            Long candidateRevision,
            Instant createdAt,
            Instant updatedAt,
            Instant finishedAt
    ) { }

    record CandidateReviewResponse(
            InferenceRunResponse run,
            long candidateRevision,
            JsonNode original,
            JsonNode current,
            List<CandidateProblemResponse> problems,
            List<InferenceImageResponse> images,
            int jsonSampleCount
    ) { }

    record CandidateProblemResponse(
            String code,
            String severity,
            UUID itemId,
            String pointer,
            Map<String, String> args
    ) { }

    record InferenceImageResponse(
            String artifactId,
            int ordinal,
            Integer width,
            Integer height,
            String contentUrl
    ) { }
}
