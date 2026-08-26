package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.RenderOutput;
import cn.hbads.renderweave.rendering.api.RenderingApplication;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOperationId;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOutcome;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderPurpose;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication.TemplateId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Public single-image Rendering delivery; Engine protocol and identities remain internal. */
@RestController
@RequestMapping("/api/v1/templates")
final class RenderingController {

    static final String INPUT_MEDIA_TYPE =
            "application/vnd.renderweave.render-input+json;version=1.0";
    static final String PROBLEM_MEDIA_TYPE =
            "application/vnd.renderweave.render-problem+json;version=1.0";
    static final int MAX_RENDER_INPUT_BYTES = 8 * 1024 * 1024;

    private static final String RESULT_VERSION_HEADER = "RenderWeave-Result-Version";
    private static final String REQUEST_ID_HEADER = "RenderWeave-Request-Id";
    private static final String RENDERER_PROFILE_HEADER = "RenderWeave-Renderer-Profile";
    private static final String DSL_VERSION_HEADER = "RenderWeave-DSL-Version";
    private static final String LAYOUT_PROFILE_HEADER = "RenderWeave-Layout-Profile";
    private static final String OUTPUT_PROFILE_HEADER = "RenderWeave-Output-Profile";
    private static final String FORMAT_HEADER = "RenderWeave-Format";
    private static final String WIDTH_HEADER = "RenderWeave-Width-Px";
    private static final String HEIGHT_HEADER = "RenderWeave-Height-Px";
    private static final String DPI_HEADER = "RenderWeave-DPI";
    private static final String QUALITY_HEADER = "RenderWeave-Quality";
    private static final Set<String> OUTPUT_QUERY = Set.of("format", "dpi", "quality");

    private final RenderingApplication rendering;
    private final DesignInputExpressionCapacityAuthority capacityAuthority;

    RenderingController(
            RenderingApplication rendering,
            DesignInputExpressionCapacityAuthority capacityAuthority
    ) {
        this.rendering = java.util.Objects.requireNonNull(rendering, "rendering");
        this.capacityAuthority = java.util.Objects.requireNonNull(
                capacityAuthority,
                "capacityAuthority"
        );
    }

    @PostMapping("/{templateId}/render")
    ResponseEntity<?> render(
            @PathVariable String templateId,
            @RequestParam MultiValueMap<String, String> query,
            HttpServletRequest request
    ) {
        return execute(templateId, query, request, RenderPurpose.FORMAL_OUTPUT);
    }

    @PostMapping("/{templateId}/authoritative-preview")
    ResponseEntity<?> authoritativePreview(
            @PathVariable String templateId,
            @RequestParam MultiValueMap<String, String> query,
            HttpServletRequest request
    ) {
        return execute(templateId, query, request, RenderPurpose.AUTHORITATIVE_PREVIEW);
    }

    private ResponseEntity<?> execute(
            String rawTemplateId,
            MultiValueMap<String, String> query,
            HttpServletRequest request,
            RenderPurpose purpose
    ) {
        if (!isExactInputMediaType(request.getContentType())) {
            return requestProblem(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "RENDER_INPUT_MEDIA_TYPE_UNSUPPORTED",
                    Optional.empty());
        }
        var encodingProblem = contentEncodingProblem(request);
        if (encodingProblem.isPresent()) {
            var rejected = encodingProblem.orElseThrow();
            return problem(
                    status(rejected),
                    null,
                    rejected.code().name(),
                    rejected.stage().name(),
                    Optional.empty(),
                    Optional.empty()
            );
        }

        final TemplateId templateId;
        final OutputSelection output;
        try {
            templateId = TemplateId.of(rawTemplateId);
            output = outputSelection(query);
        } catch (IllegalArgumentException invalid) {
            return requestProblem(
                    HttpStatus.BAD_REQUEST,
                    "RENDER_REQUEST_INVALID",
                    Optional.empty());
        }

        final byte[] input;
        try {
            input = boundedInput(request);
        } catch (InputLimitExceeded limit) {
            return requestProblem(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    RenderingProblem.ProblemCode.RENDER_INPUT_LIMIT_EXCEEDED.name(),
                    Optional.of(new RenderingProblem.LimitId(
                            "renderInput.entityUtf8Bytes")));
        } catch (IOException unreadable) {
            return requestProblem(
                    HttpStatus.BAD_REQUEST,
                    "RENDER_REQUEST_INVALID",
                    Optional.empty());
        }
        if (input.length == 0) {
            return requestProblem(
                    HttpStatus.BAD_REQUEST,
                    "RENDER_REQUEST_INVALID",
                    Optional.empty());
        }

        var outcome = rendering.render(
                RenderingApplication.RenderInvocationRef.serverCreated(
                        UUID.randomUUID().toString()),
                new RenderingApplication.RenderCommand(templateId, input, output, purpose));
        return response(outcome);
    }

