package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import cn.hbads.renderweave.rendering.spi.RenderingCapabilityRuntime.CapabilityContract;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.DesignSemanticAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.template.internal.TemplateModule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CapabilityDeclarationsTest {

    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));
    private static final String ROOT_ID = "00000000-0000-4000-8000-0000000000a1";
    private static final String CHILD_ID = "00000000-0000-4000-8000-0000000000a2";
    private static final String THIRD_ID = "00000000-0000-4000-8000-0000000000a3";

    @Test
    void collectsExactContractsAcrossEverySemanticClosureSnapshot() {
        var root = snapshot(ROOT_ID, designWithCapability(
                "CLOCK", "UTC_DATE", "date", "today"));
        var child = snapshot(CHILD_ID, designWithCapability(
                "RANDOM", "UNIFORM_DECIMAL_0_1", "decimal", "draw"));

        var outcome = CapabilityDeclarations.scan(
                closure(root, child), TemplateModule.designSemanticAuthority());

        var declared = assertInstanceOf(CapabilityDeclarations.Declared.class, outcome);
        assertEquals(Set.of(CapabilityContract.CLOCK_1_0, CapabilityContract.RANDOM_1_0),
                declared.contracts());
        assertEquals(2, declared.sourceCount());
        assertEquals("renderweave-capability-clock/1.0,renderweave-capability-random/1.0",
                declared.canonicalContractIdentity());
    }

    @Test
    void consumesSemanticValuesInsteadOfSearchingCanonicalBytes() {
        var misleadingBytes = designWithCapability(
                "RANDOM", "UNIFORM_DECIMAL_0_1", "decimal", "draw")
                .getBytes(StandardCharsets.UTF_8);
        var snapshot = new TemplateClosureAuthority.TemplateSnapshot(
                new TemplateApplication.TemplateId(ROOT_ID),
                1,
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                SCHEMA,
                "renderweave-design/1.0",
                "renderweave-expression/1.0",
                misleadingBytes,
                "sha256:" + "1".repeat(64));
        DesignSemanticAuthority noDeclarations = ignored ->
                new DesignSemanticAuthority.Interpreted(
                        new DesignSemanticAuthority.ObjectNode(Map.of(
                                "definitions", new DesignSemanticAuthority.ArrayNode(List.of()))),
                        Map.of());

        var outcome = CapabilityDeclarations.scan(closure(snapshot), noDeclarations);

        var declared = assertInstanceOf(CapabilityDeclarations.Declared.class, outcome);
        assertEquals(Set.of(), declared.contracts());
        assertEquals(0, declared.sourceCount());
    }

    @Test
    void semanticInterpretationFaultIsClosed() {
        var snapshot = snapshot(ROOT_ID, designWithCapability(
                "CLOCK", "UTC_TIME", "time", "now"));
        DesignSemanticAuthority fault = ignored -> new DesignSemanticAuthority.InterpretationFault();

        assertInstanceOf(CapabilityDeclarations.DeclarationFault.class,
                CapabilityDeclarations.scan(closure(snapshot), fault));
    }

    @Test
    void rejectsTheNextStaticSourceThroughTheProductionGuard() {
        var root = snapshot(ROOT_ID, designWithCapability(
                "CLOCK", "UTC_DATE", "date", "today"));
        var child = snapshot(CHILD_ID, designWithCapability(
                "RANDOM", "UNIFORM_DECIMAL_0_1", "decimal", "draw"));
        var third = snapshot(THIRD_ID, designWithCapability(
                "CLOCK", "UTC_TIME", "time", "now"));
        var budget = CapabilityBudget.fromEffectiveVector(capabilityBudgetVector(1));
        var atLimit = assertInstanceOf(CapabilityDeclarations.Declared.class,
                CapabilityDeclarations.scan(
                        closure(root), TemplateModule.designSemanticAuthority(), budget));
        assertEquals(1, atLimit.sourceCount());
        var semanticCalls = new AtomicInteger();
        var authority = TemplateModule.designSemanticAuthority();
        DesignSemanticAuthority countingAuthority = bytes -> {
            semanticCalls.incrementAndGet();
            return authority.interpret(bytes);
        };

        var outcome = CapabilityDeclarations.scan(
                closure(root, child, third), countingAuthority, budget);

        var exceeded = assertInstanceOf(
                CapabilityDeclarations.DeclarationCapacityExceeded.class, outcome);
        assertEquals(EvaluationStage.TEMPLATE_CLOSURE, exceeded.problem().stage());
        assertEquals(ProblemCode.CAPABILITY_BUDGET_EXCEEDED,
                exceeded.problem().code());
        assertEquals("capabilityRuntime.staticCapabilitySources",
                exceeded.problem().limitId().orElseThrow().value());
        assertEquals(2, semanticCalls.get());
    }

    private static TemplateClosureAuthority.ClosureSnapshot closure(
            TemplateClosureAuthority.TemplateSnapshot... snapshots) {
        return new TemplateClosureAuthority.ClosureSnapshot(
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                new TemplateApplication.TemplateId(ROOT_ID),
                1,
                List.of(snapshots),
                List.of());
    }

    private static TemplateClosureAuthority.TemplateSnapshot snapshot(
            String templateId, String designDocument) {
        var admitted = (DesignDslAuthority.Admitted) TemplateModule.designDslAuthority()
                .admit(designDocument.getBytes(StandardCharsets.UTF_8));
        return new TemplateClosureAuthority.TemplateSnapshot(
                new TemplateApplication.TemplateId(templateId),
                1,
                new TemplateClosureAuthority.OwnerScope("owner-a"),
                SCHEMA,
                "renderweave-design/1.0",
                "renderweave-expression/1.0",
                admitted.canonicalUtf8(),
                admitted.contentHash());
    }

    private static String designWithCapability(
            String capability, String operation, String output, String alias) {
        return "{\"dslVersion\":\"renderweave-design/1.0\","
                + "\"expressionProfile\":\"renderweave-expression/1.0\","
                + "\"displayName\":\"R\",\"definitions\":[{"
                + "\"definitionId\":\"00000000-0000-4000-8000-0000000000e1\","
                + "\"kind\":\"expression\",\"displayName\":\"D\","
                + "\"domain\":\"invocation\",\"output\":\"" + output + "\","
                + "\"inputs\":[{\"alias\":\"" + alias + "\",\"source\":{"
                + "\"kind\":\"capability\",\"capability\":\"" + capability + "\","
                + "\"operation\":\"" + operation + "\"}}],"
                + "\"source\":\"input." + alias + "\"}],"
                + "\"designRoot\":{\"nodeId\":\"00000000-0000-4000-8000-000000000001\","
                + "\"kind\":\"canvas\",\"widthMm\":210,\"heightMm\":297,"
                + "\"bindings\":[],\"children\":[]}}";
    }

    private static String capabilityBudgetVector(long staticSources) {
        return "{\"groups\":{\"capabilityRuntime\":{\"limits\":{"
                + "\"staticCapabilitySources\":" + staticSources + ","
                + "\"totalDemands\":8192,\"clockDemands\":4096,"
                + "\"randomDemands\":4096,\"positionCanonicalBytesPerDemand\":2048,"
                + "\"positionCanonicalBytesTotal\":16777216,"
                + "\"capabilityStateRecordBytes\":1048576,"
                + "\"resultDigestStreamingBytes\":16777216,"
                + "\"initializationAttempts\":3,\"randomRejectionAttempts\":128}}}}";
    }
}
