package cn.hbads.renderweave.inference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

@Testcontainers
@SpringBootTest(properties = {
        "renderweave.inference.blob-root=target/test-inference-api-blobs",
        "DASHSCOPE_API_KEY=",
        "DASHSCOPE_API_KEY_FILE="
})
@AutoConfigureMockMvc
class InferenceApiTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearData() {
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
        jdbcClient.sql("delete from schema_reference_edge").update();
        jdbcClient.sql("delete from schema_draft_revision").update();
        jdbcClient.sql("delete from schema_draft").update();
    }

    @Test
    void replayCreateReviewImageAndSingleItemAutosaveFormAClosedLoop() throws Exception {
        mockMvc.perform(get("/api/v1/inference-runs/replay-fixtures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileId").value("replay-v1"))
                .andExpect(jsonPath("$.networkAllowed").value(false))
                .andExpect(jsonPath("$.items.length()").value(60));

        var createdBody = mockMvc.perform(post("/api/v1/inference-runs")
                        .header("Idempotency-Key", "api-image-low-info")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fixtureId":"image-08-low-information","externalTransferConfirmed":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/api/v1/inference-runs/")))
                .andExpect(jsonPath("$.state").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.stage").value("USER_APPROVAL"))
                .andExpect(jsonPath("$.candidateRevision").value(0))
                .andReturn().getResponse().getContentAsString();
        var runId = json.readTree(createdBody).path("runId").asText();

        var replayBody = mockMvc.perform(post("/api/v1/inference-runs")
                        .header("Idempotency-Key", "api-image-low-info")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fixtureId":"image-08-low-information","externalTransferConfirmed":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(replayBody).path("runId").asText()).isEqualTo(runId);

        var reviewText = mockMvc.perform(get("/api/v1/inference-runs/{runId}/candidate", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateRevision").value(0))
                .andExpect(jsonPath("$.images.length()").value(1))
                .andExpect(jsonPath("$.problems[?(@.code == 'LOW_CONFIDENCE_UNRESOLVED')]").exists())
                .andReturn().getResponse().getContentAsString();
        var review = json.readTree(reviewText);
        var originalBefore = review.path("original").toString();
        var current = review.path("current").deepCopy();
        var artifactUrl = review.path("images").get(0).path("contentUrl").asText();

        mockMvc.perform(get(artifactUrl))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G'));

        var bulk = current.deepCopy();
        ((ObjectNode) bulk.path("schemas").get(0).path("assessment")).put("resolution", "CONFIRMED");
        ((ObjectNode) bulk.path("schemas").get(0).path("fields").get(0).path("assessment"))
                .put("resolution", "RESOLVED_BY_EDIT");
        mockMvc.perform(put("/api/v1/inference-runs/{runId}/candidate", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(0, bulk)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CANDIDATE_BULK_RESOLUTION_FORBIDDEN"));

        var edited = current.deepCopy();
        var field = (ObjectNode) edited.path("schemas").get(0).path("fields").get(0);
        ((ObjectNode) field.path("assessment")).put("resolution", "RESOLVED_BY_EDIT");
        var value = (ObjectNode) field.path("value");
        value.put("kind", "TEXT");
        value.putNull("items");
        value.putNull("reference");
        value.putArray("observedKinds");

        var savedText = mockMvc.perform(put("/api/v1/inference-runs/{runId}/candidate", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(0, edited)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateRevision").value(1))
                .andExpect(jsonPath("$.current.schemas[0].fields[0].assessment.resolution")
                        .value("RESOLVED_BY_EDIT"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(savedText).path("original").toString()).isEqualTo(originalBefore);

        mockMvc.perform(put("/api/v1/inference-runs/{runId}/candidate", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(0, edited)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANDIDATE_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.revision").value(1));

        var tampered = edited.deepCopy();
        ((ObjectNode) tampered.path("schemas").get(0).path("fields").get(0).path("assessment"))
                .put("confidenceBps", 9999);
        mockMvc.perform(put("/api/v1/inference-runs/{runId}/candidate", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody(1, tampered)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AI_PROVENANCE_IMMUTABLE"));

        assertThat(count("schema_draft")).isZero();
        assertThat(count("schema_draft_revision")).isZero();
        assertThat(count("inference_candidate")).isEqualTo(1);
    }

    @Test
    void createRequiresExplicitConfirmationAndIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/inference-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fixtureId":"json-01-scalars","externalTransferConfirmed":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/inference-runs")
                        .header("Idempotency-Key", "missing-confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fixtureId":"json-01-scalars","externalTransferConfirmed":false}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void liveAvailabilityIsSafeAndDisabledPolicyPreventsUploadsBeforeNormalization() throws Exception {
        mockMvc.perform(get("/api/v1/inference-runs/live-availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.uploadEnabled").value(false))
                .andExpect(jsonPath("$.maximumAttempts").value(6))
                .andExpect(jsonPath("$.maximumCostMicrosCny").value(1_000_000))
                .andExpect(jsonPath("$.profiles.length()").value(3))
                .andExpect(jsonPath("$.profiles[?(@.model == 'qwen3.7-flash')]").exists())
                .andExpect(jsonPath("$.profiles[?(@.model == 'qwen3.7-plus-2026-05-26')]").exists())
                .andExpect(jsonPath("$.profiles[?(@.model == 'qwen3.8-max')]").exists())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("DASHSCOPE_API_KEY")
                )));

        var metadata = new MockMultipartFile(
                "metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                """
                        {"profileId":"dashscope-qwen37-flash-v1","mode":"IMAGE_ONLY",
                         "inputClassification":"SYNTHETIC","externalTransferConfirmed":true,
                         "experimentalProfileConfirmed":true}
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
        var image = new MockMultipartFile(
                "images", "synthetic.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3}
        );
        mockMvc.perform(multipart("/api/v1/inference-runs/live")
                        .file(metadata).file(image)
                        .header("Idempotency-Key", "disabled-live-upload"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LIVE_INFERENCE_DISABLED"));
        assertThat(count("inference_run")).isZero();
        assertThat(count("inference_provider_reservation")).isZero();
    }

    @Test
    void applyEndpointCreatesOnlyDraftsIsIdempotentAndExposesTheFrozenFinalCandidate() throws Exception {
        var staticCount = count("static_schema");
        var created = mockMvc.perform(post("/api/v1/inference-runs")
                        .header("Idempotency-Key", "api-apply-clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fixtureId":"json-01-scalars","externalTransferConfirmed":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var runId = json.readTree(created).path("runId").asText();
        var reviewSequence = json.readTree(created).path("sequence").asLong();

        var applied = mockMvc.perform(post("/api/v1/inference-runs/{runId}/apply", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCandidateRevision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.state").value("COMPLETED"))
                .andExpect(jsonPath("$.run.stage").value("ATOMIC_CREATE"))
                .andExpect(jsonPath("$.candidateRevision").value(0))
                .andExpect(jsonPath("$.createdDrafts.length()").value(1))
                .andExpect(jsonPath("$.createdDrafts[0].revision").value(0))
                .andReturn().getResponse().getContentAsString();
        var draftHref = json.readTree(applied).path("createdDrafts").get(0).path("href").asText();

        mockMvc.perform(post("/api/v1/inference-runs/{runId}/apply", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCandidateRevision\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.state").value("COMPLETED"));
        mockMvc.perform(get(draftHref))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creationSource").value("AI"))
                .andExpect(jsonPath("$.revision").value(0));
        mockMvc.perform(get("/api/v1/inference-runs/{runId}/candidate", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalCandidate").isMap())
                .andExpect(jsonPath("$.appliedAt").isNotEmpty());

        var stream = mockMvc.perform(get("/api/v1/inference-runs/{runId}/events", runId)
                        .header("Last-Event-ID", Long.toString(reviewSequence))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        stream.getAsyncResult(5_000);
        mockMvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:APPLYING")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:CANDIDATE_APPLIED")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("event:REVIEW_REQUIRED")
                )));

        assertThat(count("schema_draft")).isEqualTo(1);
        assertThat(count("schema_draft_revision")).isEqualTo(1);
        assertThat(count("static_schema")).isEqualTo(staticCount);
    }

    @Test
    void applyEndpointKeepsBlockingCandidateInReviewWithZeroDraftWrites() throws Exception {
        var created = mockMvc.perform(post("/api/v1/inference-runs")
                        .header("Idempotency-Key", "api-apply-blocked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fixtureId":"image-08-low-information","externalTransferConfirmed":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var runId = json.readTree(created).path("runId").asText();

        mockMvc.perform(post("/api/v1/inference-runs/{runId}/apply", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedCandidateRevision\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CANDIDATE_APPLY_BLOCKED"))
                .andExpect(jsonPath("$.violations").isArray());

        mockMvc.perform(get("/api/v1/inference-runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REVIEW_REQUIRED"));
        assertThat(count("schema_draft")).isZero();
    }

    @Test
    void reviewCancellationAndManualRetryCreateANewAuditableRunWithoutDrafts() throws Exception {
        var created = mockMvc.perform(post("/api/v1/inference-runs")
                        .header("Idempotency-Key", "api-cancel-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fixtureId":"json-01-scalars","externalTransferConfirmed":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var sourceRunId = json.readTree(created).path("runId").asText();

        mockMvc.perform(post("/api/v1/inference-runs/{runId}/cancel", sourceRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));

        var retried = mockMvc.perform(post("/api/v1/inference-runs/{runId}/retries", sourceRunId)
                        .header("Idempotency-Key", "api-cancel-retry"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.retryOfRunId").value(sourceRunId))
                .andReturn().getResponse().getContentAsString();
        var retryRunId = json.readTree(retried).path("runId").asText();

        mockMvc.perform(post("/api/v1/inference-runs/{runId}/retries", sourceRunId)
                        .header("Idempotency-Key", "api-cancel-retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(retryRunId));
        assertThat(jdbcClient.sql("select count(*) from inference_attempt")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(count("schema_draft")).isZero();
    }

    private String saveBody(long revision, JsonNode candidate) throws Exception {
        var body = json.createObjectNode();
        body.put("expectedCandidateRevision", revision);
        body.set("candidate", candidate);
        return json.writeValueAsString(body);
    }

    private long count(String table) {
        return jdbcClient.sql("select count(*) from " + table).query(Long.class).single();
    }
}
