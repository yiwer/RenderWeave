package cn.hbads.renderweave.schema;

import cn.hbads.renderweave.schema.compile.CompiledArtifactTooLargeException;
import cn.hbads.renderweave.schema.compile.JsonSchemaCompiler;
import cn.hbads.renderweave.schema.definition.InvalidSchemaDefinitionException;
import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonParser;
import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonWriter;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.draft.DraftRevisionConflictException;
import cn.hbads.renderweave.schema.draft.DraftService;
import cn.hbads.renderweave.schema.draft.StaticReferenceTarget;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.schema.staticvalue.PublishStaticSchema;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaAlreadyExistsException;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaOrigin;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaListSort;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaOriginFilter;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaService;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DuplicateKeyException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class StaticSchemaPersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DraftService drafts;

    @Autowired
    private StaticSchemaService statics;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StaticSchemaStore staticStore;

    @BeforeEach
    void clearUserSchemas() {
        jdbcClient.sql("delete from schema_reference_edge").update();
        jdbcClient.sql("delete from static_schema_reference_edge where source_schema_key not like 'system-%'")
                .update();
        jdbcClient.sql("delete from static_schema where origin = 'DRAFT'").update();
        jdbcClient.sql("delete from schema_draft_revision").update();
        jdbcClient.sql("delete from schema_draft").update();
    }

    @Test
    void flywayInstallsSixFirstClassPresetsWhoseArtifactsMatchTheCompiler() {
        var page = statics.list(1, 20);

        assertThat(page.total()).isEqualTo(6);
        assertThat(page.items())
                .extracting(item -> item.reference().schemaKey().value())
                .containsExactly(
                        "system-basic-boolean",
                        "system-basic-date",
                        "system-basic-decimal",
                        "system-basic-text",
                        "system-basic-time",
                        "system-empty"
                );
        assertThat(page.items()).allMatch(item -> item.origin() == StaticSchemaOrigin.SYSTEM);
        assertThat(page.items()).allMatch(item -> item.referenceDepth() == 1);
        assertThat(jdbcClient.sql("select count(*) from schema_draft where schema_key like 'system-%'")
                .query(Long.class).single()).isZero();

        var compiler = new JsonSchemaCompiler();
        for (var summary : page.items()) {
            var preset = statics.get(
                    summary.reference().schemaKey().value(),
                    summary.reference().versionTag().value()
            );
            var recompiled = compiler.compile(
                    preset.reference(),
                    preset.definition(),
                    reference -> {
                        throw new AssertionError("System preset must not reference another StaticSchema");
                    }
            );
            assertThat(preset.compiledJsonSchema()).isEqualTo(recompiled.json());
        }

        assertThat(Arrays.stream(StaticSchemaStore.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .noneMatch(name -> name.equals("update")
                        || name.equals("delete")
                        || name.equals("recompile"));
    }

    @Test
    void listUsesSummaryProjectionWithoutMaterializingCompiledArtifactBytes() {
        drafts.create("large-list-artifact", textDefinition("大产物列表项", "value"));
        statics.publish("large-list-artifact", 0, "v1", null);
        jdbcClient.sql("""
                        update static_schema
                        set compiled_json_schema = cast(
                            json_build_object('padding', repeat('x', 1900000)) as json
                        )
                        where schema_key = 'large-list-artifact' and version_tag = 'v1'
                        """).update();

        var summaries = staticStore.findPage(
                0,
                100,
                "large-list-artifact",
                StaticSchemaListSort.PUBLISHED_DESC,
                StaticSchemaOriginFilter.DRAFT
        );

        assertThat(summaries).singleElement().satisfies(summary -> {
            assertThat(summary.reference().schemaKey().value()).isEqualTo("large-list-artifact");
            assertThat(summary.displayName()).isEqualTo("大产物列表项");
            assertThat(summary.fieldCount()).isEqualTo(1);
        });
        assertThat(jdbcClient.sql("""
                        select octet_length(compiled_json_schema::text)
                        from static_schema
                        where schema_key = 'large-list-artifact' and version_tag = 'v1'
                        """).query(Integer.class).single()).isGreaterThan(1_800_000);
    }

    @Test
    void publicationConsumesExactCurrentRevisionAndNeverRewritesAnExistingTag() {
        drafts.create("leaf", textDefinition("叶节点", "value"));

        var first = statics.publish("leaf", 0, "v1", "  首次发布  ");
        var originalBytes = first.compiledJsonSchema();
        var secondTag = statics.publish("leaf", 0, "release-2026", null);

        assertThat(first.sourceDraftRevision()).contains(0L);
        assertThat(first.releaseNote()).contains("首次发布");
        assertThat(first.origin()).isEqualTo(StaticSchemaOrigin.DRAFT);
        assertThat(first.referenceDepth()).isEqualTo(1);
        assertThat(secondTag.sourceDraftRevision()).contains(0L);
        assertThat(userStaticCount()).isEqualTo(2);
        assertThat(compiledColumnType()).isEqualTo("json");

        assertThatThrownBy(() -> statics.publish("leaf", 0, "v1", "覆盖"))
                .isInstanceOf(StaticSchemaAlreadyExistsException.class);
        assertThat(userStaticCount()).isEqualTo(2);

        drafts.save("leaf", 0, textDefinition("叶节点 v2", "renamed"));
        assertThatThrownBy(() -> statics.publish("leaf", 0, "stale", null))
                .isInstanceOf(DraftRevisionConflictException.class);
        assertThat(userStaticCount()).isEqualTo(2);
        assertThat(statics.get("leaf", "v1").compiledJsonSchema()).isEqualTo(originalBytes);
    }

    @Test
    void bottomUpPublicationEmbedsImmutableChildAndStaticCopyKeepsExactReferences() {
        drafts.create("leaf", textDefinition("叶节点", "value"));
        var leaf = statics.publish("leaf", 0, "v1", null);
        drafts.create("parent", liveReferenceDefinition("父节点", "leaf"));

        assertThatThrownBy(() -> statics.publish("parent", 0, "v1", null))
                .isInstanceOf(InvalidSchemaDefinitionException.class)
                .satisfies(error -> assertThat(((InvalidSchemaDefinitionException) error).problems())
                        .anyMatch(problem -> problem.code().equals("DRAFT_REFERENCE_NOT_PUBLISHABLE")));
        assertThat(userStaticCount()).isEqualTo(1);

        drafts.save("parent", 0, staticReferenceDefinition("父节点", "leaf", "v1"));
        var parent = statics.publish("parent", 1, "v1", "父发布");

        assertThat(parent.referenceDepth()).isEqualTo(2);
        assertThat(parent.compiledJsonSchema()).contains(leaf.compilerVersion());
        assertThat(parent.compiledJsonSchema()).contains("\"x-renderweave-type\":\"reference\"");
        assertThat(countOccurrences(parent.compiledJsonSchema(), "\"$schema\"")).isEqualTo(1);
        assertThat(staticEdgeCount()).isEqualTo(1);

        drafts.delete("leaf", 0);
        assertThat(statics.get("leaf", "v1").compiledJsonSchema()).isEqualTo(leaf.compiledJsonSchema());
        assertThat(statics.get("parent", "v1").compiledJsonSchema()).isEqualTo(parent.compiledJsonSchema());

        var copied = statics.copyToDraft("parent", "v1", "parent-copy", "父节点副本");
        assertThat(copied.revision()).isZero();
        assertThat(copied.definition().displayName()).isEqualTo("父节点副本");
        assertThat(copied.definition().fields().getFirst().value().type()).isEqualTo("reference");
        assertThat(jdbcClient.sql("""
                        select target_kind || ':' || target_schema_key || '@' || target_version_tag
                        from schema_reference_edge
                        where source_schema_key = 'parent-copy' and active
                        """).query(String.class).single()).isEqualTo("STATIC:leaf@v1");
    }

    @Test
    void missingStaticReferenceAndMixedDepthSeventeenAreRejectedWithoutWrites() {
        assertThatThrownBy(() -> drafts.create(
                "missing-parent",
                staticReferenceDefinition("无效父节点", "missing", "v1")
        ))
                .isInstanceOf(InvalidSchemaDefinitionException.class)
                .satisfies(error -> assertThat(((InvalidSchemaDefinitionException) error).problems())
                        .anyMatch(problem -> problem.code().equals("STATIC_SCHEMA_REFERENCE_NOT_FOUND")));
        assertThat(draftCount()).isZero();

        drafts.create("node-15", staticReferenceDefinition("Node 15", "system-empty", "v1"));
        for (int index = 14; index >= 1; index--) {
            drafts.create("node-" + index, liveReferenceDefinition("Node " + index, "node-" + (index + 1)));
        }
        assertThat(drafts.get("node-1").resolvedRevisions()).hasSize(15);

        assertThatThrownBy(() -> drafts.create(
                "node-0",
                liveReferenceDefinition("Node 0", "node-1")
        ))
                .isInstanceOf(InvalidSchemaDefinitionException.class)
                .satisfies(error -> assertThat(((InvalidSchemaDefinitionException) error).problems())
                        .anyMatch(problem -> problem.code().equals("SCHEMA_REFERENCE_DEPTH_EXCEEDED")));
        assertThat(draftCount()).isEqualTo(15);
    }

    @Test
    void oversizedParentArtifactRollsBackThePublication() {
        drafts.create("wide-0", emptyDefinition("Wide 0"));
        var child = statics.publish("wide-0", 0, "v1", null);

        for (int level = 1; level <= 4; level++) {
            var schemaKey = "wide-" + level;
            drafts.create(schemaKey, repeatedStaticReferences("Wide " + level, "wide-" + (level - 1), 8));
            child = statics.publish(schemaKey, 0, "v1", null);
        }
        assertThat(child.compiledJsonSchema().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThan(JsonSchemaCompiler.MAX_ARTIFACT_BYTES);

        drafts.create("wide-5", repeatedStaticReferences("Wide 5", "wide-4", 8));
        var countBefore = userStaticCount();
        assertThatThrownBy(() -> statics.publish("wide-5", 0, "v1", null))
                .isInstanceOf(CompiledArtifactTooLargeException.class);
        assertThat(userStaticCount()).isEqualTo(countBefore);
        assertThat(jdbcClient.sql("""
                        select count(*) from static_schema
                        where schema_key = 'wide-5' and version_tag = 'v1'
                        """).query(Long.class).single()).isZero();
        assertThat(drafts.get("wide-5").revision()).isZero();
    }

    @Test
    void edgeWriteFaultRollsBackTheStaticRowAndEveryEdge() {
        drafts.create("fault-parent", staticReferenceDefinition(
                "Fault parent", "system-empty", "v1"
        ));
        var parser = new SchemaDefinitionJsonParser();
        var writer = new SchemaDefinitionJsonWriter();
        var definition = drafts.get("fault-parent").definition();
        var identity = new StaticSchemaRef(
                SchemaKey.userProvided("fault-parent"),
                VersionTag.of("fault")
        );
        var systemEmpty = statics.get("system-empty", "v1");
        var compiled = new JsonSchemaCompiler().compile(
                identity,
                definition,
                reference -> new cn.hbads.renderweave.schema.compile.CompiledStaticArtifact(
                        reference,
                        systemEmpty.compiledJsonSchema()
                )
        );
        var duplicate = new StaticReferenceTarget(
                "/fields/0/value/ref",
                systemEmpty.reference()
        );

        assertThatThrownBy(() -> staticStore.publish(new PublishStaticSchema(
                identity.schemaKey(),
                0,
                identity.versionTag(),
                writer.write(parser.parse(writer.write(definition))),
                compiled.json(),
                compiled.compilerVersion(),
                Optional.empty(),
                List.of(duplicate, duplicate),
                2
        ))).isInstanceOf(DuplicateKeyException.class);

        assertThat(jdbcClient.sql("""
                        select count(*) from static_schema
                        where schema_key = 'fault-parent' and version_tag = 'fault'
                        """).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("""
                        select count(*) from static_schema_reference_edge
                        where source_schema_key = 'fault-parent' and source_version_tag = 'fault'
                        """).query(Long.class).single()).isZero();
    }

    private long userStaticCount() {
        return jdbcClient.sql("select count(*) from static_schema where origin = 'DRAFT'")
                .query(Long.class)
                .single();
    }

    private long staticEdgeCount() {
        return jdbcClient.sql("select count(*) from static_schema_reference_edge")
                .query(Long.class)
                .single();
    }

    private long draftCount() {
        return jdbcClient.sql("select count(*) from schema_draft")
                .query(Long.class)
                .single();
    }

    private String compiledColumnType() {
        return jdbcClient.sql("""
                        select pg_typeof(compiled_json_schema)::text
                        from static_schema where schema_key = 'leaf' and version_tag = 'v1'
                        """).query(String.class).single();
    }

    private static String emptyDefinition(String displayName) {
        return """
                {"dslVersion":"renderweave-schema/1.0","displayName":"%s","fields":[]}
                """.formatted(displayName);
    }

    private static String textDefinition(String displayName, String fieldKey) {
        return """
                {"dslVersion":"renderweave-schema/1.0","displayName":"%s","fields":[
                  {"fieldKey":"%s","required":true,"value":{"type":"text"}}
                ]}
                """.formatted(displayName, fieldKey);
    }

    private static String liveReferenceDefinition(String displayName, String targetSchemaKey) {
        return """
                {"dslVersion":"renderweave-schema/1.0","displayName":"%s","fields":[
                  {"fieldKey":"child","required":true,"value":{"type":"reference","ref":{
                    "schemaKey":"%s"}}}
                ]}
                """.formatted(displayName, targetSchemaKey);
    }

    private static String staticReferenceDefinition(
            String displayName,
            String targetSchemaKey,
            String versionTag
    ) {
        return """
                {"dslVersion":"renderweave-schema/1.0","displayName":"%s","fields":[
                  {"fieldKey":"child","required":true,"value":{"type":"reference","ref":{
                    "schemaKey":"%s","versionTag":"%s"}}}
                ]}
                """.formatted(displayName, targetSchemaKey, versionTag);
    }

    private static String repeatedStaticReferences(
            String displayName,
            String targetSchemaKey,
            int count
    ) {
        var fields = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                fields.append(',');
            }
            fields.append("""
                    {"fieldKey":"child-%d","required":true,"value":{"type":"reference","ref":{
                      "schemaKey":"%s","versionTag":"v1"}}}
                    """.formatted(index, targetSchemaKey));
        }
        return """
                {"dslVersion":"renderweave-schema/1.0","displayName":"%s","fields":[%s]}
                """.formatted(displayName, fields);
    }

    private static int countOccurrences(String value, String needle) {
        var count = 0;
        var offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
