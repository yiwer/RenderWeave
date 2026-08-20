package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.rendering.spi.RenderEngine.RendererCommand;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Primary Java replay for the frozen renderer process wire vectors (TV1-T22). */
class RendererProcessProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode VECTORS = loadVectors();

    @Test
    void exactFramesMatchSharedCrossLanguageVectors() throws Exception {
        for (var vector : VECTORS.path("cases")) {
            var payload = vector.has("canonicalJson")
                    ? vector.path("canonicalJson").asText().getBytes(StandardCharsets.UTF_8)
                    : Base64.getDecoder().decode(vector.path("payloadBase64").asText());
            var type = RendererProcessProtocol.FrameType.fromWire(
                    vector.path("frameType").intValue());
            var encoded = RendererProcessProtocol.encodeFrame(type, payload);

            assertArrayEquals(
                    Base64.getDecoder().decode(vector.path("expectedFrameBase64").asText()),
                    encoded,
                    vector.path("id").asText());

            var decoded = RendererProcessProtocol.readFrame(
                    new ByteArrayInputStream(encoded), 4096);
            assertEquals(type, decoded.type(), vector.path("id").asText());
            assertArrayEquals(payload, decoded.payload(), vector.path("id").asText());
        }
    }

    @Test
    void commandEncoderMatchesCanonicalBytesAndBothFrozenDigests() {
        var vector = caseById("png-command");
        var command = new RendererCommand(
                "renderweave-render-command/1.0",
                new RenderRequestId(vector.path("requestId").asText()),
                "renderweave-renderer/1.0",
                Instant.parse(vector.path("deadlineAt").asText()).toEpochMilli(),
                vector.path("renderDocumentDigest").asText(),
                vector.path("documentCanonicalJson").asText().getBytes(StandardCharsets.UTF_8),
                new OutputSelection.Png(96),
                false);

        var encoded = RendererProcessProtocol.encodeCommand(command);
        assertEquals(vector.path("canonicalJson").asText(),
                new String(encoded.canonicalJsonUtf8(), StandardCharsets.UTF_8));
        assertEquals(vector.path("rendererCommandDigest").asText(), encoded.commandDigest());
        assertArrayEquals(
                Base64.getDecoder().decode(vector.path("expectedFrameBase64").asText()),
                RendererProcessProtocol.encodeFrame(
                        RendererProcessProtocol.FrameType.COMMAND,
                        encoded.canonicalJsonUtf8()));
    }

    @Test
    void commandEncoderRejectsNoncanonicalAndNullDocumentWithoutRepair() {
        var noncanonical = "{\"canvas\":{}, \"resources\":[]}".getBytes(StandardCharsets.UTF_8);
        var containingNull = "{\"canvas\":null}".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> RendererProcessProtocol.encodeCommand(commandWithDocument(noncanonical)));
        assertThrows(IllegalArgumentException.class,
                () -> RendererProcessProtocol.encodeCommand(commandWithDocument(containingNull)));
    }

    @Test
    void commandEncoderRejectsDeadlineOutsideExactFourDigitUtcWireShape() {
        assertThrows(IllegalArgumentException.class,
                () -> RendererProcessProtocol.encodeCommand(commandWithDeadline(Long.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class,
                () -> RendererProcessProtocol.encodeCommand(commandWithDeadline(
                        Instant.parse("0000-01-01T00:00:00Z").toEpochMilli())));
    }

    @Test
    void frameDecoderRejectsZeroUnknownOversizeAndTruncation() {
        assertThrows(RendererProcessProtocol.ProtocolException.class,
                () -> RendererProcessProtocol.readFrame(
                        new ByteArrayInputStream(new byte[]{0, 0, 0, 0}), 16));
        assertThrows(RendererProcessProtocol.ProtocolException.class,
                () -> RendererProcessProtocol.readFrame(
                        new ByteArrayInputStream(new byte[]{0, 0, 0, 1, 0x7f}), 16));
        assertThrows(RendererProcessProtocol.ProtocolException.class,
                () -> RendererProcessProtocol.readFrame(
                        new ByteArrayInputStream(new byte[]{0, 0, 0, 17}), 16));
        assertThrows(EOFException.class,
                () -> RendererProcessProtocol.readFrame(
                        new ByteArrayInputStream(new byte[]{0, 0, 0, 2, 1}), 16));
    }

    @Test
    void helloProblemAndSplitResultPayloadsAreStrictlyAdmitted() throws Exception {
        var manifest = VECTORS.path("authorityContext").path("machineManifestSha256").asText();
        assertEquals(caseById("server-hello").path("canonicalJson").asText(),
                new String(RendererProcessProtocol.encodeServerHelloForT22(manifest),
                        StandardCharsets.UTF_8));
        RendererProcessProtocol.validateServerHelloForT22(
                caseById("server-hello").path("canonicalJson").asText()
                        .getBytes(StandardCharsets.UTF_8),
                manifest);
        var problem = RendererProcessProtocol.parseProblem(
                caseById("problem").path("canonicalJson").asText()
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals("123e4567-e89b-42d3-a456-426614174000", problem.requestId());
        var result = RendererProcessProtocol.parseResult(
                caseById("png-result-metadata").path("canonicalJson").asText()
                        .getBytes(StandardCharsets.UTF_8));
        var image = RendererProcessProtocol.parseResultImage(
                Base64.getDecoder().decode(caseById("png-result-image")
                        .path("payloadBase64").asText()));
        assertEquals(result.requestId(), image.requestId());
        assertEquals(result.byteLength(), image.imageBytes().length);
        assertEquals(result.contentSha256(), RendererProcessProtocol.rawSha256(image.imageBytes()));
    }

    @Test
    void problemParserRejectsNonEngineCodesAndIllegalCapacityParameters() {
        var problem = caseById("problem").path("canonicalJson").asText();
        assertThrows(RendererProcessProtocol.ProtocolException.class,
                () -> RendererProcessProtocol.parseProblem(problem.replace(
                                "RENDER_INTERNAL_ERROR", "RENDER_INPUT_LIMIT_EXCEEDED")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(RendererProcessProtocol.ProtocolException.class,
                () -> RendererProcessProtocol.parseProblem(problem.replace(
                                "\"parameters\":{}",
                                "\"parameters\":{\"limitId\":\"rw-limit\"}")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThrows(RendererProcessProtocol.ProtocolException.class,
                () -> RendererProcessProtocol.parseProblem(problem.replace(
                                "RENDER_INTERNAL_ERROR", "RESOURCE_BUDGET_EXCEEDED")
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void renderingRequestIdentityIsCanonicalLowercaseUuidV4AtItsOwningBoundary() {
        assertEquals("123e4567-e89b-42d3-a456-426614174000",
                new RenderRequestId("123e4567-e89b-42d3-a456-426614174000").value());
        assertThrows(IllegalArgumentException.class, () -> new RenderRequestId("render-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new RenderRequestId("123E4567-E89B-42D3-A456-426614174000"));
        assertThrows(IllegalArgumentException.class,
                () -> new RenderRequestId("123e4567-e89b-12d3-a456-426614174000"));
    }

    private static RendererCommand commandWithDocument(byte[] document) {
        var vector = caseById("png-command");
        return new RendererCommand(
                "renderweave-render-command/1.0",
                new RenderRequestId(vector.path("requestId").asText()),
                "renderweave-renderer/1.0",
                Instant.parse(vector.path("deadlineAt").asText()).toEpochMilli(),
                RendererProcessProtocol.digest(
                        RendererProcessProtocol.DOCUMENT_DIGEST_DOMAIN, document),
                document,
                new OutputSelection.Png(96),
                false);
    }

    private static RendererCommand commandWithDeadline(long deadlineAtEpochMilli) {
        var vector = caseById("png-command");
        return new RendererCommand(
                "renderweave-render-command/1.0",
                new RenderRequestId(vector.path("requestId").asText()),
                "renderweave-renderer/1.0",
                deadlineAtEpochMilli,
                vector.path("renderDocumentDigest").asText(),
                vector.path("documentCanonicalJson").asText().getBytes(StandardCharsets.UTF_8),
                new OutputSelection.Png(96),
                false);
    }

    private static JsonNode caseById(String id) {
        for (var vector : VECTORS.path("cases")) {
            if (id.equals(vector.path("id").asText())) {
                return vector;
            }
        }
        throw new IllegalStateException("missing vector " + id);
    }

    private static JsonNode loadVectors() {
        try {
            var cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            for (var i = 0; i < 5 && cursor != null; i++, cursor = cursor.getParent()) {
                var candidate = cursor.resolve("renderer/protocol-vectors-v1.json");
                if (Files.isRegularFile(candidate)) {
                    return JSON.readTree(Files.readAllBytes(candidate));
                }
            }
            throw new IllegalStateException("renderer protocol vectors not found from Maven working directory");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
