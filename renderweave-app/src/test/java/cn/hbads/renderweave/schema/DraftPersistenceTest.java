package cn.hbads.renderweave.schema;

import cn.hbads.renderweave.schema.definition.InvalidSchemaDefinitionException;
import cn.hbads.renderweave.schema.draft.DraftAlreadyExistsException;
import cn.hbads.renderweave.schema.draft.DraftDeleteBlockedException;
import cn.hbads.renderweave.schema.draft.DraftNotFoundException;
import cn.hbads.renderweave.schema.draft.DraftRevisionConflictException;
import cn.hbads.renderweave.schema.draft.DraftService;
import cn.hbads.renderweave.schema.draft.DraftSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class DraftPersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DraftService drafts;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearDrafts() {
        jdbcClient.sql("delete from schema_reference_edge").update();
        jdbcClient.sql("delete from schema_draft_revision").update();
        jdbcClient.sql("delete from schema_draft").update();
    }

    @Test
    void createStartsAtZeroAndSaveAppendsANormalizedCompleteSnapshot() {
        var created = drafts.create("product-card", definition("  商品卡片  ", "title"));

        assertThat(created.schemaKey().value()).isEqualTo("product-card");
        assertThat(created.revision()).isZero();
        assertThat(created.definition().displayName()).isEqualTo("商品卡片");
        assertThat(created.definition().description()).isEmpty();

        var saved = drafts.save("product-card", 0, definition("商品卡片 v2", "headline"));

        assertThat(saved.revision()).isEqualTo(1);
        assertThat(saved.definition().displayName()).isEqualTo("商品卡片 v2");
        assertThat(saved.definition().fields().getFirst().fieldKey().value()).isEqualTo("headline");
        assertThat(revisionCount("product-card")).isEqualTo(2);
        assertThat(displayNameAt("product-card", 0)).isEqualTo("商品卡片");
        assertThat(displayNameAt("product-card", 1)).isEqualTo("商品卡片 v2");

        Boolean descriptionWasOmitted = jdbcClient.sql("""
                        select not jsonb_exists(definition_json, 'description')
                        from schema_draft_revision
                        where schema_key = :schemaKey and revision = 0
                        """)
                .param("schemaKey", "product-card")
                .query(Boolean.class)
                .single();
        assertThat(descriptionWasOmitted).isTrue();
    }

    @Test
    void staleWriterCannotOverwriteTheWinnerOrAppendHistory() {
        drafts.create("product-card", definition("原始", "title"));
        drafts.save("product-card", 0, definition("先到的写入", "winner"));

        assertThatThrownBy(() -> drafts.save(
                "product-card", 0, definition("过期写入", "stale")
        ))
                .isInstanceOf(DraftRevisionConflictException.class)
                .satisfies(error -> assertThat(((DraftRevisionConflictException) error).currentRevision())
                        .isEqualTo(1));

        var current = drafts.get("product-card");
        assertThat(current.revision()).isEqualTo(1);
        assertThat(current.definition().displayName()).isEqualTo("先到的写入");
        assertThat(revisionCount("product-card")).isEqualTo(2);
    }

    @Test
    void simultaneousWritersWithTheSameExpectedRevisionProduceOneWinner() throws Exception {
        drafts.create("concurrent-card", definition("原始", "title"));
        var start = new CountDownLatch(1);

        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var attempts = List.of(
                    executor.submit(() -> saveAfter(start, "并发 A", "a")),
                    executor.submit(() -> saveAfter(start, "并发 B", "b"))
            );
            start.countDown();

            var results = attempts.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(results).filteredOn(DraftSnapshot.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(DraftRevisionConflictException.class::isInstance).hasSize(1);
        }

        assertThat(drafts.get("concurrent-card").revision()).isEqualTo(1);
        assertThat(revisionCount("concurrent-card")).isEqualTo(2);
    }

    @Test
    void invalidOrDuplicateCreateIsAllOrNothing() {
        assertThatThrownBy(() -> drafts.create("invalid-card", """
                {"dslVersion":"renderweave-schema/1.0","displayName":"无效","fields":[
                  {"fieldKey":"title","fieldId":"forbidden","required":false,"value":{"type":"text"}}
                ]}
                """))
                .isInstanceOf(InvalidSchemaDefinitionException.class);
        assertThat(draftCount()).isZero();

        drafts.create("same-card", definition("第一次", "title"));
        assertThatThrownBy(() -> drafts.create("same-card", definition("第二次", "other")))
                .isInstanceOf(DraftAlreadyExistsException.class);

        assertThat(draftCount()).isEqualTo(1);
        assertThat(revisionCount("same-card")).isEqualTo(1);
        assertThat(drafts.get("same-card").definition().displayName()).isEqualTo("第一次");
    }

    @Test
    void unresolvedReferenceIsRejectedWithoutAnyWrite() {
        assertThatThrownBy(() -> drafts.create("parent-card", """
                {"dslVersion":"renderweave-schema/1.0","displayName":"父定义","fields":[
                  {"fieldKey":"child","required":false,"value":{
                    "type":"reference","ref":{"schemaKey":"missing-child"}
                  }}
                ]}
                """))
                .isInstanceOf(InvalidSchemaDefinitionException.class)
                .satisfies(error -> assertThat(((InvalidSchemaDefinitionException) error).problems())
                        .anyMatch(problem -> problem.code().equals("SCHEMA_REFERENCE_NOT_FOUND")));

        assertThat(draftCount()).isZero();
    }

    @Test
    void liveReferencesPersistAsRevisionProjectionAndResolveTransitively() {
        drafts.create("leaf", emptyDefinition("叶节点"));
        drafts.create("child", referenceDefinition("子节点", "leaf"));
        var root = drafts.create("root", arrayReferenceDefinition("根节点", "child"));

        assertThat(root.resolvedRevisions())
                .extractingByKey(root.schemaKey())
                .isEqualTo(0L);
        assertThat(root.resolvedRevisions().entrySet())
                .extracting(entry -> entry.getKey().value(), java.util.Map.Entry::getValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("root", 0L),
                        org.assertj.core.groups.Tuple.tuple("child", 0L),
                        org.assertj.core.groups.Tuple.tuple("leaf", 0L)
                );
        assertThat(activeEdgeCount()).isEqualTo(2);

        drafts.save("leaf", 0, emptyDefinition("叶节点 v2"));
        var reread = drafts.get("root");
        assertThat(reread.resolvedRevisions().entrySet())
                .extracting(entry -> entry.getKey().value(), java.util.Map.Entry::getValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("root", 0L),
                        org.assertj.core.groups.Tuple.tuple("child", 0L),
                        org.assertj.core.groups.Tuple.tuple("leaf", 1L)
                );
    }

    @Test
    void fixedAdvisoryLockPreventsConcurrentReciprocalCycle() throws Exception {
        drafts.create("alpha", emptyDefinition("Alpha"));
        drafts.create("beta", emptyDefinition("Beta"));
        var start = new CountDownLatch(1);

        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var attempts = List.of(
                    executor.submit(() -> saveReferenceAfter(start, "alpha", "beta")),
                    executor.submit(() -> saveReferenceAfter(start, "beta", "alpha"))
            );
            start.countDown();

            var results = attempts.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();

            assertThat(results).filteredOn(DraftSnapshot.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(InvalidSchemaDefinitionException.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(InvalidSchemaDefinitionException.class::isInstance)
                    .first()
                    .satisfies(error -> assertThat(((InvalidSchemaDefinitionException) error).problems())
                            .anyMatch(problem -> problem.code().equals("SCHEMA_REFERENCE_CYCLE")));
        }

        assertThat(activeEdgeCount()).isEqualTo(1);
        assertThat(revisionCount("alpha") + revisionCount("beta")).isEqualTo(3);
    }

    @Test
    void depthSixteenIsAcceptedAndDepthSeventeenRollsBack() {
        drafts.create("node-16", emptyDefinition("Node 16"));
        for (int index = 15; index >= 1; index--) {
            drafts.create("node-" + index, referenceDefinition("Node " + index, "node-" + (index + 1)));
        }

        assertThat(drafts.get("node-1").resolvedRevisions()).hasSize(16);
        assertThatThrownBy(() -> drafts.create(
                "node-0",
                referenceDefinition("Node 0", "node-1")
        ))
                .isInstanceOf(InvalidSchemaDefinitionException.class)
                .satisfies(error -> assertThat(((InvalidSchemaDefinitionException) error).problems())
                        .anyMatch(problem -> problem.code().equals("SCHEMA_REFERENCE_DEPTH_EXCEEDED")));
        assertThat(draftCount()).isEqualTo(16);
        assertThat(activeEdgeCount()).isEqualTo(15);
    }

    @Test
    void deleteRestoreHistoryAndCopyPreserveTombstonesAndReferenceRules() {
        drafts.create("leaf", emptyDefinition("叶节点"));
        drafts.create("parent", referenceDefinition("父节点", "leaf"));
        drafts.save("parent", 0, emptyDefinition("父节点 v2"));
        var restoredOldContent = drafts.restore("parent", 1, 0);

        assertThat(restoredOldContent.revision()).isEqualTo(2);
        assertThat(restoredOldContent.definition().displayName()).isEqualTo("父节点");
        assertThat(restoredOldContent.resolvedRevisions()).hasSize(2);
        assertThat(drafts.history("parent", 1, 20).items())
                .extracting(revision -> revision.revision())
                .containsExactly(2L, 1L, 0L);

        assertThatThrownBy(() -> drafts.delete("leaf", 0))
                .isInstanceOf(DraftDeleteBlockedException.class)
                .satisfies(error -> {
                    var blocked = (DraftDeleteBlockedException) error;
                    assertThat(blocked.total()).isEqualTo(1);
                    assertThat(blocked.incomingReferences().getFirst().sourceSchemaKey().value())
                            .isEqualTo("parent");
                });

        var copied = drafts.copyCurrent("parent", "parent-copy", "父节点副本");
        assertThat(copied.revision()).isZero();
        assertThat(copied.creationSource().name()).isEqualTo("USER");
        assertThat(copied.definition().displayName()).isEqualTo("父节点副本");
        assertThat(copied.resolvedRevisions()).hasSize(2);
        assertThat(revisionCount("parent-copy")).isEqualTo(1);

        drafts.delete("parent-copy", 0);
        drafts.delete("parent", 2);
        drafts.delete("leaf", 0);
        assertThat(activeEdgeCount()).isZero();
        assertThatThrownBy(() -> drafts.get("parent")).isInstanceOf(DraftNotFoundException.class);
        assertThatThrownBy(() -> drafts.create("parent", emptyDefinition("不可复用")))
                .isInstanceOf(DraftAlreadyExistsException.class);

        assertThatThrownBy(() -> drafts.restore("parent", 2, 0))
                .isInstanceOf(InvalidSchemaDefinitionException.class)
                .satisfies(error -> assertThat(((InvalidSchemaDefinitionException) error).problems())
                        .anyMatch(problem -> problem.code().equals("SCHEMA_REFERENCE_NOT_FOUND")));
        assertThat(currentRevision("parent")).isEqualTo(2);
        assertThat(isDeleted("parent")).isTrue();
        assertThat(revisionCount("parent")).isEqualTo(3);

        var restoredLeaf = drafts.restore("leaf", 0, 0);
        assertThat(restoredLeaf.revision()).isEqualTo(1);
        var restoredParent = drafts.restore("parent", 2, 0);
        assertThat(restoredParent.revision()).isEqualTo(3);
        assertThat(restoredParent.resolvedRevisions().entrySet())
                .extracting(entry -> entry.getKey().value(), java.util.Map.Entry::getValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("parent", 3L),
                        org.assertj.core.groups.Tuple.tuple("leaf", 1L)
                );
        assertThat(isDeleted("parent")).isFalse();
    }

    private Object saveAfter(CountDownLatch start, String displayName, String fieldKey) {
        try {
            start.await();
            return drafts.save("concurrent-card", 0, definition(displayName, fieldKey));
        } catch (DraftRevisionConflictException conflict) {
            return conflict;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private Object saveReferenceAfter(CountDownLatch start, String source, String target) {
        try {
            start.await();
            return drafts.save(source, 0, referenceDefinition(source, target));
        } catch (InvalidSchemaDefinitionException invalid) {
            return invalid;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private long draftCount() {
        return jdbcClient.sql("select count(*) from schema_draft").query(Long.class).single();
    }

    private long activeEdgeCount() {
        return jdbcClient.sql("select count(*) from schema_reference_edge where active")
                .query(Long.class)
                .single();
    }

    private long currentRevision(String schemaKey) {
        return jdbcClient.sql("select current_revision from schema_draft where schema_key = :schemaKey")
                .param("schemaKey", schemaKey)
                .query(Long.class)
                .single();
    }

    private boolean isDeleted(String schemaKey) {
        return jdbcClient.sql("select deleted_at is not null from schema_draft where schema_key = :schemaKey")
                .param("schemaKey", schemaKey)
                .query(Boolean.class)
                .single();
    }

    private long revisionCount(String schemaKey) {
        return jdbcClient.sql("""
                        select count(*) from schema_draft_revision where schema_key = :schemaKey
                        """)
                .param("schemaKey", schemaKey)
                .query(Long.class)
                .single();
    }

    private String displayNameAt(String schemaKey, long revision) {
        return jdbcClient.sql("""
                        select definition_json ->> 'displayName'
                        from schema_draft_revision
                        where schema_key = :schemaKey and revision = :revision
                        """)
                .param("schemaKey", schemaKey)
                .param("revision", revision)
                .query(String.class)
                .single();
    }

    private static String definition(String displayName, String fieldKey) {
        return """
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"%s",
                  "description":"   ",
                  "fields":[
                    {"fieldKey":"%s","required":false,"value":{"type":"text","constraints":{"minLength":1}}}
                  ]
                }
                """.formatted(displayName, fieldKey);
    }

    private static String emptyDefinition(String displayName) {
        return """
                {"dslVersion":"renderweave-schema/1.0","displayName":"%s","fields":[]}
                """.formatted(displayName);
    }

    private static String referenceDefinition(String displayName, String targetSchemaKey) {
        return """
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"%s",
                  "fields":[
                    {"fieldKey":"child","required":false,"value":{
                      "type":"reference","ref":{"schemaKey":"%s"}
                    }}
                  ]
                }
                """.formatted(displayName, targetSchemaKey);
    }

    private static String arrayReferenceDefinition(String displayName, String targetSchemaKey) {
        return """
                {
                  "dslVersion":"renderweave-schema/1.0",
                  "displayName":"%s",
                  "fields":[
                    {"fieldKey":"children","required":false,"value":{
                      "type":"array","items":{
                        "type":"reference","ref":{"schemaKey":"%s"}
                      }
                    }}
                  ]
                }
                """.formatted(displayName, targetSchemaKey);
    }
}
