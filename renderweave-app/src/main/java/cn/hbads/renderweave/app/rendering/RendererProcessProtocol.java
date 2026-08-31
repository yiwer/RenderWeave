package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.LimitId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import cn.hbads.renderweave.rendering.spi.RenderEngine.EngineProblemStage;
import cn.hbads.renderweave.rendering.spi.RenderEngine.RendererCommand;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Exact app-side codec for {@code renderweave-renderer-process/1.0}.
 *
 * <p>The class deliberately owns only wire concerns. It does not parse RenderDocument semantics,
 * select profiles, launch a renderer, or relax an Engine failure into an output.
 */
final class RendererProcessProtocol {

    static final String PROCESS_CONTRACT_VERSION = "renderweave-renderer-process/1.0";
    static final String COMMAND_CONTRACT_VERSION = "renderweave-render-command/1.0";
    static final String COMMAND_DIGEST_DOMAIN = "renderweave-render-command/1\0";
    static final String DOCUMENT_DIGEST_DOMAIN = "renderweave-render-document/1\0";
    static final String PROBLEM_CONTRACT_VERSION = "renderweave-render-problem/1.0";
    static final String RESULT_CONTRACT_VERSION = "renderweave-render-result/1.0";
    static final List<String> CAPABILITIES = List.of(
            "render-command-v1",
            "render-cancel-v1",
            "render-document-v1",
            "render-result-v1",
            "render-problem-v1");

