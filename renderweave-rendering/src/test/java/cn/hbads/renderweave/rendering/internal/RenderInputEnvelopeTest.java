package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderInputEnvelopeTest {

    private static final String DEFINITION_ID = "00000000-0000-4000-8000-0000000000d1";

    @Test
    void minimalEnvelopeAdmitsWithVerbatimRootDocumentBytes() {
        var body = "{\"rootDocument\":{\"name\":\"a\",\"amount\":30.50}}".getBytes(StandardCharsets.UTF_8);

        var result = RenderInputEnvelope.parse(body);

        var admitted = assertInstanceOf(RenderInputEnvelope.EnvelopeAdmitted.class, result);
        var envelope = admitted.envelope();
        assertEquals(0, envelope.assignments().size());
        assertEquals("{\"name\":\"a\",\"amount\":30.50}",
                new String(envelope.rootDocumentBytes(), StandardCharsets.UTF_8));
        var root = assertInstanceOf(RenderJson.ObjectValue.class, envelope.rootDocument());
        assertEquals(2, root.members().size());
    }

    @Test
    void customValuesAssignmentsAreCaptured() {
        var body = ("{\"rootDocument\":{},"
                + "\"customValues\":[{\"definitionId\":\"" + DEFINITION_ID + "\",\"value\":\"win\"}]}")
                .getBytes(StandardCharsets.UTF_8);

        var admitted = assertInstanceOf(
                RenderInputEnvelope.EnvelopeAdmitted.class, RenderInputEnvelope.parse(body));

        assertEquals(1, admitted.envelope().assignments().size());
        assertEquals(DEFINITION_ID, admitted.envelope().assignments().get(0).definitionId());
        assertEquals("win", ((RenderJson.StringValue) admitted.envelope().assignments().get(0).value()).value());
    }

    @Test
    void omittedCustomValuesEqualsEmptyList() {
        var admitted = assertInstanceOf(
                RenderInputEnvelope.EnvelopeAdmitted.class,
                RenderInputEnvelope.parse("{\"rootDocument\":{}}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, admitted.envelope().assignments().size());
    }

    @Test
    void unknownEnvelopeMemberIsRejected() {
        assertEnvelopeRejected("{\"rootDocument\":{},\"extra\":1}");
    }

    @Test
    void missingRootDocumentIsRejected() {
        assertEnvelopeRejected("{\"customValues\":[]}");
    }

    @Test
    void nonObjectRootDocumentIsRejected() {
        assertEnvelopeRejected("{\"rootDocument\":[1,2]}");
    }

    @Test
    void nonObjectAssignmentIsRejected() {
        assertEnvelopeRejected("{\"rootDocument\":{},\"customValues\":[\"x\"]}");
    }

    @Test
    void assignmentWithUnknownMemberIsRejected() {
        assertEnvelopeRejected("{\"rootDocument\":{},\"customValues\":[{"
                + "\"definitionId\":\"" + DEFINITION_ID + "\",\"value\":1,\"extra\":2}]}");
    }

    @Test
    void assignmentMissingValueIsRejected() {
        assertEnvelopeRejected("{\"rootDocument\":{},\"customValues\":[{"
                + "\"definitionId\":\"" + DEFINITION_ID + "\"}]}");
    }

    @Test
    void lexicallyInvalidDefinitionIdIsRejected() {
        assertEnvelopeRejected("{\"rootDocument\":{},\"customValues\":[{"
                + "\"definitionId\":\"not-a-uuid\",\"value\":1}]}");
    }

    @Test
    void duplicateMembersAreRejected() {
        assertEnvelopeRejected("{\"rootDocument\":{},\"rootDocument\":{}}");
    }

    @Test
    void trailingContentIsRejected() {
        assertEnvelopeRejected("{\"rootDocument\":{}} {}");
    }

    @Test
    void invalidUtf8IsContentEncodingUnsupported() {
        var body = new byte[] { '{', '"', 'r', '"', ':', (byte) 0xFF, '}' };

        var rejected = assertInstanceOf(
                RenderInputEnvelope.EnvelopeRejected.class, RenderInputEnvelope.parse(body));

        assertEquals(
                RenderingProblem.ProblemCode.RENDER_INPUT_CONTENT_ENCODING_UNSUPPORTED,
                rejected.problems().get(0).code());
    }

    @Test
    void objectMemberCountBeyondBudgetIsLimitExceeded() {
        var builder = new StringBuilder("{\"rootDocument\":{");
        for (int index = 0; index < 1025; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append("m").append(index).append("\":1");
        }
        builder.append("}}");

        var rejected = assertLimitRejected(builder.toString());
        assertEquals("renderInput.objectMembers", rejected.problems().get(0).limitId().orElseThrow().value());
    }

    @Test
    void arrayItemBudgetIsLimitExceeded() {
        var builder = new StringBuilder("{\"rootDocument\":{\"a\":[");
        for (int index = 0; index < 10_001; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('1');
        }
        builder.append("]}}");

        var rejected = assertLimitRejected(builder.toString());
        assertEquals("renderInput.arrayItems", rejected.problems().get(0).limitId().orElseThrow().value());
    }

    @Test
    void numberTokenBudgetIsLimitExceeded() {
        var builder = new StringBuilder("{\"rootDocument\":{\"a\":0.");
        builder.append("1".repeat(300));
        builder.append("}}");

        var rejected = assertLimitRejected(builder.toString());
        assertEquals("renderInput.numberTokenBytes", rejected.problems().get(0).limitId().orElseThrow().value());
    }

    @Test
    void stringBudgetIsLimitExceeded() {
        var builder = new StringBuilder("{\"rootDocument\":{\"a\":\"");
        builder.append("x".repeat(1_048_577));
        builder.append("\"}}");

        var rejected = assertLimitRejected(builder.toString());
        assertEquals("renderInput.stringUtf8Bytes", rejected.problems().get(0).limitId().orElseThrow().value());
    }

    @Test
    void depthBudgetIsLimitExceeded() {
        var builder = new StringBuilder("{\"rootDocument\":");
        for (int index = 0; index < 33; index++) {
            builder.append('[');
        }
        builder.append('1');
        for (int index = 0; index < 33; index++) {
            builder.append(']');
        }
        builder.append('}');

        var rejected = assertLimitRejected(builder.toString());
        assertEquals("renderInput.jsonDepth", rejected.problems().get(0).limitId().orElseThrow().value());
    }

    @Test
    void customValueEntriesBudgetIsLimitExceeded() {
        var builder = new StringBuilder("{\"rootDocument\":{},\"customValues\":[");
        for (int index = 0; index < 257; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append("{\"definitionId\":\"")
                    .append(String.format("00000000-0000-4000-8000-%012d", index + 1))
                    .append("\",\"value\":1}");
        }
        builder.append("]}");

        var rejected = assertLimitRejected(builder.toString());
        assertEquals("renderInput.customValueEntries", rejected.problems().get(0).limitId().orElseThrow().value());
    }

    @Test
    void escapedUnicodeRoundTripsAndCountsUtf8Bytes() {
        var body = "{\"rootDocument\":{\"a\":\"\\u00e9\\ud83d\\ude00\"}}".getBytes(StandardCharsets.UTF_8);

        var admitted = assertInstanceOf(
                RenderInputEnvelope.EnvelopeAdmitted.class, RenderInputEnvelope.parse(body));

        var root = (RenderJson.ObjectValue) admitted.envelope().rootDocument();
        var value = (RenderJson.StringValue) root.members().get("a");
        assertEquals("é😀", value.value());
    }

    private static void assertEnvelopeRejected(String body) {
        var rejected = assertInstanceOf(
                RenderInputEnvelope.EnvelopeRejected.class,
                RenderInputEnvelope.parse(body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(EvaluationStage.REQUEST_ADMISSION, rejected.problems().get(0).stage());
    }

    private static RenderInputEnvelope.EnvelopeRejected assertLimitRejected(String body) {
        var rejected = assertInstanceOf(
                RenderInputEnvelope.EnvelopeRejected.class,
                RenderInputEnvelope.parse(body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                RenderingProblem.ProblemCode.RENDER_INPUT_LIMIT_EXCEEDED,
                rejected.problems().get(0).code());
        assertTrue(rejected.problems().get(0).limitId().isPresent());
        return rejected;
    }
}