    private static ResponseEntity<?> response(RenderOutcome outcome) {
        return switch (outcome) {
            case RenderOutcome.Rendered rendered -> rendered(rendered);
            case RenderOutcome.Rejected rejected -> problem(
                    status(rejected.problem()),
                    rejected.operationId(),
                    rejected.problem().code().name(),
                    rejected.problem().stage().name(),
                    rejected.problem().safeLocation(),
                    rejected.problem().limitId());
            case RenderOutcome.NotFound notFound -> problem(
                    HttpStatus.NOT_FOUND,
                    notFound.operationId(),
                    "TEMPLATE_NOT_FOUND",
                    EvaluationStage.REQUEST_ADMISSION.name(),
                    Optional.empty(),
                    Optional.empty());
            case RenderOutcome.Forbidden forbidden -> problem(
                    HttpStatus.FORBIDDEN,
                    forbidden.operationId(),
                    "RENDER_FORBIDDEN",
                    EvaluationStage.REQUEST_ADMISSION.name(),
                    Optional.empty(),
                    Optional.empty());
            case RenderOutcome.AuthorityUnavailable unavailable -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    unavailable.operationId(),
                    "RENDER_AUTHORITY_UNAVAILABLE",
                    EvaluationStage.REQUEST_ADMISSION.name(),
                    Optional.empty(),
                    Optional.empty());
            case RenderOutcome.RendererUnavailable unavailable -> problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    unavailable.operationId(),
                    "RENDERER_UNAVAILABLE",
                    EvaluationStage.REQUEST_ADMISSION.name(),
                    Optional.empty(),
                    Optional.empty());
        };
    }

    private static ResponseEntity<byte[]> rendered(RenderOutcome.Rendered rendered) {
        RenderOutput output = rendered.output();
        var builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(output.mediaType()))
                .contentLength(output.byteLength())
                .header("Content-Digest", contentDigest(output.contentSha256()))
                .header(RESULT_VERSION_HEADER, output.contractVersion())
                // Ticket 16's public header carries the public operation identity, never Engine ID.
                .header(REQUEST_ID_HEADER, rendered.operationId().value())
                .header(RENDERER_PROFILE_HEADER, output.rendererProfile())
                .header(DSL_VERSION_HEADER, output.dslVersion())
                .header(LAYOUT_PROFILE_HEADER, output.layoutProfile())
                .header(OUTPUT_PROFILE_HEADER, output.outputProfile())
                .header(FORMAT_HEADER, output.format())
                .header(WIDTH_HEADER, Integer.toString(output.widthPx()))
                .header(HEIGHT_HEADER, Integer.toString(output.heightPx()))
                .header(DPI_HEADER, Integer.toString(output.dpi()));
        output.quality().ifPresent(value ->
                builder.header(QUALITY_HEADER, Integer.toString(value)));
        return builder.body(output.sealedImageBytes());
    }

    private static HttpStatus status(RenderingProblem problem) {
        return switch (problem.code()) {
            case RENDER_INPUT_CONTENT_ENCODING_UNSUPPORTED -> HttpStatus.BAD_REQUEST;
            case RENDER_REQUEST_CONFLICT, RENDER_REQUEST_STATE_LOST, RENDER_CANCELLED ->
                    HttpStatus.CONFLICT;
            case FETCH_FAILED, ASSET_RESOLVE_UNAVAILABLE, ASSET_RESOLVE_TIMEOUT ->
                    HttpStatus.BAD_GATEWAY;
            case RENDER_ENGINE_BUSY,
                    CAPABILITY_STATE_UNAVAILABLE,
                    CAPABILITY_CLOCK_UNAVAILABLE,
                    CAPABILITY_ENTROPY_UNAVAILABLE,
                    CAPABILITY_PROFILE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case RENDER_DEADLINE_EXCEEDED, CAPABILITY_DEADLINE_EXCEEDED ->
                    HttpStatus.GATEWAY_TIMEOUT;
            case RENDER_INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    private static ResponseEntity<?> requestProblem(
            HttpStatus status,
            String code,
            Optional<RenderingProblem.LimitId> limit
    ) {
        return problem(
                status,
                null,
                code,
                EvaluationStage.REQUEST_ADMISSION.name(),
                Optional.empty(),
                limit);
    }

    private static ResponseEntity<?> problem(
            HttpStatus status,
            RenderOperationId operationId,
            String code,
            String stage,
            Optional<String> safeLocation,
            Optional<RenderingProblem.LimitId> limit
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contractVersion", "renderweave-render-problem/1.0");
        if (operationId != null) {
            body.put("renderOperationId", operationId.value());
        }
        body.put("code", code);
        body.put("stage", stage);
        safeLocation.ifPresent(value -> body.put("safeLocation", value));
        body.put("parameters", limit.isPresent()
                ? Map.of("limitId", limit.orElseThrow().value())
                : Map.of());
        return ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(PROBLEM_MEDIA_TYPE))
                .body(body);
    }

    private static OutputSelection outputSelection(MultiValueMap<String, String> query) {
        if (!OUTPUT_QUERY.containsAll(query.keySet())) {
            throw new IllegalArgumentException("unknown Rendering query parameter");
        }
        var format = single(query, "format", true);
        var dpi = integer(single(query, "dpi", false), 96, 1, 600);
        var quality = single(query, "quality", false);
        return switch (format) {
            case "PNG" -> {
                if (quality != null) {
                    throw new IllegalArgumentException("PNG does not accept quality");
                }
                yield new OutputSelection.Png(dpi);
            }
            case "JPEG" -> new OutputSelection.Jpeg(
                    dpi,
                    integer(quality, 90, 1, 100));
            default -> throw new IllegalArgumentException("format is not supported");
        };
    }

    private static String single(
            MultiValueMap<String, String> query,
            String name,
            boolean required
    ) {
        var values = query.get(name);
        if (values == null) {
            if (required) {
                throw new IllegalArgumentException(name + " is required");
            }
            return null;
        }
        if (values.size() != 1 || values.get(0) == null || values.get(0).isBlank()) {
            throw new IllegalArgumentException(name + " must occur exactly once");
        }
        return values.get(0);
    }

    private static int integer(String raw, int defaultValue, int minimum, int maximum) {
        if (raw == null) {
            return defaultValue;
        }
        if (!raw.matches("[0-9]+")) {
            throw new IllegalArgumentException("integer query value is invalid");
        }
        final int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("integer query value is invalid", overflow);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("integer query value is outside the public bound");
        }
        return value;
    }

    private static boolean isExactInputMediaType(String raw) {
        if (raw == null) {
            return false;
        }
        try {
            var mediaType = MediaType.parseMediaType(raw);
            return "application".equalsIgnoreCase(mediaType.getType())
                    && "vnd.renderweave.render-input+json".equalsIgnoreCase(
                            mediaType.getSubtype())
                    && mediaType.getParameters().size() == 1
                    && "1.0".equals(mediaType.getParameter("version"));
        } catch (InvalidMediaTypeException invalid) {
            return false;
        }
    }

    private Optional<RenderingProblem> contentEncodingProblem(HttpServletRequest request) {
        var observation = new DesignInputExpressionCapacityAuthority.Observation(
                "renderInput.contentEncoding",
                contentEncodingObservation(request)
        );
        final DesignInputExpressionCapacityAuthority.Decision decision;
        try {
            decision = capacityAuthority.evaluate(observation);
        } catch (RuntimeException unavailable) {
            return Optional.of(internalCapacityProblem());
        }
        return switch (decision) {
            case DesignInputExpressionCapacityAuthority.Accepted ignored -> Optional.empty();
            case DesignInputExpressionCapacityAuthority.Rejected rejected ->
                    Optional.of(terminalProblem(rejected.terminal()));
            case DesignInputExpressionCapacityAuthority.Invalid ignored ->
                    Optional.of(internalCapacityProblem());
            case null -> Optional.of(internalCapacityProblem());
        };
    }

    private static String contentEncodingObservation(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("Content-Encoding"));
        if (values.isEmpty()) {
            return "identity";
        }
        if (values.size() != 1 || values.get(0) == null) {
            return "invalid";
        }
        var value = values.get(0).strip();
        return value.isEmpty() ? "invalid" : value.toLowerCase(Locale.ROOT);
    }

    private static RenderingProblem terminalProblem(
            DesignInputExpressionCapacityAuthority.Terminal terminal
    ) {
        try {
            return RenderingProblem.of(
                    RenderingProblem.ProblemCode.valueOf(terminal.code()),
                    EvaluationStage.valueOf(terminal.publicRenderStage())
            );
        } catch (IllegalArgumentException | NullPointerException invalidTerminal) {
            return internalCapacityProblem();
        }
    }

    private static RenderingProblem internalCapacityProblem() {
        return RenderingProblem.of(
                RenderingProblem.ProblemCode.RENDER_INTERNAL_ERROR,
                EvaluationStage.INPUT_ADMISSION
        );
    }

    private static byte[] boundedInput(HttpServletRequest request)
            throws IOException, InputLimitExceeded {
        var declared = request.getContentLengthLong();
        if (declared > MAX_RENDER_INPUT_BYTES) {
            throw new InputLimitExceeded();
        }
        var bytes = request.getInputStream().readNBytes(MAX_RENDER_INPUT_BYTES + 1);
        if (bytes.length > MAX_RENDER_INPUT_BYTES) {
            throw new InputLimitExceeded();
        }
        return bytes;
    }

    private static String contentDigest(String rawSha256) {
        var digest = HexFormat.of().parseHex(rawSha256);
        return "sha-256=:" + Base64.getEncoder().encodeToString(digest) + ":";
    }

    private static final class InputLimitExceeded extends Exception {
    }
}