    private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern UUID_V4 = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern PROFILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}");
    private static final Pattern WIRE_DEADLINE = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z");
    private static final java.time.format.DateTimeFormatter DEADLINE_FORMAT =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter().withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private RendererProcessProtocol() {
    }

    enum FrameType {
        CLIENT_HELLO(0x01),
        SERVER_HELLO(0x02),
        COMMAND(0x10),
        CANCEL(0x11),
        RESULT_METADATA(0x20),
        RESULT_IMAGE(0x21),
        PROBLEM(0x30);

        private final int wire;

        FrameType(int wire) {
            this.wire = wire;
        }

        int wire() {
            return wire;
        }

        static FrameType fromWire(int wire) throws ProtocolException {
            for (var value : values()) {
                if (value.wire == wire) {
                    return value;
                }
            }
            throw new ProtocolException("unknown renderer frame type");
        }
    }

    record Frame(FrameType type, byte[] payload) {
        Frame {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(payload, "payload");
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    record EncodedCommand(byte[] canonicalJsonUtf8, String commandDigest) {
        EncodedCommand {
            canonicalJsonUtf8 = canonicalJsonUtf8.clone();
            requireSha256(commandDigest, "commandDigest");
        }

        @Override
        public byte[] canonicalJsonUtf8() {
            return canonicalJsonUtf8.clone();
        }
    }

    record ParsedProblem(
            String requestId,
            ProblemCode code,
            EngineProblemStage engineStage,
            Optional<String> occurrenceId,
            Optional<String> resourceId,
            Optional<LimitId> limitId
    ) {
    }

    record ParsedResult(
            String contractVersion,
            String requestId,
            String rendererProfile,
            String dslVersion,
            String layoutProfile,
            String outputProfile,
            String format,
            String mediaType,
            int widthPx,
            int heightPx,
            int dpi,
            OptionalInt quality,
            long byteLength,
            String contentSha256
    ) {
        OutputSelection outputSelection() {
            if ("PNG".equals(format)) {
                return new OutputSelection.Png(dpi);
            }
            return new OutputSelection.Jpeg(dpi, quality.orElseThrow());
        }
    }

    record ParsedImage(String requestId, byte[] imageBytes) {
        ParsedImage {
            imageBytes = imageBytes.clone();
        }

        @Override
        public byte[] imageBytes() {
            return imageBytes.clone();
        }
    }

    static final class ProtocolException extends IOException {
        ProtocolException(String message) {
            super(message);
        }

        ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static byte[] encodeFrame(FrameType type, byte[] payload) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (payload.length == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("frame payload is too large");
        }
        var framedLength = payload.length + 1;
        var buffer = ByteBuffer.allocate(4 + framedLength);
        buffer.putInt(framedLength);
        buffer.put((byte) type.wire());
        buffer.put(payload);
        return buffer.array();
    }

    static Frame readFrame(InputStream input, int maximumFramedBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        if (maximumFramedBytes < 1) {
            throw new IllegalArgumentException("maximumFramedBytes must be positive");
        }
        var header = readExact(input, 4);
        var framedLength = ByteBuffer.wrap(header).getInt();
        if (framedLength < 1) {
            throw new ProtocolException("renderer frame length must include a type byte");
        }
        if (framedLength > maximumFramedBytes) {
            throw new ProtocolException("renderer frame exceeds configured maximum");
        }
        var body = readExact(input, framedLength);
        var type = FrameType.fromWire(Byte.toUnsignedInt(body[0]));
        return new Frame(type, Arrays.copyOfRange(body, 1, body.length));
    }

    static byte[] encodeClientHello(String manifestSha256) {
        requireSha256(manifestSha256, "manifestSha256");
        var json = "{\"contractVersion\":\"" + PROCESS_CONTRACT_VERSION
                + "\",\"manifestSha256\":\"" + manifestSha256
                + "\",\"requiredCapabilities\":" + stringArray(CAPABILITIES) + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] encodeServerHelloForT22(String manifestSha256) {
        requireSha256(manifestSha256, "manifestSha256");
        var json = "{\"contractVersion\":\"" + PROCESS_CONTRACT_VERSION
                + "\",\"manifestSha256\":\"" + manifestSha256
                + "\",\"capabilities\":" + stringArray(CAPABILITIES)
                + ",\"rendererProfiles\":[],\"profileAvailability\":\"NOT_REGISTERED\""
                + ",\"certificationStatus\":\"NOT_CERTIFIED\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static void validateServerHelloForT22(byte[] payload, String expectedManifestSha256)
            throws ProtocolException {
        Objects.requireNonNull(payload, "payload");
        var expected = encodeServerHelloForT22(expectedManifestSha256);
        if (!MessageDigest.isEqual(expected, payload)) {
            throw new ProtocolException("renderer SERVER_HELLO identity mismatch");
        }
        readStrictObject(payload, "SERVER_HELLO");
    }

    static EncodedCommand encodeCommand(RendererCommand command) {
        Objects.requireNonNull(command, "command");
        if (!COMMAND_CONTRACT_VERSION.equals(command.contractVersion())) {
            throw new IllegalArgumentException("unexpected renderer Command version");
        }
        var requestId = command.renderRequestId().value();
        requireUuidV4(requestId, "requestId");
        requireProfile(command.rendererProfile(), "rendererProfile");
        requireSha256(command.renderDocumentDigest(), "renderDocumentDigest");

        var document = strictUtf8(command.renderDocumentCanonicalUtf8(), "RenderDocument");
        requireCanonicalJsonObject(command.renderDocumentCanonicalUtf8(), "RenderDocument");
        var recomputedDocumentDigest = digest(DOCUMENT_DIGEST_DOMAIN,
                command.renderDocumentCanonicalUtf8());
        if (!MessageDigest.isEqual(
                recomputedDocumentDigest.getBytes(StandardCharsets.US_ASCII),
                command.renderDocumentDigest().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("renderDocumentDigest does not match exact document bytes");
        }

        var deadline = DEADLINE_FORMAT.format(Instant.ofEpochMilli(command.deadlineAtEpochMilli()));
        if (!WIRE_DEADLINE.matcher(deadline).matches() || deadline.startsWith("0000-")) {
            throw new IllegalArgumentException(
                    "deadlineAt must fit the exact four-digit RFC3339 UTC wire shape");
        }
        var output = output(command.outputSelection());
        var json = "{\"contractVersion\":\"" + COMMAND_CONTRACT_VERSION
                + "\",\"requestId\":\"" + requestId
                + "\",\"rendererProfile\":\"" + command.rendererProfile()
                + "\",\"deadlineAt\":\"" + deadline
                + "\",\"renderDocumentDigest\":\"" + command.renderDocumentDigest()
                + "\",\"document\":" + document
                + ",\"output\":" + output
                + ",\"diagnostics\":{\"layoutTrace\":"
                + command.layoutTraceRequested() + "}}";
        var bytes = json.getBytes(StandardCharsets.UTF_8);
        return new EncodedCommand(bytes, digest(COMMAND_DIGEST_DOMAIN, bytes));
    }

    static byte[] resultImagePayload(String requestId, byte[] imageBytes) {
        requireUuidV4(requestId, "requestId");
        Objects.requireNonNull(imageBytes, "imageBytes");
        var uuidBytes = uuidNetworkBytes(requestId);
        var payload = new byte[uuidBytes.length + imageBytes.length];
        System.arraycopy(uuidBytes, 0, payload, 0, uuidBytes.length);
        System.arraycopy(imageBytes, 0, payload, uuidBytes.length, imageBytes.length);
        return payload;
    }

    static ParsedProblem parseProblem(byte[] payload) throws ProtocolException {
        var node = readStrictObject(payload, "PROBLEM");
        var occurrence = optionalText(node, "occurrenceId");
        var resource = optionalText(node, "resourceId");
        var expectedMembers = 5 + (occurrence.isPresent() ? 1 : 0) + (resource.isPresent() ? 1 : 0);
        if (node.size() != expectedMembers
                || !node.has("contractVersion")
                || !node.has("requestId")
                || !node.has("code")
                || !node.has("engineStage")
                || !node.has("parameters")) {
            throw new ProtocolException("renderer PROBLEM member set mismatch");
        }
        var contractVersion = requiredText(node, "contractVersion");
        var requestId = requiredText(node, "requestId");
        var codeText = requiredText(node, "code");
        var engineStageText = requiredText(node, "engineStage");
        if (!PROBLEM_CONTRACT_VERSION.equals(contractVersion)) {
            throw new ProtocolException("renderer PROBLEM contract mismatch");
        }
        requireUuidForProtocol(requestId, "problem requestId");
        var engineStage = parseEngineStage(engineStageText);
        ProblemCode code;
        try {
            code = ProblemCode.valueOf(codeText);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException("renderer PROBLEM code is unknown", e);
        }
        if (!isEngineProblemCode(code)) {
            throw new ProtocolException("renderer PROBLEM code is outside the Engine catalog");
        }
        var parameters = node.path("parameters");
        if (!parameters.isObject()) {
            throw new ProtocolException("renderer PROBLEM parameters must be an object");
        }
        Optional<LimitId> limitId;
        String parametersCanonical;
        if (parameters.size() == 0) {
            limitId = Optional.empty();
            parametersCanonical = "{}";
        } else if (parameters.size() == 1 && parameters.has("limitId")) {
            var value = requiredText(parameters, "limitId");
            try {
                limitId = Optional.of(new LimitId(value));
            } catch (IllegalArgumentException e) {
                throw new ProtocolException("renderer PROBLEM limitId is invalid", e);
            }
            parametersCanonical = "{\"limitId\":" + quote(value) + "}";
        } else {
            throw new ProtocolException("renderer PROBLEM parameters member set mismatch");
        }
        var capacityProblem = code == ProblemCode.RESOURCE_BUDGET_EXCEEDED
                || code == ProblemCode.RASTER_BUDGET_EXCEEDED
                || code == ProblemCode.OUTPUT_BUDGET_EXCEEDED
                || code == ProblemCode.RENDER_LAYOUT_TRACE_LIMIT_EXCEEDED;
        if (capacityProblem != limitId.isPresent()) {
            throw new ProtocolException("renderer PROBLEM capacity parameter shape mismatch");
        }
        var canonical = new StringBuilder("{\"contractVersion\":\"")
                .append(PROBLEM_CONTRACT_VERSION)
                .append("\",\"requestId\":\"").append(requestId)
                .append("\",\"code\":\"").append(codeText)
                .append("\",\"engineStage\":\"").append(engineStageText).append('"');
        occurrence.ifPresent(value -> canonical.append(",\"occurrenceId\":").append(quote(value)));
        resource.ifPresent(value -> canonical.append(",\"resourceId\":").append(quote(value)));
        canonical.append(",\"parameters\":").append(parametersCanonical).append('}');
        requireCanonical(payload, canonical.toString(), "PROBLEM");
        return new ParsedProblem(requestId, code, engineStage, occurrence, resource, limitId);
    }

    static ParsedResult parseResult(byte[] payload) throws ProtocolException {
        var node = readStrictObject(payload, "RESULT_METADATA");
        var hasQuality = node.has("quality");
        var expectedMembers = hasQuality ? 14 : 13;
        for (var member : List.of(
                "contractVersion", "requestId", "rendererProfile", "dslVersion",
                "layoutProfile", "outputProfile", "format", "mediaType", "widthPx",
                "heightPx", "dpi", "byteLength", "contentSha256")) {
            if (!node.has(member)) {
                throw new ProtocolException("renderer RESULT metadata member set mismatch");
            }
        }
        if (node.size() != expectedMembers) {
            throw new ProtocolException("renderer RESULT metadata member set mismatch");
        }
        var contractVersion = requiredText(node, "contractVersion");
        var requestId = requiredText(node, "requestId");
        var rendererProfile = requiredText(node, "rendererProfile");
        var dslVersion = requiredText(node, "dslVersion");
        var layoutProfile = requiredText(node, "layoutProfile");
        var outputProfile = requiredText(node, "outputProfile");
        var format = requiredText(node, "format");
        var mediaType = requiredText(node, "mediaType");
        var widthPx = positiveInt(node, "widthPx");
        var heightPx = positiveInt(node, "heightPx");
        var dpi = positiveInt(node, "dpi");
        var byteLength = positiveLong(node, "byteLength");
        var contentSha256 = requiredText(node, "contentSha256");
        if (dpi > 600) {
            throw new ProtocolException("renderer RESULT dpi is outside the public bound");
        }
        if (!RESULT_CONTRACT_VERSION.equals(contractVersion)
                || !"renderweave-render/1.0".equals(dslVersion)
                || !"renderweave-layout/1.0".equals(layoutProfile)) {
            throw new ProtocolException("renderer RESULT exact profile identity mismatch");
        }
        requireUuidForProtocol(requestId, "result requestId");
        requireProfile(rendererProfile, "result rendererProfile");
        if (!contentSha256.matches("[0-9a-f]{64}")) {
            throw new ProtocolException("renderer RESULT contentSha256 is invalid");
        }
        OptionalInt quality;
        String qualityMember = "";
        if ("renderweave-output-png/1.0".equals(outputProfile)) {
            if (hasQuality || !"PNG".equals(format) || !"image/png".equals(mediaType)) {
                throw new ProtocolException("renderer PNG RESULT shape mismatch");
            }
            quality = OptionalInt.empty();
        } else if ("renderweave-output-jpeg/1.0".equals(outputProfile)) {
            if (!hasQuality || !"JPEG".equals(format) || !"image/jpeg".equals(mediaType)) {
                throw new ProtocolException("renderer JPEG RESULT shape mismatch");
            }
            var qualityValue = positiveInt(node, "quality");
            if (qualityValue > 100) {
                throw new ProtocolException("renderer JPEG quality is invalid");
            }
            quality = OptionalInt.of(qualityValue);
            qualityMember = ",\"quality\":" + qualityValue;
        } else {
            throw new ProtocolException("renderer RESULT output profile is unknown");
        }
        var canonical = "{\"contractVersion\":\"" + RESULT_CONTRACT_VERSION
                + "\",\"requestId\":\"" + requestId
                + "\",\"rendererProfile\":\"" + rendererProfile
                + "\",\"dslVersion\":\"" + dslVersion
                + "\",\"layoutProfile\":\"" + layoutProfile
                + "\",\"outputProfile\":\"" + outputProfile
                + "\",\"format\":\"" + format
                + "\",\"mediaType\":\"" + mediaType
                + "\",\"widthPx\":" + widthPx
                + ",\"heightPx\":" + heightPx
                + ",\"dpi\":" + dpi
                + ",\"byteLength\":" + byteLength
                + ",\"contentSha256\":\"" + contentSha256 + "\""
                + qualityMember + "}";
        requireCanonical(payload, canonical, "RESULT_METADATA");
        return new ParsedResult(
                contractVersion,
                requestId,
                rendererProfile,
                dslVersion,
                layoutProfile,
                outputProfile,
                format,
                mediaType,
                widthPx,
                heightPx,
                dpi,
                quality,
                byteLength,
                contentSha256);
    }

    static ParsedImage parseResultImage(byte[] payload) throws ProtocolException {
        if (payload.length <= 16) {
            throw new ProtocolException("renderer RESULT_IMAGE must contain UUID and image bytes");
        }
        var buffer = ByteBuffer.wrap(payload, 0, 16);
        var requestId = new UUID(buffer.getLong(), buffer.getLong()).toString();
        requireUuidForProtocol(requestId, "result image requestId");
        return new ParsedImage(requestId, Arrays.copyOfRange(payload, 16, payload.length));
    }

    static String digest(String domain, byte[] payload) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(payload, "payload");
        var digest = sha256();
        digest.update(domain.getBytes(StandardCharsets.UTF_8));
        digest.update(payload);
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    static String rawSha256(byte[] payload) {
        return HexFormat.of().formatHex(sha256().digest(payload));
    }

    static void requireUuidV4(String value, String field) {
        if (value == null || !UUID_V4.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical lowercase UUID v4");
        }
    }

    static void requireSha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be sha256: plus 64 lowercase hex chars");
        }
    }

    private static void requireProfile(String value, String field) {
        if (value == null || !PROFILE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a canonical profile identity");
        }
    }

    private static JsonNode readStrictObject(byte[] bytes, String name) throws ProtocolException {
        try {
            JsonNode parsed = JSON.readTree(bytes);
            if (parsed == null || !parsed.isObject()) {
                throw new ProtocolException("renderer " + name + " must be one JSON object");
            }
            return parsed;
        } catch (tools.jackson.core.JacksonException e) {
            throw new ProtocolException("renderer " + name + " is not strict JSON", e);
        }
    }

    private static String requiredText(JsonNode node, String member) throws ProtocolException {
        var value = node.path(member);
        if (!value.isTextual()) {
            throw new ProtocolException("renderer JSON member " + member + " must be text");
        }
        return value.asText();
    }

    private static Optional<String> optionalText(JsonNode node, String member)
            throws ProtocolException {
        if (!node.has(member)) {
            return Optional.empty();
        }
        return Optional.of(requiredText(node, member));
    }

    private static int positiveInt(JsonNode node, String member) throws ProtocolException {
        var value = node.path(member);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw new ProtocolException("renderer JSON member " + member + " must be positive int");
        }
        return value.intValue();
    }

    private static long positiveLong(JsonNode node, String member) throws ProtocolException {
        var value = node.path(member);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw new ProtocolException("renderer JSON member " + member + " must be positive long");
        }
        return value.longValue();
    }

    private static EngineProblemStage parseEngineStage(String value) throws ProtocolException {
        return switch (value) {
            case "COMMAND_ADMISSION" -> EngineProblemStage.COMMAND_ADMISSION;
            case "REQUEST_CONTROL" -> EngineProblemStage.REQUEST_CONTROL;
            case "DOCUMENT_ADMISSION" -> EngineProblemStage.DOCUMENT_ADMISSION;
            case "OUTPUT_PREFLIGHT" -> EngineProblemStage.OUTPUT_PREFLIGHT;
            case "RESOURCE_PREPARATION" -> EngineProblemStage.RESOURCE_PREPARATION;
            case "LAYOUT" -> EngineProblemStage.LAYOUT;
            case "SHAPING" -> EngineProblemStage.SHAPING;
            case "RASTERIZATION" -> EngineProblemStage.RASTERIZATION;
            case "ENCODING" -> EngineProblemStage.ENCODING;
            case "TRACE_PROJECTION" -> EngineProblemStage.TRACE_PROJECTION;
            case "OUTPUT_SEAL" -> EngineProblemStage.OUTPUT_SEAL;
            default -> throw new ProtocolException("renderer PROBLEM engineStage is unknown");
        };
    }

    private static boolean isEngineProblemCode(ProblemCode value) {
        return switch (value) {
            case RENDER_INTERNAL_ERROR,
                    RENDER_REQUEST_CONFLICT,
                    RENDER_REQUEST_STATE_LOST,
                    RENDER_CANCELLED,
                    RENDER_DEADLINE_EXCEEDED,
                    RENDER_ENGINE_BUSY,
                    RESOURCE_LEASE_EXPIRED,
                    RESOURCE_BUDGET_EXCEEDED,
                    FETCH_FAILED,
                    LENGTH_MISMATCH,
                    HASH_MISMATCH,
                    MEDIA_MISMATCH,
                    DECODE_FAILED,
                    FONT_GLYPH_MISSING,
                    RASTER_BUDGET_EXCEEDED,
                    OUTPUT_BUDGET_EXCEEDED,
                    RENDER_LAYOUT_TRACE_LIMIT_EXCEEDED -> true;
            case RENDER_INPUT_LIMIT_EXCEEDED,
                    RENDER_INPUT_CONTENT_ENCODING_UNSUPPORTED,
                    TEMPLATE_NOT_FOUND,
                    TEMPLATE_DELETED,
                    TEMPLATE_DEPENDENCY_ERROR,
                    TEMPLATE_AUTHORITY_UNAVAILABLE,
                    TEMPLATE_CLOSURE_LIMIT_EXCEEDED,
                    TEMPLATE_CLOSURE_UNSTABLE,
                    DESIGN_DSL_LIMIT_EXCEEDED,
                    ASSET_BUDGET_EXCEEDED,
                    ASSET_NOT_FOUND,
                    ASSET_RESOLVE_NOT_FOUND,
                    ASSET_RESOLVE_DELETED,
                    ASSET_RESOLVE_KIND_MISMATCH,
                    ASSET_RESOLVE_UNAVAILABLE,
                    ASSET_RESOLVE_TIMEOUT,
                    CAPABILITY_BUDGET_EXCEEDED,
                    CAPABILITY_STATE_CONFLICT,
                    CAPABILITY_STATE_UNAVAILABLE,
                    CAPABILITY_RESULT_INVALID,
                    CAPABILITY_CLOCK_UNAVAILABLE,
                    CAPABILITY_ENTROPY_UNAVAILABLE,
                    CAPABILITY_PROFILE_UNAVAILABLE,
                    CAPABILITY_DEADLINE_EXCEEDED,
                    CAPABILITY_CANCELLED,
                    EXPRESSION_LIMIT_EXCEEDED,
                    EVALUATION_BUDGET_EXCEEDED,
                    EVALUATION_FAILED,
                    RENDER_DOCUMENT_LIMIT_EXCEEDED,
                    RENDER_DIAGNOSTIC_LIMIT_EXCEEDED -> false;
        };
    }

    private static void requireUuidForProtocol(String value, String field)
            throws ProtocolException {
        try {
            requireUuidV4(value, field);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(e.getMessage(), e);
        }
    }

    private static void requireCanonical(byte[] actual, String canonical, String name)
            throws ProtocolException {
        if (!MessageDigest.isEqual(actual, canonical.getBytes(StandardCharsets.UTF_8))) {
            throw new ProtocolException("renderer " + name + " is not canonical");
        }
    }

    private static String quote(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("JSON string encoding must be available", e);
        }
    }

    private static void requireCanonicalJsonObject(byte[] bytes, String name) {
        try {
            JsonNode parsed = JSON.readTree(bytes);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException(name + " must be one strict JSON object");
            }
            if (containsNull(parsed)) {
                throw new IllegalArgumentException(name + " must not contain null");
            }
            if (!MessageDigest.isEqual(bytes, JSON.writeValueAsBytes(parsed))) {
                throw new IllegalArgumentException(name + " must already be canonical JSON");
            }
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException(name + " must be strict JSON", e);
        }
    }

    private static boolean containsNull(JsonNode value) {
        if (value.isNull()) {
            return true;
        }
        for (var child : value) {
            if (containsNull(child)) {
                return true;
            }
        }
        return false;
    }

    private static String strictUtf8(byte[] bytes, String name) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(name + " must be strict UTF-8", e);
        }
    }

    private static String output(OutputSelection selection) {
        if (selection instanceof OutputSelection.Png png) {
            return "{\"profile\":\"renderweave-output-png/1.0\",\"dpi\":"
                    + png.dpi() + "}";
        }
        if (selection instanceof OutputSelection.Jpeg jpeg) {
            return "{\"profile\":\"renderweave-output-jpeg/1.0\",\"dpi\":"
                    + jpeg.dpi() + ",\"quality\":" + jpeg.quality() + "}";
        }
        throw new IllegalArgumentException("unknown output selection");
    }

    private static String stringArray(List<String> values) {
        var builder = new StringBuilder("[");
        for (var i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(values.get(i)).append('"');
        }
        return builder.append(']').toString();
    }

    private static byte[] uuidNetworkBytes(String value) {
        var uuid = UUID.fromString(value);
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        var bytes = new byte[length];
        var offset = 0;
        while (offset < length) {
            var count = input.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new EOFException("renderer frame ended before declared length");
            }
            if (count == 0) {
                continue;
            }
            offset += count;
        }
        return bytes;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available", e);
        }
    }
}
