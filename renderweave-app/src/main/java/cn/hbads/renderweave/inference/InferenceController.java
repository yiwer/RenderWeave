package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateApplyResult;
import cn.hbads.renderweave.inference.candidate.CandidateApplyService;
import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateProblem;
import cn.hbads.renderweave.inference.candidate.CandidateReviewService;
import cn.hbads.renderweave.inference.candidate.CandidateReviewSnapshot;
import cn.hbads.renderweave.inference.input.BlobStore;
import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.input.InferenceMode;
import cn.hbads.renderweave.inference.input.NormalizedArtifact;
import cn.hbads.renderweave.inference.profile.InferenceProfileRegistry;
import cn.hbads.renderweave.inference.profile.JsonStructuralProfiler;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.replay.InferenceReplayStore;
import cn.hbads.renderweave.inference.run.InferenceRunService;
import cn.hbads.renderweave.inference.run.InferenceRunSnapshot;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.io.IOException;
import java.time.Duration;
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
    private final InferenceCoordinator coordinator;
    private final InferenceProvider provider;
    private final CandidateReviewService reviews;
    private final CandidateApplyService applies;
    private final ReplayFixtureInputFactory fixtureInputs;
    private final BlobStore blobStore;
    private final ObjectMapper json;
    private final boolean liveUploadsEnabled;
    private final CandidateJsonCodec candidateCodec = new CandidateJsonCodec();
    private final InferenceProfileRegistry profiles = new InferenceProfileRegistry();
    private final JsonStructuralProfiler structuralProfiler = new JsonStructuralProfiler();
    private static final long MAXIMUM_RUN_COST_LIMIT_MICROS_CNY = 100_000_000L;

    InferenceController(
            InferenceRunService runService,
            InferenceRunStore runStore,
            InferenceReplayStore replayStore,
            InferenceCoordinator coordinator,
            InferenceProvider provider,
            CandidateReviewService reviews,
            CandidateApplyService applies,
            ReplayFixtureInputFactory fixtureInputs,
            BlobStore blobStore,
            ObjectMapper json,
            @Value("${renderweave.inference.live-upload-enabled:false}") boolean liveUploadsEnabled
    ) {
        this.runService = runService;
        this.runStore = runStore;
        this.replayStore = replayStore;
        this.coordinator = coordinator;
        this.provider = provider;
        this.reviews = reviews;
        this.applies = applies;
        this.fixtureInputs = fixtureInputs;
        this.blobStore = blobStore;
        this.json = json;
        this.liveUploadsEnabled = liveUploadsEnabled;
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
            run = coordinator.processReplay(
                    run.runId(), "http-replay-" + run.runId()
            ).orElse(run);
        }
        var response = toRunResponse(run);
        return ResponseEntity
                .status(created.created() ? 201 : 200)
                .location(URI.create("/api/v1/inference-runs/" + run.runId()))
                .body(response);
    }

    @GetMapping
    InferenceRunPageResponse list(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        if (page < 1 || size < 1 || size > 20) {
            throw new InvalidInferenceApiRequestException("page must be >= 1 and size must be 1..20");
        }
        try {
            Math.multiplyExact(page - 1, size);
        } catch (ArithmeticException overflow) {
            throw new InvalidInferenceApiRequestException("page is too large", overflow);
        }
        var result = runStore.list(page, size);
        return new InferenceRunPageResponse(
                result.page(), result.size(), result.total(),
                result.items().stream().map(InferenceController::toRunResponse).toList()
        );
    }

    @GetMapping("/live-availability")
    LiveAvailabilityResponse liveAvailability() {
        var items = profiles.productLiveProfiles().stream()
                .map(InferenceProfileRegistry.ProfileResource::profile)
                .map(profile -> new LiveProfileResponse(
                        profile.profileId(), profile.provider(), profile.model(), profile.certification(),
                        profile.supportedModes().stream().map(Enum::name).toList(),
                        profile.maximumTotalCalls(), profile.maximumOutputTokens(),
                        profile.maximumEstimatedCostMicrosCny(), profile.pricingEffectiveDate()
                ))
                .toList();
        return new LiveAvailabilityResponse(
                coordinator.liveEnabled(), provider.configured(), liveUploadsEnabled, "USER_PROVIDED",
                false, MAXIMUM_RUN_COST_LIMIT_MICROS_CNY, items
        );
    }

    @PostMapping(path = "/live", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<InferenceRunResponse> createLive(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestPart("metadata") CreateLiveRunRequest request,
            @RequestPart(name = "images", required = false) List<MultipartFile> images,
            @RequestPart(name = "jsonSamples", required = false) List<MultipartFile> jsonSamples
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidInferenceApiRequestException("Idempotency-Key is required");
        }
        requireLiveAuthorization();
        if (!"USER_PROVIDED".equals(request.inputClassification())) {
            throw new InvalidInferenceApiRequestException("Live uploads must use the user-provided classification");
        }
        if (!Boolean.TRUE.equals(request.externalTransferConfirmed())
                || !Boolean.TRUE.equals(request.experimentalProfileConfirmed())) {
            throw new InvalidInferenceApiRequestException(
                    "External transfer and experimental profile confirmations are required"
            );
        }
        final InferenceMode mode;
        try {
            mode = parseMode(request.mode());
        } catch (IllegalArgumentException exception) {
            throw new InvalidInferenceApiRequestException("Unsupported inference mode", exception);
        }
        final InferenceProfileRegistry.ProfileResource profile;
        try {
            profile = profiles.require(request.profileId());
        } catch (IllegalArgumentException exception) {
            throw new InvalidInferenceApiRequestException("Unknown inference profile", exception);
        }
        if (!profiles.isProductLiveProfile(profile.profile().profileId())
                || !profile.profile().networkAllowed()
                || !"EXPERIMENTAL".equals(profile.profile().certification())
                || !profile.profile().supportedModes().contains(mode)) {
            throw new InvalidInferenceApiRequestException("Profile is not authorized for this live mode");
        }
        if (request.costLimitMicrosCny() != null
                && (request.costLimitMicrosCny() < 1
                || request.costLimitMicrosCny() > MAXIMUM_RUN_COST_LIMIT_MICROS_CNY)) {
            throw new InvalidInferenceApiRequestException(
                    "costLimitMicrosCny must be 1.." + MAXIMUM_RUN_COST_LIMIT_MICROS_CNY + " when present"
            );
        }
        var input = new InferenceInput(
                mode, profile.profile().profileId(), "user-upload", true,
                binaryInputs(images, false), binaryInputs(jsonSamples, true)
        );
        var created = runService.create(
                idempotencyKey, input, profile.snapshotJson(), request.costLimitMicrosCny()
        );
        if (created.created()) coordinator.kick();
        return ResponseEntity
                .status(created.created() ? 201 : 200)
                .location(URI.create("/api/v1/inference-runs/" + created.run().runId()))
                .body(toRunResponse(created.run()));
    }

    @GetMapping("/{runId}")
    InferenceRunResponse get(@PathVariable UUID runId) {
        return toRunResponse(runStore.find(runId)
                .orElseThrow(() -> new cn.hbads.renderweave.inference.run.InferenceRunNotFoundException(runId)));
    }

    @PostMapping("/{runId}/cancel")
    InferenceRunResponse cancel(@PathVariable UUID runId) {
        return toRunResponse(runService.cancel(runId));
    }

    @PostMapping("/{runId}/retries")
    ResponseEntity<InferenceRunResponse> retry(
            @PathVariable UUID runId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidInferenceApiRequestException("Idempotency-Key is required");
        }
        var source = runStore.find(runId)
                .orElseThrow(() -> new cn.hbads.renderweave.inference.run.InferenceRunNotFoundException(runId));
        var live = profiles.parseSnapshot(source.profileSnapshotJson()).networkAllowed();
        if (live) requireLiveAuthorization();
        var retried = runService.retry(runId, idempotencyKey);
        var run = retried.run();
        if (retried.created()) {
            if (live) {
                coordinator.kick();
            } else {
                run = coordinator.processReplay(
                        run.runId(), "http-retry-" + run.runId()
                ).orElse(run);
            }
        }
        return ResponseEntity
                .status(retried.created() ? 201 : 200)
                .location(URI.create("/api/v1/inference-runs/" + run.runId()))
                .body(toRunResponse(run));
    }

    private void requireLiveAuthorization() {
        if (!coordinator.liveEnabled()) {
            throw new LiveInferenceUnavailableException(
                    "LIVE_INFERENCE_DISABLED", "Live inference is disabled by deployment policy"
            );
        }
        if (!liveUploadsEnabled) {
            throw new LiveInferenceUnavailableException(
                    "LIVE_UPLOAD_NOT_AUTHORIZED",
                    "Arbitrary live uploads require a separate data-transfer authorization"
            );
        }
        if (!provider.configured()) {
            throw new LiveInferenceUnavailableException(
                    "DASHSCOPE_NOT_CONFIGURED", "DashScope credential is not configured"
            );
        }
    }

    @GetMapping(path = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(
            @PathVariable UUID runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(name = "afterSequence", required = false) Long afterSequence
    ) {
        runStore.find(runId).orElseThrow(
                () -> new cn.hbads.renderweave.inference.run.InferenceRunNotFoundException(runId)
        );
        var cursor = eventCursor(lastEventId, afterSequence);
        var emitter = new SseEmitter(30_000L);
        Thread.startVirtualThread(() -> streamEvents(runId, cursor, emitter));
        return emitter;
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

    @PostMapping("/{runId}/apply")
    CandidateApplyResponse applyCandidate(
            @PathVariable UUID runId,
            @RequestBody ApplyCandidateRequest request
    ) {
        if (request.expectedCandidateRevision() == null || request.expectedCandidateRevision() < 0) {
            throw new InvalidInferenceApiRequestException(
                    "expectedCandidateRevision must be a non-negative integer"
            );
        }
        return toApplyResponse(applies.apply(runId, request.expectedCandidateRevision()));
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

    private void streamEvents(UUID runId, long initialCursor, SseEmitter emitter) {
        var cursor = initialCursor;
        var deadline = Instant.now().plus(Duration.ofSeconds(25));
        try {
            while (Instant.now().isBefore(deadline)) {
                var events = runStore.eventsAfter(runId, cursor, 100);
                for (var event : events) {
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(event.sequence()))
                            .name(event.type())
                            .data(new InferenceEventResponse(
                                    event.sequence(), event.type(), event.state().name(),
                                    event.stage().name(), tree(event.dataJson()), event.occurredAt()
                            ), MediaType.APPLICATION_JSON));
                    cursor = event.sequence();
                }
                var snapshot = runStore.find(runId).orElseThrow(
                        () -> new cn.hbads.renderweave.inference.run.InferenceRunNotFoundException(runId)
                );
                if (snapshot.state().terminal() && cursor >= snapshot.sequence()) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(250);
            }
            emitter.send(SseEmitter.event().comment("reconnect"));
            emitter.complete();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (IOException | RuntimeException failure) {
            emitter.completeWithError(failure);
        }
    }

    private static long eventCursor(String lastEventId, Long afterSequence) {
        long cursor = afterSequence == null ? 0 : afterSequence;
        if (cursor < 0) {
            throw new InvalidInferenceApiRequestException("afterSequence must not be negative");
        }
        if (lastEventId == null || lastEventId.isBlank()) return cursor;
        try {
            var headerCursor = Long.parseLong(lastEventId);
            if (headerCursor < 0) throw new NumberFormatException("negative");
            return Math.max(cursor, headerCursor);
        } catch (NumberFormatException invalid) {
            throw new InvalidInferenceApiRequestException("Last-Event-ID must be a non-negative integer", invalid);
        }
    }

    private static InferenceMode parseMode(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("mode is required");
        try {
            return InferenceMode.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return InferenceMode.fromWireName(value);
        }
    }

    private static List<InferenceInput.BinaryInput> binaryInputs(
            List<MultipartFile> files,
            boolean jsonInput
    ) {
        if (files == null) return List.of();
        return files.stream().map(file -> {
            try {
                var name = file.getOriginalFilename();
                if (name == null || name.isBlank()) name = jsonInput ? "sample.json" : "image";
                var mediaType = file.getContentType();
                if (mediaType == null || mediaType.isBlank()) {
                    mediaType = jsonInput ? MediaType.APPLICATION_JSON_VALUE : MediaType.APPLICATION_OCTET_STREAM_VALUE;
                }
                return new InferenceInput.BinaryInput(name, mediaType, file.getBytes());
            } catch (IOException exception) {
                throw new InvalidInferenceApiRequestException("Uploaded input could not be read", exception);
            }
        }).toList();
    }

    private CandidateReviewResponse toReviewResponse(CandidateReviewSnapshot review) {
        return new CandidateReviewResponse(
                toRunResponse(review.run()),
                review.candidateRevision(),
                tree(candidateCodec.write(review.original())),
                tree(candidateCodec.write(review.current())),
                review.problems().stream().map(InferenceController::toProblem).toList(),
                review.finalCandidate().map(candidate -> tree(candidateCodec.write(candidate))).orElse(null),
                review.appliedAt().orElse(null),
                review.run().inputs().stream()
                        .filter(input -> input.kind() == NormalizedArtifact.Kind.IMAGE)
                        .map(input -> new InferenceImageResponse(
                                input.artifact().artifactId(), input.ordinal(),
                                input.artifact().width(), input.artifact().height(),
                                "/api/v1/inference-runs/" + review.run().runId()
                                        + "/artifacts/" + input.artifact().artifactId()
                        ))
                        .toList(),
                jsonSampleCount(review.run())
        );
    }

    private int jsonSampleCount(InferenceRunSnapshot run) {
        var jsonInputs = run.inputs().stream()
                .filter(input -> input.kind() == NormalizedArtifact.Kind.JSON_PROFILE)
                .toList();
        if (jsonInputs.isEmpty()) return 0;
        if (jsonInputs.size() > 1) throw new IllegalStateException("A run may contain one JSON profile artifact");
        return structuralProfiler.profile(
                blobStore.read(jsonInputs.getFirst().artifact().locator())
        ).sampleCount();
    }

    private CandidateApplyResponse toApplyResponse(CandidateApplyResult result) {
        return new CandidateApplyResponse(
                toRunResponse(result.run()), result.candidateRevision(),
                result.rootSchemaKey().value(),
                result.createdSchemaKeys().stream()
                        .map(schemaKey -> new CreatedDraftResponse(
                                schemaKey.value(), 0,
                                "/api/v1/schema-drafts/" + schemaKey.value()
                        ))
                        .toList(),
                result.appliedAt()
        );
    }

    private InferenceRunResponse toRunResponse(InferenceRunSnapshot run) {
        return new InferenceRunResponse(
                run.runId(), run.mode().name(), run.state().name(), run.stage().name(), run.sequence(),
                run.profileId(), run.sourceReference(), run.costLimitMicrosCny(), run.cancellationRequested(),
                run.retryOfRunId().orElse(null),
                run.failureCode().orElse(null),
                replayStore.findCandidate(run.runId()).map(snapshot -> snapshot.revision()).orElse(null),
                run.createdAt(), run.updatedAt(), run.finishedAt().orElse(null)
        );
    }

    private static InferenceRunResponse toRunResponse(InferenceRunStore.RunSummary run) {
        return new InferenceRunResponse(
                run.runId(), run.mode(), run.state(), run.stage(), run.sequence(),
                run.profileId(), run.sourceReference(), run.costLimitMicrosCny(), run.cancellationRequested(),
                run.retryOfRunId(), run.failureCode(), run.candidateRevision(),
                run.createdAt(), run.updatedAt(), run.finishedAt()
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

    record CreateLiveRunRequest(
            String profileId,
            String mode,
            String inputClassification,
            Boolean externalTransferConfirmed,
            Boolean experimentalProfileConfirmed,
            Long costLimitMicrosCny
    ) { }

    record LiveAvailabilityResponse(
            boolean enabled,
            boolean configured,
            boolean uploadEnabled,
            String inputClassification,
            boolean runCostLimitRequired,
            long maximumRunCostLimitMicrosCny,
            List<LiveProfileResponse> profiles
    ) { }

    record LiveProfileResponse(
            String profileId,
            String provider,
            String model,
            String certification,
            List<String> supportedModes,
            int maximumTotalCalls,
            int maximumOutputTokens,
            long maximumEstimatedCostMicrosCny,
            String pricingEffectiveDate
    ) { }

    record SaveCandidateRequest(Long expectedCandidateRevision, JsonNode candidate) { }

    record ApplyCandidateRequest(Long expectedCandidateRevision) { }

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
            String sourceReference,
            Long costLimitMicrosCny,
            boolean cancellationRequested,
            UUID retryOfRunId,
            String failureCode,
            Long candidateRevision,
            Instant createdAt,
            Instant updatedAt,
            Instant finishedAt
    ) { }

    record InferenceRunPageResponse(
            int page,
            int size,
            long total,
            List<InferenceRunResponse> items
    ) { }

    record CandidateReviewResponse(
            InferenceRunResponse run,
            long candidateRevision,
            JsonNode original,
            JsonNode current,
            List<CandidateProblemResponse> problems,
            JsonNode finalCandidate,
            Instant appliedAt,
            List<InferenceImageResponse> images,
            int jsonSampleCount
    ) { }

    record CandidateApplyResponse(
            InferenceRunResponse run,
            long candidateRevision,
            String rootSchemaKey,
            List<CreatedDraftResponse> createdDrafts,
            Instant appliedAt
    ) { }

    record CreatedDraftResponse(String schemaKey, long revision, String href) { }

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

    record InferenceEventResponse(
            long sequence,
            String type,
            String state,
            String stage,
            JsonNode data,
            Instant occurredAt
    ) { }
}
