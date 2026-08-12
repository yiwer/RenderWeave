package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.provider.InferenceProvider;
import cn.hbads.renderweave.inference.provider.ProviderInferenceResponse;
import cn.hbads.renderweave.inference.provider.ProviderUsage;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.inference.vision.DocumentVisionCapability;
import cn.hbads.renderweave.inference.vision.DocumentVisionObservation;
import cn.hbads.renderweave.inference.vision.DocumentVisionPreprocessor;
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
                .andExpect(jsonPath("$.uploadEnabled").value(true))
                .andExpect(jsonPath("$.profiles[*].available",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(true))))
                .andExpect(jsonPath("$.profiles[*].unavailabilityCode",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));

        assertThat(multipartProperties.getMaxFileSize().toBytes()).isEqualTo(11L * 1024 * 1024);
        assertThat(multipartProperties.getMaxRequestSize().toBytes()).isEqualTo(34L * 1024 * 1024);
        var imageBytes = largeValidPng();
        assertThat(imageBytes.length).isBetween(1024 * 1024 + 1, 10 * 1024 * 1024);
        var metadata = new MockMultipartFile(
                "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                """
                        {"profileId":"dashscope-qwen37-plus-product-v44-hybrid-generic","mode":"IMAGE_ONLY",
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
                .andExpect(jsonPath("$.profileId").value("dashscope-qwen37-plus-product-v44-hybrid-generic"))
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
                .query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void multipartProductUploadDownscalesAnOversizedDesignImageBeforeQueuing() throws Exception {
        var metadata = new MockMultipartFile(
                "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                """
                        {"profileId":"dashscope-qwen37-plus-product-v44-hybrid-generic","mode":"IMAGE_ONLY",
                         "inputClassification":"USER_PROVIDED","externalTransferConfirmed":true,
                         "experimentalProfileConfirmed":true,"costLimitMicrosCny":5000000}
                        """.getBytes(StandardCharsets.UTF_8)
        );
        var image = new MockMultipartFile(
                "images", "wide-design.png", MediaType.IMAGE_PNG_VALUE, wideValidPng()
        );
        var response = mockMvc.perform(multipart("/api/v1/inference-runs/live")
                        .file(metadata).file(image)
                        .header("Idempotency-Key", "live-api-wide-design"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var runId = UUID.fromString(json.readTree(response).path("runId").asText());

        assertThat(jdbcClient.sql("""
                        select artifact.width
                        from inference_run_input input
                        join inference_artifact artifact on artifact.artifact_id = input.artifact_id
                        where input.run_id = :runId and input.input_kind = 'IMAGE'
                        """)
                .param("runId", runId)
                .query(Integer.class).single()).isEqualTo(4096);
        assertThat(jdbcClient.sql("""
                        select artifact.height
                        from inference_run_input input
                        join inference_artifact artifact on artifact.artifact_id = input.artifact_id
                        where input.run_id = :runId and input.input_kind = 'IMAGE'
                        """)
                .param("runId", runId)
                .query(Integer.class).single()).isEqualTo(1);

        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline
                && runs.find(runId).orElseThrow().state() != InferenceRunState.REVIEW_REQUIRED) {
            Thread.sleep(20);
        }
        assertThat(runs.find(runId).orElseThrow().state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
    }

    @Test
    void productUploadRejectsCostLimitAboveProductBoundaryBeforeCreatingRun() throws Exception {
        var metadata = new MockMultipartFile(
                "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                """
                        {"profileId":"dashscope-qwen37-plus-product-v44-hybrid-generic","mode":"IMAGE_ONLY",
                         "inputClassification":"USER_PROVIDED","externalTransferConfirmed":true,
                         "experimentalProfileConfirmed":true,"costLimitMicrosCny":5000001}
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
    void productUploadRejectsAnOmittedRunCostLimit() throws Exception {
        var metadata = new MockMultipartFile(
                "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                """
                        {"profileId":"dashscope-qwen37-plus-product-v44-hybrid-generic","mode":"IMAGE_ONLY",
                         "inputClassification":"USER_PROVIDED","externalTransferConfirmed":true,
                         "experimentalProfileConfirmed":true}
                        """.getBytes(StandardCharsets.UTF_8)
        );
        var image = new MockMultipartFile(
                "images", "sample.png", MediaType.IMAGE_PNG_VALUE, smallValidPng()
        );
        mockMvc.perform(multipart("/api/v1/inference-runs/live")
                        .file(metadata).file(image)
                        .header("Idempotency-Key", "live-api-no-run-limit"))
                .andExpect(status().isBadRequest());

        assertThat(jdbcClient.sql("select count(*) from inference_run").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from inference_provider_reservation")
                .query(Long.class).single()).isZero();
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

    private static byte[] wideValidPng() throws Exception {
        var image = new BufferedImage(4097, 1, BufferedImage.TYPE_INT_RGB);
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
            return request -> {
                var output = switch (request.stage()) {
                    case OBSERVE -> """
                            {"contractVersion":"renderweave-visual-grounding/2.0","regions":[
                              {"regionId":"root","parentRegionId":null,"kind":"ROOT","multiplicity":"ONE",
                               "readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00",
                                 "boundingBox":{"left":0,"top":0,"right":10000,"bottom":10000}}]},
                              {"regionId":"header","parentRegionId":"root","kind":"SECTION","multiplicity":"ONE",
                               "readingOrder":0,"repeatGroupId":null,"evidence":[{"viewId":"view-00-overview-00",
                                 "boundingBox":{"left":0,"top":0,"right":10000,"bottom":3000}}]}
                            ],"elements":[
                              {"elementId":"title","kind":"SLOT","proposedKey":"title",
                               "displayName":"标题","multiplicity":"ONE","valueHint":"TEXT",
                               "regionIds":["header"],"evidence":[{"viewId":"view-00-overview-00",
                                 "boundingBox":{"left":500,"top":500,"right":9500,"bottom":2500}}]}
                            ]}
                            """;
                    case HIERARCHY -> """
                            {"contractVersion":"renderweave-visual-hierarchy/2.0",
                             "rootEntityId":"product","entities":[
                               {"entityId":"product","schemaKey":"synthetic-product",
                                "displayName":"合成商品","regionIds":["root"],
                                "supportingElementIds":["title"]}
                             ],"relationships":[]}
                            """;
                    case ELEMENT_BINDING -> """
                            {"contractVersion":"renderweave-visual-bindings/2.0",
                             "bindings":[{"elementId":"title","entityId":"product"}]}
                            """;
                    case STRUCTURE, REPAIR -> throw new AssertionError(
                            "Pipeline 4 product entry must materialize locally"
                    );
                    default -> throw new AssertionError("Unexpected provider stage " + request.stage());
                };
                return new ProviderInferenceResponse(
                        output, "test-request-" + request.attemptOrdinal(), request.profile().model(),
                        new ProviderUsage(1_000, 500), "stop"
                );
            };
        }

        @Bean
        @Primary
        DocumentVisionPreprocessor syntheticDocumentVisionPreprocessor() {
            var capability = DocumentVisionCapability.available(
                    "rapidocr-3.9.2-openvino-2026.0.0-ppocrv6-small-c05805399d7d10b1",
                    "synthetic-ocr", "1.0", "0".repeat(64)
            );
            return new DocumentVisionPreprocessor() {
                @Override
                public DocumentVisionCapability capability() {
                    return capability;
                }

                @Override
                public DocumentVisionObservation preprocess(
                        List<cn.hbads.renderweave.inference.vision.DocumentVisionArtifact> artifacts
                ) {
                    return DocumentVisionObservation.canonical(
                            capability.capabilityId(),
                            artifacts.stream().map(artifact -> new DocumentVisionObservation.ArtifactObservation(
                                    artifact.artifactId(), artifact.sourceOrdinal(), List.of()
                            )).toList()
                    );
                }
            };
        }
    }
}
