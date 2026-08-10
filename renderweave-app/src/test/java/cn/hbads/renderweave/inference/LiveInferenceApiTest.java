package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateAssessment;
import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateBundle;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateField;
import cn.hbads.renderweave.inference.candidate.CandidateJsonCodec;
import cn.hbads.renderweave.inference.candidate.CandidateResolution;
import cn.hbads.renderweave.inference.candidate.CandidateSchema;
import cn.hbads.renderweave.inference.candidate.CandidateSource;
import cn.hbads.renderweave.inference.candidate.CandidateValue;
import cn.hbads.renderweave.inference.candidate.CandidateValueKind;
import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;
import cn.hbads.renderweave.inference.provider.ProviderUsage;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "renderweave.inference.live-enabled=true",
        "renderweave.inference.live-upload-enabled=true",
        "renderweave.inference.live-poll-millis=60000",
        "renderweave.inference.blob-root=target/test-live-inference-api-blobs",
        "DASHSCOPE_API_KEY=",
        "DASHSCOPE_API_KEY_FILE="
})
@AutoConfigureMockMvc
@Import(LiveInferenceApiTest.ProviderConfiguration.class)
class LiveInferenceApiTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private InferenceRunStore runs;

    @Autowired
    private MultipartProperties multipartProperties;

    @Autowired
    private tools.jackson.databind.ObjectMapper json;

    @BeforeEach
    void clearData() {
        jdbcClient.sql("delete from inference_provider_reservation").update();
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
    }

    @Test
    void multipartProductUploadQueuesRunsAndBackgroundWorkerProducesReview() throws Exception {
        mockMvc.perform(get("/api/v1/inference-runs/live-availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.uploadEnabled").value(true));

        assertThat(multipartProperties.getMaxFileSize().toBytes()).isEqualTo(11L * 1024 * 1024);
        assertThat(multipartProperties.getMaxRequestSize().toBytes()).isEqualTo(34L * 1024 * 1024);
        var imageBytes = largeValidPng();
        assertThat(imageBytes.length).isBetween(1024 * 1024 + 1, 10 * 1024 * 1024);
        var metadata = new MockMultipartFile(
                "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                """
                        {"profileId":"dashscope-qwen37-flash-product-v2","mode":"IMAGE_ONLY",
                         "inputClassification":"USER_PROVIDED","externalTransferConfirmed":true,
                         "experimentalProfileConfirmed":true,"costLimitMicrosCny":250000}
                        """.getBytes(StandardCharsets.UTF_8)
        );
        var image = new MockMultipartFile(
                "images", "synthetic.png", MediaType.IMAGE_PNG_VALUE, imageBytes
        );
        var response = mockMvc.perform(multipart("/api/v1/inference-runs/live")
                        .file(metadata).file(image)
                        .header("Idempotency-Key", "live-api-synthetic"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profileId").value("dashscope-qwen37-flash-product-v2"))
                .andExpect(jsonPath("$.costLimitMicrosCny").value(250000))
                .andReturn().getResponse().getContentAsString();
        var runId = UUID.fromString(json.readTree(response).path("runId").asText());

        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline
                && runs.find(runId).orElseThrow().state() != InferenceRunState.REVIEW_REQUIRED) {
            Thread.sleep(20);
        }
        assertThat(runs.find(runId).orElseThrow().state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        mockMvc.perform(get("/api/v1/inference-runs/{runId}/candidate", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.sourceReference").value("user-upload"))
                .andExpect(jsonPath("$.images.length()").value(1))
                .andExpect(jsonPath("$.jsonSampleCount").value(0));
        assertThat(jdbcClient.sql("select count(*) from inference_provider_reservation")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void productUploadRejectsCostLimitAboveProductBoundaryBeforeCreatingRun() throws Exception {
        var metadata = new MockMultipartFile(
                "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                """
                        {"profileId":"dashscope-qwen37-flash-product-v2","mode":"IMAGE_ONLY",
                         "inputClassification":"USER_PROVIDED","externalTransferConfirmed":true,
                         "experimentalProfileConfirmed":true,"costLimitMicrosCny":100000001}
                        """.getBytes(StandardCharsets.UTF_8)
        );
        var image = new MockMultipartFile(
                "images", "sample.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1}
        );

        mockMvc.perform(multipart("/api/v1/inference-runs/live")
                        .file(metadata).file(image)
                        .header("Idempotency-Key", "live-api-cost-too-high"))
                .andExpect(status().isBadRequest());

        assertThat(jdbcClient.sql("select count(*) from inference_run").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from inference_provider_reservation")
                .query(Long.class).single()).isZero();
    }

    @Test
    void productUploadAcceptsAnOmittedRunCostLimit() throws Exception {
        var metadata = new MockMultipartFile(
                "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                """
                        {"profileId":"dashscope-qwen37-flash-product-v2","mode":"IMAGE_ONLY",
                         "inputClassification":"USER_PROVIDED","externalTransferConfirmed":true,
                         "experimentalProfileConfirmed":true}
                        """.getBytes(StandardCharsets.UTF_8)
        );
        var image = new MockMultipartFile(
                "images", "sample.png", MediaType.IMAGE_PNG_VALUE, smallValidPng()
        );
        var response = mockMvc.perform(multipart("/api/v1/inference-runs/live")
                        .file(metadata).file(image)
                        .header("Idempotency-Key", "live-api-no-run-limit"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.costLimitMicrosCny").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        var runId = UUID.fromString(json.readTree(response).path("runId").asText());

        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline
                && runs.find(runId).orElseThrow().state() != InferenceRunState.REVIEW_REQUIRED) {
            Thread.sleep(20);
        }
        assertThat(runs.find(runId).orElseThrow().costLimitMicrosCny()).isNull();
        assertThat(runs.find(runId).orElseThrow().state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
    }

    private static byte[] largeValidPng() throws Exception {
        var image = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
        var random = new Random(42L);
        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) image.setRGB(x, y, random.nextInt());
        }
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
            return output.toByteArray();
        }
    }

    private static byte[] smallValidPng() throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG writer unavailable");
            return output.toByteArray();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean
        @Primary
        InferenceProvider syntheticProvider() {
            var codec = new CandidateJsonCodec();
            return request -> {
                var schemaId = UUID.nameUUIDFromBytes((request.runId() + ":schema").getBytes(StandardCharsets.UTF_8));
                var fieldId = UUID.nameUUIDFromBytes((request.runId() + ":field").getBytes(StandardCharsets.UTF_8));
                var evidence = CandidateEvidence.image(
                        request.images().getFirst().artifactId(),
                        new CandidateBoundingBox(500, 500, 9_500, 2_500)
                );
                var assessment = CandidateAssessment.ai(
                        9_000, true, CandidateResolution.NOT_REQUIRED, List.of(evidence)
                );
                var candidate = new CandidateBundle(
                        CandidateBundle.CONTRACT_VERSION, schemaId,
                        List.of(new CandidateSchema(
                                schemaId, "synthetic-product", "合成商品", CandidateSource.AI, assessment,
                                List.of(new CandidateField(
                                        fieldId, "title", "标题", false,
                                        CandidateValue.scalar(CandidateValueKind.TEXT), CandidateSource.AI, assessment
                                ))
                        ))
                );
                return new ProviderInferenceResponse(
                        codec.write(candidate), "test-request", request.profile().model(),
                        new ProviderUsage(1_000, 500), "stop"
                );
            };
        }
    }
}
