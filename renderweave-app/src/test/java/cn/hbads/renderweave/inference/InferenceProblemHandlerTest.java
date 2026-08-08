package cn.hbads.renderweave.inference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceProblemHandlerTest {
    @Test
    void oversizedMultipartUsesTheStableProblemContract() {
        var request = new MockHttpServletRequest("POST", "/api/v1/inference-runs/live");

        var response = new InferenceProblemHandler().payloadTooLarge(
                new MaxUploadSizeExceededException(34L * 1024 * 1024), request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/problem+json");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INFERENCE_PAYLOAD_TOO_LARGE");
        assertThat(response.getBody().instance()).isEqualTo("/api/v1/inference-runs/live");
    }
}
