package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.Evaluator;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationCommand;
import cn.hbads.renderweave.rendering.api.Evaluator.EvaluationOutcome;
import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.Evaluator.OwnerScope;
import cn.hbads.renderweave.rendering.api.Evaluator.RenderRequestId;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.TemplateApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 首个 Rendering 纵切端到端 assembly 证明：真实 PostgreSQL 上创建 Template，装配后的
 * Evaluator 完成 closure 冻结 → input admission → materialization → 原子 seal，产出
 * SealedDocument。无公开 route（Engine 时代接线）；AssetResolutionPort 缺省 fail-closed。
 */
@Testcontainers
@SpringBootTest(properties = {
        "renderweave.template.single-owner.enabled=true",
        "renderweave.template.single-owner.owner-scope=owner-a",
        "renderweave.template.single-owner.capabilities=template.create,template.read",
        "renderweave.rendering.capability-state.key="
                + "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
})
class EvaluatorAssemblyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final StaticSchemaRef SYSTEM_EMPTY = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"),
            VersionTag.of("v1"));

    private static final byte[] DESIGN = """
            {"dslVersion":"renderweave-design/1.0",
             "expressionProfile":"renderweave-expression/1.0",
             "displayName":"Assembly",
             "definitions":[],
             "designRoot":{"nodeId":"00000000-0000-4000-8000-000000000001",
               "kind":"canvas","widthMm":210,"heightMm":297,
               "bindings":[],"children":[
                 {"nodeId":"00000000-0000-4000-8000-000000000002","kind":"rect",
                  "bindings":[],"placement":{"type":"ABSOLUTE","xMm":1,"yMm":2,
                    "widthMode":"FIXED","widthMm":10,"heightMode":"FIXED","heightMm":5},
                  "fill":{"color":"#FF000000"}}]}}
            """.getBytes(StandardCharsets.UTF_8);

    @Autowired
    private TemplateApplication templates;

    @Autowired
    private Evaluator evaluator;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clearTemplates() {
        jdbc.sql("""
                truncate table template_use_reference,
                                 template_asset_reference,
                                 template_revision,
                                 template_aggregate
                cascade
                """).update();
    }

    @Test
    void evaluatesCreatedTemplateIntoSealedDocument() {
        var invocation = TemplateApplication.TemplateInvocationRef.serverCreated("assembly-1");
        var created = (TemplateApplication.CreatedReadable) templates.create(
                invocation,
                new TemplateApplication.CreateCommand(SYSTEM_EMPTY, DESIGN));

        var outcome = evaluator.evaluate(new EvaluationCommand(
                new RenderRequestId("render-assembly-1"),
                new OwnerScope("owner-a"),
                created.current().templateId(),
                "{\"rootDocument\":{}}".getBytes(StandardCharsets.UTF_8),
                OutputSelection.defaultPng()));

        assertThat(outcome).isInstanceOf(EvaluationOutcome.SealedDocument.class);
        var sealed = (EvaluationOutcome.SealedDocument) outcome;
        var document = new String(sealed.renderDocumentCanonicalUtf8(), StandardCharsets.UTF_8);
        assertThat(document).contains("\"dslVersion\":\"renderweave-render/1.0\"");
        assertThat(document).contains("\"layoutProfile\":\"renderweave-layout/1.0\"");
        assertThat(document).contains("\"occurrenceId\":\"rwocc_0000000000000000\"");
        // 10mm → 28.346457pt（x360/127 HALF_EVEN ≤6 位）
        assertThat(document).contains("28.346457");
        assertThat(sealed.renderDocumentDigest()).startsWith("sha256:");
        assertThat(sealed.evaluationResultDigest()).startsWith("sha256:");
    }

    @Test
    void evaluatingUnknownTemplateRejectsAtClosureStage() {
        var outcome = evaluator.evaluate(new EvaluationCommand(
                new RenderRequestId("render-assembly-2"),
                new OwnerScope("owner-a"),
                new TemplateApplication.TemplateId("00000000-0000-4000-8000-0000000000f9"),
                "{\"rootDocument\":{}}".getBytes(StandardCharsets.UTF_8),
                OutputSelection.defaultPng()));

        assertThat(outcome).isInstanceOf(EvaluationOutcome.Rejected.class);
        var rejected = (EvaluationOutcome.Rejected) outcome;
        assertThat(rejected.stage())
                .isEqualTo(cn.hbads.renderweave.rendering.api.EvaluationStage.TEMPLATE_CLOSURE);
    }
}
