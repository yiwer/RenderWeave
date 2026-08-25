package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.RenderOutput;
import cn.hbads.renderweave.rendering.api.RenderingApplication;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOperationId;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOutcome;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderPurpose;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RenderingControllerTest {

    private static final String INPUT_MEDIA_TYPE =
            "application/vnd.renderweave.render-input+json;version=1.0";
    private static final String PROBLEM_MEDIA_TYPE =
            "application/vnd.renderweave.render-problem+json;version=1.0";
    private static final String OPERATION_ID = "00000000-0000-4000-8000-0000000000a1";
    private static final byte[] PNG = new byte[] { 1, 2, 3 };

    @Test
    void formalPngUsesServerSelectedPurposeAndPublishesOnlyCompleteVerifiedResult()
            throws Exception {
        var seen = new AtomicReference<RenderingApplication.RenderCommand>();
        RenderingApplication application = (invocation, command) -> {
            seen.set(command);
            return new RenderOutcome.Rendered(operationId(), png());
        };

        mvc(application).perform(post("/api/v1/templates/{templateId}/render", "template-a")
                        .queryParam("format", "PNG")
                        .contentType(INPUT_MEDIA_TYPE)
                        .content("{\"rootDocument\":{}}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG))
                .andExpect(header().longValue("Content-Length", PNG.length))
                .andExpect(header().string("Content-Digest", contentDigest(PNG)))
                .andExpect(header().string(
                        "RenderWeave-Result-Version", "renderweave-render-result/1.0"))
                .andExpect(header().string("RenderWeave-Request-Id", OPERATION_ID))
                .andExpect(header().string(
                        "RenderWeave-Renderer-Profile", "renderweave-renderer/1.0"))
                .andExpect(header().string(
                        "RenderWeave-DSL-Version", "renderweave-render/1.0"))
                .andExpect(header().string(
                        "RenderWeave-Layout-Profile", "renderweave-layout/1.0"))
                .andExpect(header().string(
                        "RenderWeave-Output-Profile", "renderweave-output-png/1.0"))
                .andExpect(header().string("RenderWeave-Format", "PNG"))
                .andExpect(header().string("RenderWeave-Width-Px", "10"))
                .andExpect(header().string("RenderWeave-Height-Px", "20"))
                .andExpect(header().string("RenderWeave-DPI", "96"))
                .andExpect(header().doesNotExist("RenderWeave-Quality"));

        assertEquals(RenderPurpose.FORMAL_OUTPUT, seen.get().purpose());
        assertEquals(new OutputSelection.Png(96), seen.get().outputSelection());
        assertArrayEquals(
                "{\"rootDocument\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                seen.get().rawRenderInputUtf8());
    }

    @Test
    void authoritativePreviewSelectsPreviewPurposeAndExpandsJpegQualityDefault()
            throws Exception {
        var seen = new AtomicReference<RenderingApplication.RenderCommand>();
        var jpegBytes = new byte[] { 4, 5, 6, 7 };
        RenderingApplication application = (invocation, command) -> {
            seen.set(command);
            return new RenderOutcome.Rendered(
                    operationId(),
                    jpeg(jpegBytes, 120, 90));
        };

        mvc(application).perform(post(
                                "/api/v1/templates/{templateId}/authoritative-preview",
                                "template-a")
                        .queryParam("format", "JPEG")
                        .queryParam("dpi", "120")
                        .contentType(INPUT_MEDIA_TYPE)
                        .content("{\"rootDocument\":{}}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(jpegBytes))
                .andExpect(header().string("RenderWeave-Quality", "90"));

        assertEquals(RenderPurpose.AUTHORITATIVE_PREVIEW, seen.get().purpose());
        assertEquals(new OutputSelection.Jpeg(120, 90), seen.get().outputSelection());
    }

    @Test
    void transportAdmissionRejectsBeforeCallingTheApplication() throws Exception {
        var calls = new AtomicInteger();
        RenderingApplication application = (invocation, command) -> {
            calls.incrementAndGet();
            return new RenderOutcome.RendererUnavailable(operationId());
        };
        var mvc = mvc(application);

        mvc.perform(post("/api/v1/templates/template-a/render")
                        .queryParam("format", "PNG")
                        .header("Content-Encoding", "gzip")
                        .contentType(INPUT_MEDIA_TYPE)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM_MEDIA_TYPE))
                .andExpect(jsonPath("$.code")
                        .value("RENDER_INPUT_CONTENT_ENCODING_UNSUPPORTED"));
        mvc.perform(post("/api/v1/templates/template-a/render")
                        .queryParam("format", "PNG")
                        .queryParam("quality", "90")
                        .contentType(INPUT_MEDIA_TYPE)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RENDER_REQUEST_INVALID"));
        mvc.perform(post("/api/v1/templates/template-a/render")
                        .queryParam("format", "PNG")
                        .queryParam("dpi", "601")
                        .contentType(INPUT_MEDIA_TYPE)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RENDER_REQUEST_INVALID"));
        mvc.perform(post("/api/v1/templates/template-a/render")
                        .queryParam("format", "PNG")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code")
                        .value("RENDER_INPUT_MEDIA_TYPE_UNSUPPORTED"));
        mvc.perform(post("/api/v1/templates/template-a/render")
                        .queryParam("format", "PNG")
                        .contentType(INPUT_MEDIA_TYPE)
                        .content(new byte[8 * 1024 * 1024 + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("RENDER_INPUT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.parameters.limitId")
                        .value("renderInput.entityUtf8Bytes"));

        assertEquals(0, calls.get());
    }

    @Test
    void exactEightMebibyteEntityReachesTheApplication() throws Exception {
        var calls = new AtomicInteger();
        RenderingApplication application = (invocation, command) -> {
            calls.incrementAndGet();
            assertEquals(8 * 1024 * 1024, command.rawRenderInputUtf8().length);
            return new RenderOutcome.RendererUnavailable(operationId());
        };

        mvc(application).perform(post("/api/v1/templates/template-a/render")
                        .queryParam("format", "PNG")
                        .header("Content-Encoding", "identity")
                        .contentType(INPUT_MEDIA_TYPE)
                        .content(new byte[8 * 1024 * 1024]))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RENDERER_UNAVAILABLE"));

        assertEquals(1, calls.get());
    }

    @Test
    void closedApplicationOutcomesNeverReleaseImageHeaders() throws Exception {
        RenderingApplication unavailable = (invocation, command) ->
                new RenderOutcome.RendererUnavailable(operationId());

        mvc(unavailable).perform(post("/api/v1/templates/template-a/render")
                        .queryParam("format", "PNG")
                        .contentType(INPUT_MEDIA_TYPE)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(PROBLEM_MEDIA_TYPE))
                .andExpect(jsonPath("$.contractVersion")
                        .value("renderweave-render-problem/1.0"))
                .andExpect(jsonPath("$.renderOperationId").value(OPERATION_ID))
                .andExpect(jsonPath("$.code").value("RENDERER_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Content-Digest"))
                .andExpect(header().doesNotExist("RenderWeave-Result-Version"));

        var problem = RenderingProblem.ofLimit(
                RenderingProblem.ProblemCode.RENDER_DOCUMENT_LIMIT_EXCEEDED,
                EvaluationStage.DOCUMENT_SEAL,
                new RenderingProblem.LimitId("renderDocument.entityBytes"));
        RenderingApplication rejected = (invocation, command) ->
                new RenderOutcome.Rejected(operationId(), problem);

        mvc(rejected).perform(post("/api/v1/templates/template-a/render")
                        .queryParam("format", "PNG")
                        .contentType(INPUT_MEDIA_TYPE)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RENDER_DOCUMENT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.stage").value("DOCUMENT_SEAL"))
                .andExpect(jsonPath("$.parameters.limitId")
                        .value("renderDocument.entityBytes"));
    }

    private static MockMvc mvc(RenderingApplication application) {
        return MockMvcBuilders.standaloneSetup(new RenderingController(application)).build();
    }

    private static RenderOperationId operationId() {
        return new RenderOperationId(OPERATION_ID);
    }

    private static RenderOutput png() {
        return output(
                PNG,
                "renderweave-output-png/1.0",
                "PNG",
                "image/png",
                96,
                OptionalInt.empty());
    }

    private static RenderOutput jpeg(byte[] bytes, int dpi, int quality) {
        return output(
                bytes,
                "renderweave-output-jpeg/1.0",
                "JPEG",
                "image/jpeg",
                dpi,
                OptionalInt.of(quality));
    }

    private static RenderOutput output(
            byte[] bytes,
            String outputProfile,
            String format,
            String mediaType,
            int dpi,
            OptionalInt quality
    ) {
        return new RenderOutput(
                bytes,
                "renderweave-render-result/1.0",
                "renderweave-renderer/1.0",
                "renderweave-render/1.0",
                "renderweave-layout/1.0",
                outputProfile,
                format,
                mediaType,
                10,
                20,
                dpi,
                quality,
                bytes.length,
                sha256(bytes));
    }

    private static String contentDigest(byte[] bytes) {
        return "sha-256=:" + Base64.getEncoder().encodeToString(digest(bytes)) + ":";
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest(bytes));
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
