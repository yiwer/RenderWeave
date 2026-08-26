package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.RenderingApplication;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOutcome;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority.Accepted;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority.Invalid;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority.InvalidReason;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority.Observation;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority.Rejected;
import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority.Terminal;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RenderingControllerCapacityReservationTest {

    private static final String INPUT_MEDIA_TYPE =
            "application/vnd.renderweave.render-input+json;version=1.0";
    private static final Terminal ENCODING_TERMINAL = new Terminal(
            "RENDER_INPUT_CONTENT_ENCODING_UNSUPPORTED",
            "RENDER_INPUT_ADMISSION",
            "INPUT_ADMISSION",
            "ZERO_EVALUATION_DOCUMENT_OUTPUT",
            List.of(
                    "capabilityStates=0",
                    "evaluations=0",
                    "renderDocuments=0",
                    "engineCommands=0",
                    "renderOutputs=0"
            )
    );

    @Test
    void derivesNormalizedContentEncodingAndMapsTheSharedTerminalBeforeApplicationWork()
            throws Exception {
        var calls = new AtomicInteger();
        RenderingApplication application = (invocation, command) -> {
            calls.incrementAndGet();
            return new RenderOutcome.RendererUnavailable(
                    new RenderingApplication.RenderOperationId(
                            "00000000-0000-4000-8000-0000000000a1"));
        };
        var authority = new RecordingEncodingAuthority(false);
        var mvc = MockMvcBuilders.standaloneSetup(
                new RenderingController(application, authority)).build();

        mvc.perform(request())
                .andExpect(status().isServiceUnavailable());
        mvc.perform(request().header("Content-Encoding", " Identity "))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(request().header("Content-Encoding", "gzip"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("RENDER_INPUT_CONTENT_ENCODING_UNSUPPORTED"))
                .andExpect(jsonPath("$.stage").value("INPUT_ADMISSION"));

        assertEquals(2, calls.get());
        assertEquals(List.of(
                new Observation("renderInput.contentEncoding", "identity"),
                new Observation("renderInput.contentEncoding", "identity"),
                new Observation("renderInput.contentEncoding", "gzip")
        ), authority.observations);
    }

    @Test
    void invalidContentEncodingAuthorityDecisionFailsClosed() throws Exception {
        var calls = new AtomicInteger();
        RenderingApplication application = (invocation, command) -> {
            calls.incrementAndGet();
            return new RenderOutcome.RendererUnavailable(
                    new RenderingApplication.RenderOperationId(
                            "00000000-0000-4000-8000-0000000000a1"));
        };
        var mvc = MockMvcBuilders.standaloneSetup(new RenderingController(
                application,
                new RecordingEncodingAuthority(true)
        )).build();

        mvc.perform(request())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("RENDER_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.stage").value("INPUT_ADMISSION"));

        assertEquals(0, calls.get());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            request() {
        return post("/api/v1/templates/template-a/render")
                .queryParam("format", "PNG")
                .contentType(INPUT_MEDIA_TYPE)
                .content("{\"rootDocument\":{}}");
    }

    private static final class RecordingEncodingAuthority
            implements DesignInputExpressionCapacityAuthority {
        private final boolean invalid;
        private final List<Observation> observations = new ArrayList<>();

        private RecordingEncodingAuthority(boolean invalid) {
            this.invalid = invalid;
        }

        @Override
        public Decision evaluate(Observation observation) {
            observations.add(observation);
            if (invalid) {
                return new Invalid(InvalidReason.INVALID_OBSERVED_VALUE);
            }
            return "identity".equals(observation.observedValue())
                    ? new Accepted()
                    : new Rejected(ENCODING_TERMINAL);
        }
    }
}
