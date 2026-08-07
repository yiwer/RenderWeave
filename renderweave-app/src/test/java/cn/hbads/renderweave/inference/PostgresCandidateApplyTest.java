package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.candidate.CandidateApplyConflictException;
import cn.hbads.renderweave.inference.candidate.CandidateApplyService;
import cn.hbads.renderweave.inference.candidate.CandidateReviewService;
import cn.hbads.renderweave.inference.run.InferenceRunState;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.schema.draft.DraftService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "renderweave.inference.blob-root=target/test-candidate-apply-blobs")
@AutoConfigureMockMvc
class PostgresCandidateApplyTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CandidateReviewService reviews;

    @Autowired
    private CandidateApplyService applies;

    @Autowired
    private InferenceRunStore runs;

    @Autowired
    private DraftService drafts;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearMutableData() {
        dropFaultTrigger();
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
        jdbcClient.sql("delete from schema_reference_edge").update();
        jdbcClient.sql("delete from schema_draft_revision").update();
        jdbcClient.sql("delete from schema_draft").update();
    }

    @AfterEach
    void cleanupFaultTrigger() {
        dropFaultTrigger();
    }

    @Test
    void createsNestedBundleOnceWithAiRevisionZeroFinalSnapshotAndNoStaticPublication() throws Exception {
        var staticCount = count("static_schema");
        var runId = createReviewRun("json-02-nested-object", "apply-success");
        var review = reviews.get(runId);

        var applied = applies.apply(runId, review.candidateRevision());
        var replayed = applies.apply(runId, review.candidateRevision());

        assertThat(applied.run().state()).isEqualTo(InferenceRunState.COMPLETED);
        assertThat(applied.createdSchemaKeys()).hasSize(2);
        assertThat(replayed.createdSchemaKeys()).isEqualTo(applied.createdSchemaKeys());
        assertThat(count("schema_draft")).isEqualTo(2);
        assertThat(count("schema_draft_revision")).isEqualTo(2);
        assertThat(count("schema_reference_edge")).isEqualTo(1);
        assertThat(jdbcClient.sql("select distinct creation_source from schema_draft")
                .query(String.class).list()).containsExactly("AI");
        assertThat(jdbcClient.sql("select distinct current_revision from schema_draft")
                .query(Long.class).list()).containsExactly(0L);
        assertThat(jdbcClient.sql("""
                        select final_json = current_json and applied_at is not null
                        from inference_candidate where run_id = :runId
                        """)
                .param("runId", runId).query(Boolean.class).single()).isTrue();
        assertThat(runs.eventsAfter(runId, 0, 100).stream().map(event -> event.type()))
                .containsSubsequence("APPLYING", "CANDIDATE_APPLIED");
        assertThat(count("static_schema")).isEqualTo(staticCount);
    }

    @Test
    void activeOrTombstonedKeyConflictRollsBackTheWholeBundleAndRetainsReviewArtifacts() throws Exception {
        var runId = createReviewRun("json-02-nested-object", "apply-conflict");
        var review = reviews.get(runId);
        var rootKey = review.current().schemas().stream()
                .filter(schema -> schema.candidateSchemaId().equals(review.current().rootCandidateSchemaId()))
                .findFirst().orElseThrow().proposedSchemaKey();
        drafts.create(rootKey, emptyDefinition("占用键"));
        drafts.delete(rootKey, 0);

        assertThatThrownBy(() -> applies.apply(runId, review.candidateRevision()))
                .isInstanceOf(CandidateApplyConflictException.class)
                .satisfies(error -> assertThat(((CandidateApplyConflictException) error).code())
                        .isEqualTo("CANDIDATE_SCHEMA_KEY_CONFLICT"));

        assertThat(count("schema_draft")).isEqualTo(1);
        assertThat(count("schema_draft_revision")).isEqualTo(1);
        assertThat(count("schema_reference_edge")).isZero();
        assertThat(runs.find(runId).orElseThrow().state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(jdbcClient.sql("""
                        select final_json is null and applied_at is null
                        from inference_candidate where run_id = :runId
                        """).param("runId", runId).query(Boolean.class).single()).isTrue();
        assertThat(jdbcClient.sql("select count(*) from inference_run_input where run_id = :runId")
                .param("runId", runId).query(Long.class).single()).isPositive();
    }

    @Test
    void databaseFaultAfterAChildInsertLeavesNoPartialDraftOrFinalSnapshot() throws Exception {
        var runId = createReviewRun("json-02-nested-object", "apply-fault");
        var review = reviews.get(runId);
        var rootKey = review.current().schemas().stream()
                .filter(schema -> schema.candidateSchemaId().equals(review.current().rootCandidateSchemaId()))
                .findFirst().orElseThrow().proposedSchemaKey();
        installFaultTrigger(rootKey);

        assertThatThrownBy(() -> applies.apply(runId, review.candidateRevision()))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("schema_draft")).isZero();
        assertThat(count("schema_draft_revision")).isZero();
        assertThat(count("schema_reference_edge")).isZero();
        assertThat(runs.find(runId).orElseThrow().state()).isEqualTo(InferenceRunState.REVIEW_REQUIRED);
        assertThat(jdbcClient.sql("select final_json is null from inference_candidate where run_id = :runId")
                .param("runId", runId).query(Boolean.class).single()).isTrue();
        assertThat(runs.eventsAfter(runId, 0, 100).stream().map(event -> event.type()))
                .doesNotContain("APPLYING", "CANDIDATE_APPLIED");
    }

    @Test
    void concurrentBundlesWithTheSameKeysHaveExactlyOneWinnerAndNoDuplicateCreation() throws Exception {
        var first = createReviewRun("json-02-nested-object", "apply-race-a");
        var second = createReviewRun("json-02-nested-object", "apply-race-b");
        var firstRevision = reviews.get(first).candidateRevision();
        var secondRevision = reviews.get(second).candidateRevision();
        var start = new CountDownLatch(1);

        List<Object> outcomes;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> applyAfter(start, first, firstRevision));
            var b = executor.submit(() -> applyAfter(start, second, secondRevision));
            start.countDown();
            outcomes = List.of(a.get(), b.get());
        }

        assertThat(outcomes.stream().filter(outcome -> outcome instanceof CandidateApplyConflictException))
                .hasSize(1);
        assertThat(outcomes.stream().filter(outcome -> !(outcome instanceof RuntimeException)))
                .hasSize(1);
        assertThat(count("schema_draft")).isEqualTo(2);
        assertThat(count("schema_draft_revision")).isEqualTo(2);
        assertThat(List.of(
                runs.find(first).orElseThrow().state(),
                runs.find(second).orElseThrow().state()
        )).containsExactlyInAnyOrder(InferenceRunState.COMPLETED, InferenceRunState.REVIEW_REQUIRED);
    }

    private Object applyAfter(CountDownLatch start, UUID runId, long revision) {
        try {
            start.await();
            return applies.apply(runId, revision);
        } catch (RuntimeException failure) {
            return failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private UUID createReviewRun(String fixtureId, String idempotencyKey) throws Exception {
        var response = mockMvc.perform(post("/api/v1/inference-runs")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fixtureId":"%s","externalTransferConfirmed":true}
                                """.formatted(fixtureId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).path("runId").asText());
    }

    private void installFaultTrigger(String schemaKey) {
        jdbcClient.sql("""
                        create or replace function fail_candidate_apply() returns trigger as $$
                        begin
                          if new.schema_key = '%s' then
                            raise exception 'synthetic candidate apply fault';
                          end if;
                          return new;
                        end;
                        $$ language plpgsql
                        """.formatted(schemaKey)).update();
        jdbcClient.sql("""
                        create trigger fail_candidate_apply_trigger
                        before insert on schema_draft
                        for each row execute function fail_candidate_apply()
                        """).update();
    }

    private void dropFaultTrigger() {
        jdbcClient.sql("drop trigger if exists fail_candidate_apply_trigger on schema_draft").update();
        jdbcClient.sql("drop function if exists fail_candidate_apply()").update();
    }

    private long count(String table) {
        return jdbcClient.sql("select count(*) from " + table).query(Long.class).single();
    }

    private static String emptyDefinition(String displayName) {
        return """
                {"dslVersion":"renderweave-schema/1.0","displayName":"%s","fields":[]}
                """.formatted(displayName);
    }
}
