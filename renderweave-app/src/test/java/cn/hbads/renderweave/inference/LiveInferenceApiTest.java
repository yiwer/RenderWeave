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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "renderweave.inference.live-enabled=true",
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
    private ReplayFixtureInputFactory fixtures;

    @Autowired
    private tools.jackson.databind.ObjectMapper json;

    @BeforeEach
    void clearData() {
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
    }

    @Test
    void multipartSyntheticUploadQueuesRunsAndBackgroundWorkerProducesReview() throws Exception {
        var imageBytes = fixtures.create("image-01-product-card", true).images().getFirst().bytes();
        var metadata = new MockMultipartFile(
                "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                """
                        {"profileId":"dashscope-qwen37-flash-v1","mode":"IMAGE_ONLY",
                         "inputClassification":"SYNTHETIC","externalTransferConfirmed":true,
                         "experimentalProfileConfirmed":true}
                        """.getBytes(StandardCharsets.UTF_8)
        );
        var image = new MockMultipartFile(
                "images", "synthetic.png", MediaType.IMAGE_PNG_VALUE, imageBytes
        );
        var response = mockMvc.perform(multipart("/api/v1/inference-runs/live")
                        .file(metadata).file(image)
                        .header("Idempotency-Key", "live-api-synthetic"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profileId").value("dashscope-qwen37-flash-v1"))
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
                .andExpect(jsonPath("$.run.sourceReference").value("synthetic-upload"))
                .andExpect(jsonPath("$.images.length()").value(1))
                .andExpect(jsonPath("$.jsonSampleCount").value(0));
        assertThat(jdbcClient.sql("select count(*) from inference_provider_reservation")
                .query(Long.class).single()).isEqualTo(1);
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
