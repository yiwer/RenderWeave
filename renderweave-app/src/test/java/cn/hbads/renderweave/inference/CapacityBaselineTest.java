package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.live.LiveInferenceWorker;
import cn.hbads.renderweave.inference.run.InferenceRunStore;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.draft.DraftListSort;
import cn.hbads.renderweave.schema.draft.DraftStore;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaListSort;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaOriginFilter;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaStore;
import cn.hbads.renderweave.validation.RootDocumentValidationService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Opt-in, evidence-producing capacity fixture for the approved v1 baseline.
 *
 * <p>The test deliberately asserts data integrity and bounded concurrency, not a made-up latency
 * SLA. Timings and PostgreSQL plans are recorded for comparison by later release candidates.</p>
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "renderweave.inference.recovery-enabled=false",
                "spring.datasource.hikari.maximum-pool-size=16",
                "spring.datasource.hikari.minimum-idle=2"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_CAPACITY_BASELINE", matches = "true")
class CapacityBaselineTest {
    private static final int DRAFT_COUNT = 10_000;
    private static final int REVISIONS_PER_DRAFT = 10;
    private static final int REVISION_COUNT = DRAFT_COUNT * REVISIONS_PER_DRAFT;
    private static final int STATIC_COUNT = 10_000;
    private static final int LARGE_STATIC_ARTIFACT_COUNT = 100;
    private static final int INFERENCE_RUN_COUNT = 10_000;
    private static final int ACTIVE_SESSION_COUNT = 10;
    private static final int WORKER_KICK_COUNT = 10;
    private static final Instant MEASUREMENT_CLOCK = Instant.parse("2026-08-10T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DraftStore drafts;

    @Autowired
    private StaticSchemaStore statics;

    @Autowired
    private InferenceRunStore inferenceRuns;

    @Autowired
    private RootDocumentValidationService validations;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private DataSource dataSource;

    @LocalServerPort
    private int serverPort;

    @Test
    void recordsApprovedScaleWithoutClaimingAnSla() throws Exception {
        var seedStarted = System.nanoTime();
        resetProductData();
        seedDraftsAndRevisions();
        seedDraftReferenceEdges();
        seedStaticSchemas();
        seedInferenceRuns();
        analyzeBaselineTables();
        var seedMicros = elapsedMicros(seedStarted);

        var dataset = verifyDataset();
        warmRepresentativePaths();

        var measurements = new LinkedHashMap<String, Measurement>();
        measurements.put("draft-page-updated-desc", measure(7, () -> assertThat(
                drafts.findActivePage(0, 50, "", DraftListSort.UPDATED_DESC)
        ).hasSize(50)));
        measurements.put("draft-deep-page-size-100", measure(7, () -> assertThat(
                drafts.findActivePage(9_900, 100, "", DraftListSort.UPDATED_DESC)
        ).hasSize(100)));
        measurements.put("draft-search-and-count", measure(7, () -> {
            assertThat(drafts.findActivePage(
                    0, 50, "容量 Draft 07500", DraftListSort.NAME_ASC
            )).hasSize(1);
            assertThat(drafts.countActive("容量 Draft 07500")).isEqualTo(1);
        }));
        measurements.put("draft-history-ten-revisions", measure(7, () -> {
            var key = SchemaKey.userProvided("capacity-07500");
            assertThat(drafts.findHistory(key, 0, 20)).hasSize(REVISIONS_PER_DRAFT);
            assertThat(drafts.countHistory(key)).isEqualTo(REVISIONS_PER_DRAFT);
        }));
        measurements.put("draft-reachable-snapshot", measure(7, () -> assertThat(
                drafts.findCurrent(SchemaKey.userProvided("capacity-07491"))
                        .orElseThrow()
                        .resolvedRevisions()
        ).hasSize(10)));
        measurements.put("static-page-published-desc", measure(7, () -> assertThat(
                statics.findPage(
                        0, 50, "", StaticSchemaListSort.PUBLISHED_DESC,
                        StaticSchemaOriginFilter.DRAFT
                )
        ).hasSize(50)));
        measurements.put("static-deep-page-size-100", measure(7, () -> assertThat(
                statics.findPage(
                        9_800, 100, "", StaticSchemaListSort.PUBLISHED_DESC,
                        StaticSchemaOriginFilter.DRAFT
                )
        ).hasSize(100)));
        measurements.put("static-search-and-count", measure(7, () -> {
            assertThat(statics.findPage(
                    0, 50, "capacity-02500", StaticSchemaListSort.NAME_ASC,
                    StaticSchemaOriginFilter.DRAFT
            )).hasSize(2);
            assertThat(statics.count(
                    "capacity-02500", StaticSchemaOriginFilter.DRAFT
            )).isEqualTo(2);
        }));
        measurements.put("root-document-batch-20", measure(7, this::validateTwentyDocuments));

        var sessionResult = exerciseTenActiveSessions();
        measurements.put("ten-active-desktop-sessions", sessionResult.measurement());

        var claimResult = measureTwoConcurrentClaims();
        measurements.put("two-concurrent-run-claims", claimResult.measurement());

        var workerResult = exerciseBoundedWorkerCoordinator();
        measurements.put("ten-kicks-through-two-worker-lanes", workerResult.measurement());

        var plans = representativePlans();
        assertThat(plans.get("draft-page"))
                .contains("schema_draft_active_updated_desc_idx");
        assertThat(plans.get("draft-page-asc"))
                .contains("schema_draft_active_updated_asc_idx");
        assertThat(plans.get("draft-history"))
                .contains("schema_draft_revision_pkey");
        assertThat(plans.get("draft-reachable-closure"))
                .contains("schema_reference_edge_active_source_idx")
                .doesNotContain("\"Node Type\": \"Seq Scan\"");
        assertThat(plans.get("static-page"))
                .contains("static_schema_origin_published_desc_idx");
        assertThat(plans.get("static-page-asc"))
                .contains("static_schema_origin_published_asc_idx");
        assertThat(plans.get("inference-claim"))
                .contains("inference_run_network_claim_idx")
                .contains("Actual Rows")
                .contains("Shared Hit Blocks");
        var hikari = dataSource.unwrap(HikariDataSource.class);
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(16);
        var evidence = new CapacityReport(
                "renderweave-capacity-baseline/1.0",
                "P6/T6-1",
                Instant.now().toString(),
                false,
                "Measurements are observations from this run; no latency or throughput SLA is claimed.",
                seedMicros,
                dataset,
                new Environment(
                        jdbcClient.sql("show server_version").query(String.class).single(),
                        System.getProperty("java.version"),
                        System.getProperty("os.name"),
                        Runtime.getRuntime().availableProcessors(),
                        hikari.getMaximumPoolSize(),
                        hikari.getMinimumIdle(),
                        "postgres:16-alpine",
                        "Spring virtual threads enabled",
                        "10 concurrent HTTP/API journeys over the production controllers"
                ),
                new ConcurrencyResult(
                        ACTIVE_SESSION_COUNT,
                        sessionResult.maximumActiveSessions(),
                        2,
                        workerResult.maximumActiveWorkers(),
                        WORKER_KICK_COUNT,
                        workerResult.workerInvocations(),
                        claimResult.distinctClaims()
                ),
                measurements,
                plans,
                new ExternalEffects(
                        count("inference_attempt"),
                        count("inference_provider_reservation"),
                        "DashScope disabled and credentials removed by the capacity gate"
                )
        );

        assertThat(evidence.slaClaimed()).isFalse();
        assertThat(evidence.concurrency().maximumActiveSessions()).isEqualTo(ACTIVE_SESSION_COUNT);
        assertThat(evidence.concurrency().maximumActiveWorkers()).isEqualTo(2);
        assertThat(evidence.concurrency().distinctRunClaims()).isEqualTo(2);
        assertThat(evidence.externalEffects().providerAttempts()).isZero();
        assertThat(evidence.externalEffects().providerReservations()).isZero();
        writeReport(evidence);
    }

    private void resetProductData() {
        jdbcClient.sql("delete from inference_provider_reservation").update();
        jdbcClient.sql("delete from inference_candidate").update();
        jdbcClient.sql("delete from inference_attempt").update();
        jdbcClient.sql("delete from inference_run_event").update();
        jdbcClient.sql("delete from inference_run_input").update();
        jdbcClient.sql("delete from inference_run").update();
        jdbcClient.sql("delete from inference_artifact").update();
        jdbcClient.sql("delete from static_schema_reference_edge").update();
        jdbcClient.sql("delete from static_schema where origin = 'DRAFT'").update();
        jdbcClient.sql("delete from schema_reference_edge").update();
        jdbcClient.sql("delete from schema_draft_revision").update();
        jdbcClient.sql("delete from schema_draft").update();
    }

    private void seedDraftsAndRevisions() {
        var draftsInserted = jdbcClient.sql("""
                        insert into schema_draft (
                            schema_key, current_revision, creation_source, created_at, updated_at
                        )
                        select 'capacity-' || lpad(series::text, 5, '0'),
                               9,
                               'USER',
                               timestamptz '2026-08-10 00:00:00+00'
                                   + series * interval '1 microsecond',
                               timestamptz '2026-08-10 01:00:00+00'
                                   + series * interval '1 microsecond'
                        from generate_series(1, 10000) as series
                        """).update();
        assertThat(draftsInserted).isEqualTo(DRAFT_COUNT);

        var revisionsInserted = jdbcClient.sql("""
                        insert into schema_draft_revision (
                            schema_key, revision, definition_json, saved_at
                        )
                        select 'capacity-' || lpad(series::text, 5, '0'),
                               revision,
                               jsonb_build_object(
                                   'dslVersion', 'renderweave-schema/1.0',
                                   'displayName', '容量 Draft ' || lpad(series::text, 5, '0')
                                       || ' r' || revision,
                                   'fields', case
                                       when series >= 5001 and (series - 5001) % 10 < 9 then
                                           jsonb_build_array(jsonb_build_object(
                                               'fieldKey', 'child',
                                               'required', false,
                                               'value', jsonb_build_object(
                                                   'type', 'reference',
                                                   'ref', jsonb_build_object(
                                                       'schemaKey', 'capacity-'
                                                           || lpad((series + 1)::text, 5, '0')
                                                   )
                                               )
                                           ))
                                       else jsonb_build_array(jsonb_build_object(
                                           'fieldKey', 'value',
                                           'required', true,
                                           'value', jsonb_build_object('type', 'text')
                                       ))
                                   end
                               ),
                               timestamptz '2026-08-10 00:00:00+00'
                                   + (series * 10 + revision) * interval '1 microsecond'
                        from generate_series(1, 10000) as series
                        cross join generate_series(0, 9) as revision
                        """).update();
        assertThat(revisionsInserted).isEqualTo(REVISION_COUNT);
    }

    private void seedDraftReferenceEdges() {
        var inserted = jdbcClient.sql("""
                        insert into schema_reference_edge (
                            source_schema_key, source_revision, source_pointer,
                            target_kind, target_schema_key, target_version_tag, active
                        )
                        select 'capacity-' || lpad(series::text, 5, '0'),
                               9,
                               '/fields/0/value/ref',
                               'DRAFT',
                               'capacity-' || lpad((series + 1)::text, 5, '0'),
                               null,
                               true
                        from generate_series(5001, 10000) as series
                        where (series - 5001) % 10 < 9
                        """).update();
        assertThat(inserted).isEqualTo(4_500);
    }

    private void seedStaticSchemas() {
        var inserted = jdbcClient.sql("""
                        insert into static_schema (
                            schema_key, version_tag, origin, source_draft_revision,
                            definition_json, compiled_json_schema, compiler_version,
                            release_note, reference_depth, published_at
                        )
                        select 'capacity-' || lpad(series::text, 5, '0'),
                               version.version_tag,
                               'DRAFT',
                               9,
                               jsonb_build_object(
                                   'dslVersion', 'renderweave-schema/1.0',
                                   'displayName', '容量 Draft ' || lpad(series::text, 5, '0') || ' r9',
                                   'fields', jsonb_build_array(
                                       jsonb_build_object(
                                           'fieldKey', 'value',
                                           'required', true,
                                           'value', jsonb_build_object('type', 'text')
                                       )
                                   )
                               ),
                               json_build_object(
                                   '$schema', 'https://json-schema.org/draft/2020-12/schema',
                                   'type', 'object',
                                   'additionalProperties', true
                               ),
                               'renderweave-json-schema/1.0',
                               'capacity-baseline',
                               1,
                               timestamptz '2026-08-10 02:00:00+00'
                                   + (series * 2 + version.ordinal) * interval '1 microsecond'
                        from generate_series(1, 4997) as series
                        cross join (values ('v1', 1), ('v2', 2)) as version(version_tag, ordinal)
                        """).update();
        assertThat(inserted).isEqualTo(STATIC_COUNT - 6);
        var expanded = jdbcClient.sql("""
                        with selected as (
                            select schema_key, version_tag
                            from static_schema
                            where origin = 'DRAFT'
                            order by schema_key, version_tag
                            fetch first :artifactCount rows only
                        )
                        update static_schema target
                        set compiled_json_schema = cast(json_build_object(
                            '$schema', 'https://json-schema.org/draft/2020-12/schema',
                            'type', 'object',
                            'description', repeat('x', 1900000)
                        ) as json)
                        from selected
                        where target.schema_key = selected.schema_key
                          and target.version_tag = selected.version_tag
                        """)
                .param("artifactCount", LARGE_STATIC_ARTIFACT_COUNT)
                .update();
        assertThat(expanded).isEqualTo(LARGE_STATIC_ARTIFACT_COUNT);
    }

    private void seedInferenceRuns() {
        var inserted = jdbcClient.sql("""
                        insert into inference_run (
                            run_id, idempotency_key, request_fingerprint, input_fingerprint,
                            mode, state, stage, sequence, profile_id, profile_snapshot,
                            source_reference, cancellation_requested, checkpoint_json,
                            created_at, updated_at, finished_at
                        )
                        select md5('capacity-run-' || series)::uuid,
                               'capacity-inference-' || lpad(series::text, 5, '0'),
                               md5('request-a-' || series) || md5('request-b-' || series),
                               md5('input-a-' || series) || md5('input-b-' || series),
                               case series % 3
                                   when 0 then 'IMAGE_ONLY'
                                   when 1 then 'JSON_ONLY'
                                   else 'COMBINED'
                               end,
                               case when series <= 20 then 'QUEUED' else 'COMPLETED' end,
                               case when series <= 20 then 'OBSERVE' else 'ATOMIC_CREATE' end,
                               1,
                               case when series <= 10 then 'replay-v1' else 'capacity-profile' end,
                               jsonb_build_object(
                                   'profileId', case when series <= 10
                                       then 'replay-v1' else 'capacity-profile' end,
                                   'provider', case when series <= 10 then 'replay' else 'capacity' end,
                                   'networkAllowed', series between 11 and 20
                               ),
                               'capacity-fixture',
                               false,
                               jsonb_build_object('completedStage', 'NORMALIZE'),
                               timestamptz '2026-08-10 03:00:00+00'
                                   + series * interval '1 microsecond',
                               timestamptz '2026-08-10 03:00:00+00'
                                   + series * interval '1 microsecond',
                               case when series <= 20 then null else
                                   timestamptz '2026-08-10 04:00:00+00'
                                       + series * interval '1 microsecond' end
                        from generate_series(1, 10000) as series
                        """).update();
        assertThat(inserted).isEqualTo(INFERENCE_RUN_COUNT);
    }

    private void analyzeBaselineTables() {
        for (var table : List.of(
                "schema_draft", "schema_draft_revision", "schema_reference_edge",
                "static_schema", "inference_run"
        )) {
            jdbcClient.sql("analyze " + table).update();
        }
    }

    private Dataset verifyDataset() {
        var dataset = new Dataset(
                count("schema_draft"),
                count("schema_draft_revision"),
                count("static_schema"),
                jdbcClient.sql("select count(*) from static_schema where origin = 'SYSTEM'")
                        .query(Long.class).single(),
                jdbcClient.sql("select count(*) from static_schema where origin = 'DRAFT'")
                        .query(Long.class).single(),
                jdbcClient.sql("""
                                select count(*)
                                from static_schema
                                where octet_length(compiled_json_schema::text) > 1800000
                                """)
                        .query(Long.class).single(),
                jdbcClient.sql("select count(*) from schema_reference_edge where active")
                        .query(Long.class).single(),
                count("inference_run")
        );
        assertThat(dataset.schemaKeys()).isEqualTo(DRAFT_COUNT);
        assertThat(dataset.draftRevisions()).isEqualTo(REVISION_COUNT);
        assertThat(dataset.staticSchemas()).isEqualTo(STATIC_COUNT);
        assertThat(dataset.systemStaticSchemas()).isEqualTo(6);
        assertThat(dataset.userStaticSchemas()).isEqualTo(STATIC_COUNT - 6);
        assertThat(dataset.largeCompiledArtifacts()).isEqualTo(LARGE_STATIC_ARTIFACT_COUNT);
        assertThat(dataset.activeDraftReferences()).isEqualTo(4_500);
        assertThat(dataset.inferenceRuns()).isEqualTo(INFERENCE_RUN_COUNT);
        return dataset;
    }

    private void warmRepresentativePaths() {
        assertThat(drafts.findActivePage(0, 20, "", DraftListSort.UPDATED_DESC)).hasSize(20);
        assertThat(drafts.findHistory(SchemaKey.userProvided("capacity-05000"), 0, 20))
                .hasSize(REVISIONS_PER_DRAFT);
        assertThat(statics.findPage(
                0, 20, "", StaticSchemaListSort.PUBLISHED_DESC, StaticSchemaOriginFilter.DRAFT
        )).hasSize(20);
        assertThat(statics.find(new StaticSchemaRef(
                SchemaKey.userProvided("capacity-02500"), VersionTag.of("v1")
        ))).isPresent();
        validateTwentyDocuments();
    }

    private SessionResult exerciseTenActiveSessions() throws Exception {
        var ready = new CountDownLatch(ACTIVE_SESSION_COUNT);
        var start = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximumActive = new AtomicInteger();
        var inferenceIds = jdbcClient.sql("""
                        select run_id from inference_run
                        order by created_at desc
                        fetch first 10 rows only
                        """)
                .query(UUID.class)
                .list();
        var started = System.nanoTime();
        var perSessionMicros = new ArrayList<Long>();
        var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try (var executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("capacity-session-", 0).factory()
        )) {
            var futures = new ArrayList<java.util.concurrent.Future<Long>>();
            for (var index = 0; index < ACTIVE_SESSION_COUNT; index++) {
                var sessionIndex = index;
                futures.add(executor.submit(() -> {
                    var sessionStarted = System.nanoTime();
                    var current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    ready.countDown();
                    try {
                        assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                        var page = sessionIndex + 1;
                        getOk(http, "/api/v1/schema-drafts?page=" + page
                                + "&size=20&sort=UPDATED_DESC", "capacity-");
                        getOk(http, "/api/v1/static-schemas?page=" + page
                                + "&size=20&sort=PUBLISHED_DESC&origin=DRAFT", "capacity-");
                        getOk(http, "/api/v1/schema-drafts/capacity-"
                                + "%05d".formatted(sessionIndex + 1)
                                + "/revisions?page=1&size=20", "\"revision\":9");
                        getOk(http, "/api/v1/schema-drafts/capacity-"
                                + "%05d".formatted(5001 + sessionIndex * 10),
                                "\"resolvedRevisions\"");
                        getOk(http, "/api/v1/inference-runs/" + inferenceIds.get(sessionIndex),
                                inferenceIds.get(sessionIndex).toString());
                        return elapsedMicros(sessionStarted);
                    } finally {
                        active.decrementAndGet();
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(maximumActive.get()).isEqualTo(ACTIVE_SESSION_COUNT);
            start.countDown();
            for (var future : futures) {
                perSessionMicros.add(future.get(2, TimeUnit.MINUTES));
            }
        }

        return new SessionResult(
                Measurement.from(perSessionMicros, elapsedMicros(started)),
                maximumActive.get()
        );
    }

    private ClaimResult measureTwoConcurrentClaims() throws Exception {
        var start = new CountDownLatch(1);
        var started = System.nanoTime();
        List<UUID> claimed;
        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var futures = List.of("capacity-worker-a", "capacity-worker-b").stream()
                    .map(worker -> executor.submit(() -> {
                        assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                        return inferenceRuns.claimNext(
                                worker, MEASUREMENT_CLOCK.plusSeconds(1), Duration.ofMinutes(10)
                        ).orElseThrow().runId();
                    }))
                    .toList();
            start.countDown();
            claimed = futures.stream().map(future -> {
                try {
                    return future.get(2, TimeUnit.MINUTES);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }
        assertThat(claimed).hasSize(2).doesNotHaveDuplicates();
        var elapsed = elapsedMicros(started);
        return new ClaimResult(
                new Measurement(1, elapsed, elapsed, elapsed, elapsed, elapsed),
                claimed.stream().distinct().count()
        );
    }

    private WorkerResult exerciseBoundedWorkerCoordinator() throws Exception {
        var replay = mock(cn.hbads.renderweave.inference.replay.ReplayInferenceWorker.class);
        var worker = mock(LiveInferenceWorker.class);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(2);
        var active = new AtomicInteger();
        var maximumActive = new AtomicInteger();
        var invocations = new AtomicInteger();
        when(worker.processNext(anyString())).thenAnswer(ignored -> {
            invocations.incrementAndGet();
            var current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            entered.countDown();
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                return Optional.empty();
            } finally {
                active.decrementAndGet();
                exited.countDown();
            }
        });

        var coordinator = new InferenceCoordinator(replay, worker, true, true);
        var started = System.nanoTime();
        try {
            for (var index = 0; index < WORKER_KICK_COUNT; index++) {
                coordinator.kick();
            }
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(maximumActive.get()).isEqualTo(2);
            release.countDown();
            assertThat(exited.await(30, TimeUnit.SECONDS)).isTrue();
            verify(worker, times(2)).processNext(anyString());
        } finally {
            release.countDown();
            coordinator.close();
        }
        var elapsed = elapsedMicros(started);
        return new WorkerResult(
                new Measurement(1, elapsed, elapsed, elapsed, elapsed, elapsed),
                maximumActive.get(),
                invocations.get()
        );
    }

    private void validateTwentyDocuments() {
        var documents = new StringBuilder();
        for (var index = 0; index < 20; index++) {
            if (index > 0) documents.append(',');
            documents.append("{\"document\":{\"index\":")
                    .append(index)
                    .append(",\"value\":\"capacity-")
                    .append(index)
                    .append("\"}}");
        }
        var request = """
                {"target":{"kind":"static","schemaKey":"system-basic-text","versionTag":"v1"},
                 "documents":[%s]}
                """.formatted(documents);
        var result = validations.validate(request.getBytes(StandardCharsets.UTF_8));
        assertThat(result.validCount()).isEqualTo(20);
        assertThat(result.invalidCount()).isZero();
        assertThat(result.documents()).hasSize(20);
    }

    private Map<String, String> representativePlans() throws Exception {
        var plans = new LinkedHashMap<String, String>();
        plans.put("draft-page", explain("""
                select d.schema_key
                from schema_draft d
                join schema_draft_revision r
                  on r.schema_key = d.schema_key and r.revision = d.current_revision
                where d.deleted_at is null
                order by d.updated_at desc, d.schema_key asc
                offset 0 rows fetch first 50 rows only
                """));
        plans.put("draft-search", explain("""
                select d.schema_key
                from schema_draft d
                join schema_draft_revision r
                  on r.schema_key = d.schema_key and r.revision = d.current_revision
                where d.deleted_at is null
                  and (
                    position(lower('容量 Draft 07500') in lower(d.schema_key)) > 0
                    or position(lower('容量 Draft 07500')
                        in lower(coalesce(r.definition_json ->> 'displayName', ''))) > 0
                  )
                order by lower(r.definition_json ->> 'displayName'), d.schema_key
                fetch first 50 rows only
                """));
        plans.put("draft-page-asc", explain("""
                select d.schema_key
                from schema_draft d
                join schema_draft_revision r
                  on r.schema_key = d.schema_key and r.revision = d.current_revision
                where d.deleted_at is null
                order by d.updated_at asc, d.schema_key asc
                offset 0 rows fetch first 50 rows only
                """));
        plans.put("draft-history", explain("""
                select schema_key, revision
                from schema_draft_revision
                where schema_key = 'capacity-07500'
                order by revision desc
                fetch first 20 rows only
                """));
        plans.put("draft-reachable-closure", explain("""
                with recursive reachable(schema_key, current_revision, distance) as (
                    select schema_key, current_revision, 0
                    from schema_draft
                    where schema_key = 'capacity-07491' and deleted_at is null
                    union
                    select target.schema_key,
                           target.current_revision,
                           current_node.distance + 1
                    from reachable current_node
                    join schema_reference_edge edge
                      on edge.source_schema_key = current_node.schema_key
                     and edge.active
                     and edge.target_kind = 'DRAFT'
                    join schema_draft target
                      on target.schema_key = edge.target_schema_key
                     and target.deleted_at is null
                    where current_node.distance < 16
                )
                select schema_key, current_revision, min(distance) as distance
                from reachable
                group by schema_key, current_revision
                order by distance, schema_key
                """));
        plans.put("static-page", explain("""
                select schema_key,
                       version_tag,
                       origin,
                       definition_json ->> 'displayName' as display_name,
                       jsonb_array_length(definition_json -> 'fields') as field_count,
                       reference_depth,
                       published_at
                from static_schema
                where origin = 'DRAFT'
                order by published_at desc, schema_key, version_tag
                fetch first 50 rows only
                """));
        plans.put("static-page-asc", explain("""
                select schema_key, version_tag
                from static_schema
                where origin = 'DRAFT'
                order by published_at asc, schema_key, version_tag
                fetch first 50 rows only
                """));
        plans.put("inference-claim", explainGenericClaim());
        return plans;
    }

    private void getOk(HttpClient http, String path, String expectedBodyFragment) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + serverPort + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(expectedBodyFragment);
    }

    private String explain(String statement) {
        return jdbcClient.sql("explain (analyze, buffers, format json) " + statement)
                .query((resultSet, rowNumber) -> resultSet.getString(1))
                .single();
    }

    private String explainGenericClaim() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("set plan_cache_mode = force_generic_plan");
            statement.execute("""
                    prepare capacity_claim(boolean, timestamptz) as
                    select run_id, state
                    from inference_run
                    where (profile_snapshot ->> 'networkAllowed')::boolean = $1
                      and (state = 'QUEUED'
                       or (state = 'RUNNING' and lease_expires_at <= $2))
                    order by created_at, run_id
                    for update skip locked
                    fetch first 1 row only
                    """);
            try (var resultSet = statement.executeQuery("""
                    explain (analyze, buffers, format json)
                    execute capacity_claim(false, timestamptz '2026-08-10 00:00:01+00')
                    """)) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            } finally {
                statement.execute("deallocate capacity_claim");
            }
        }
    }

    private Measurement measure(int iterations, CheckedRunnable action) throws Exception {
        var samples = new ArrayList<Long>(iterations);
        var totalStarted = System.nanoTime();
        for (var iteration = 0; iteration < iterations; iteration++) {
            var started = System.nanoTime();
            action.run();
            samples.add(elapsedMicros(started));
        }
        return Measurement.from(samples, elapsedMicros(totalStarted));
    }

    private void writeReport(CapacityReport report) throws Exception {
        var rawPath = System.getenv("RENDERWEAVE_CAPACITY_REPORT");
        assertThat(rawPath)
                .as("The capacity gate must provide an evidence output path")
                .isNotBlank();
        var path = Path.of(rawPath).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8
        );
        assertThat(Files.size(path)).isPositive();
    }

    private long count(String table) {
        return jdbcClient.sql("select count(*) from " + table).query(Long.class).single();
    }

    private static long elapsedMicros(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedNanos);
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private record Dataset(
            long schemaKeys,
            long draftRevisions,
            long staticSchemas,
            long systemStaticSchemas,
            long userStaticSchemas,
            long largeCompiledArtifacts,
            long activeDraftReferences,
            long inferenceRuns
    ) { }

    private record Environment(
            String postgresVersion,
            String javaVersion,
            String osName,
            int availableProcessors,
            int databaseMaximumPoolSize,
            int databaseMinimumIdle,
            String databaseImage,
            String threadModel,
            String sessionModel
    ) { }

    private record ConcurrencyResult(
            int requestedActiveSessions,
            int maximumActiveSessions,
            int configuredWorkerLimit,
            int maximumActiveWorkers,
            int coordinatorKicks,
            int workerInvocations,
            long distinctRunClaims
    ) { }

    private record ExternalEffects(
            long providerAttempts,
            long providerReservations,
            String networkBoundary
    ) { }

    private record Measurement(
            int samples,
            long totalMicros,
            long minimumMicros,
            long medianMicros,
            long p95Micros,
            long maximumMicros
    ) {
        static Measurement from(List<Long> rawSamples, long totalMicros) {
            var samples = rawSamples.stream().sorted(Comparator.naturalOrder()).toList();
            assertThat(samples).isNotEmpty();
            return new Measurement(
                    samples.size(),
                    totalMicros,
                    samples.getFirst(),
                    samples.get((samples.size() - 1) / 2),
                    samples.get((int) Math.ceil(samples.size() * 0.95) - 1),
                    samples.getLast()
            );
        }
    }

    private record SessionResult(Measurement measurement, int maximumActiveSessions) { }

    private record ClaimResult(Measurement measurement, long distinctClaims) { }

    private record WorkerResult(
            Measurement measurement,
            int maximumActiveWorkers,
            int workerInvocations
    ) { }

    private record CapacityReport(
            String formatVersion,
            String scope,
            String generatedAt,
            boolean slaClaimed,
            String interpretation,
            long seedMicros,
            Dataset dataset,
            Environment environment,
            ConcurrencyResult concurrency,
            Map<String, Measurement> measurements,
            Map<String, String> postgresPlans,
            ExternalEffects externalEffects
    ) { }
}
