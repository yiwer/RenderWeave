package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.RenderOutput;
import cn.hbads.renderweave.rendering.api.RenderingApplication;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOperationId;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOutcome;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderPurpose;
import cn.hbads.renderweave.template.internal.TemplateModule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CandidatePreviewControllerTest {

    private static final String INPUT_MEDIA_TYPE =
            "application/vnd.renderweave.render-input+json;version=1.0";
    private static final String OPERATION_ID = "00000000-0000-4000-8000-0000000000a1";

    @Test
    void defaultSwitchDoesNotPublishTheCandidateController() {
        new ApplicationContextRunner()
                .withUserConfiguration(CandidatePreviewController.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CandidatePreviewController.class));
    }

    @Test
    void loopbackPngDelegatesToThePreviewPurposeAndAddsCandidateDisclosure() throws Exception {
        var seen = new AtomicReference<RenderingApplication.RenderCommand>();
        var png = new byte[] { 1, 2, 3 };
        RenderingApplication application = (invocation, command) -> {
            seen.set(command);
            return new RenderOutcome.Rendered(operationId(), output(
                    png,
                    "renderweave-output-png/1.0",
                    "PNG",
                    "image/png",
                    96,
                    OptionalInt.empty()));
        };

        mvc(application).perform(candidatePost("PNG")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(content().bytes(png))
                .andExpect(header().string(
                        CandidatePreviewController.CANDIDATE_STATUS_HEADER,
                        CandidatePreviewController.NOT_CERTIFIED))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("RenderWeave-Renderer-Profile",
                        "renderweave-renderer/1.0"));

        assertEquals(RenderPurpose.AUTHORITATIVE_PREVIEW, seen.get().purpose());
    }

    @Test
    void loopbackJpegPreservesExactDeliveryMetadataAndCandidateDisclosure() throws Exception {
        var jpeg = new byte[] { 4, 5, 6, 7 };
        RenderingApplication application = (invocation, command) ->
                new RenderOutcome.Rendered(operationId(), output(
                        jpeg,
                        "renderweave-output-jpeg/1.0",
                        "JPEG",
                        "image/jpeg",
                        144,
                        OptionalInt.of(82)));

        mvc(application).perform(post(
                                "/internal/candidate-preview/templates/{templateId}",
                                "template-a")
                        .with(request -> {
                            request.setRemoteAddr("0:0:0:0:0:0:0:1");
                            return request;
                        })
                        .queryParam("format", "JPEG")
                        .queryParam("dpi", "144")
                        .queryParam("quality", "82")
                        .contentType(INPUT_MEDIA_TYPE)
                        .content("{\"rootDocument\":{}}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(jpeg))
                .andExpect(header().longValue("Content-Length", jpeg.length))
                .andExpect(header().string("Content-Digest", contentDigest(jpeg)))
                .andExpect(header().string("RenderWeave-Format", "JPEG"))
                .andExpect(header().string("RenderWeave-DPI", "144"))
                .andExpect(header().string("RenderWeave-Quality", "82"))
                .andExpect(header().string(
                        CandidatePreviewController.CANDIDATE_STATUS_HEADER,
                        CandidatePreviewController.NOT_CERTIFIED));
    }

    @Test
    void nonLoopbackRequestIsHiddenBeforeApplicationExecution() throws Exception {
        var calls = new AtomicInteger();
        RenderingApplication application = (invocation, command) -> {
            calls.incrementAndGet();
            return new RenderOutcome.RendererUnavailable(operationId());
        };

        mvc(application).perform(candidatePost("PNG")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        }))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(
                        CandidatePreviewController.CANDIDATE_STATUS_HEADER));

        assertEquals(0, calls.get());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            candidatePost(String format) {
        return post("/internal/candidate-preview/templates/{templateId}", "template-a")
                .queryParam("format", format)
                .contentType(INPUT_MEDIA_TYPE)
                .content("{\"rootDocument\":{}}");
    }

    private static MockMvc mvc(RenderingApplication application) {
        return MockMvcBuilders.standaloneSetup(new CandidatePreviewController(
                new CandidatePreviewApplication(application),
                TemplateModule.designInputExpressionCapacityAuthority()
        )).build();
    }

    private static RenderOperationId operationId() {
        return new RenderOperationId(OPERATION_ID);
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
