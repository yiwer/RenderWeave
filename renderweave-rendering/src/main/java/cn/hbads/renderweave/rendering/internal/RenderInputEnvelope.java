package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.LimitId;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * RenderInput envelope admission（冻结票据 06）：单一聚合 {@code rootDocument} 与可省略
 * {@code customValues[]}。封闭 strict-JSON：未知 member、重复 key、非对象 assignment 或词法非法
 * definitionId 均拒绝；identity encoding only。全部条目先完成 envelope 结构与全局预算检查，
 * 再交给后续按 definitionId 分组的 Custom 消解。
 */
final class RenderInputEnvelope {

    static final RenderJsonParser.JsonBudget RENDER_INPUT_BUDGET = new RenderJsonParser.JsonBudget(
            "renderInput",
            8_388_608L,   // utf8Bytes
            32,           // jsonDepth
            1024,         // objectMembers
            10_000,       // arrayItems
            250_000L,     // totalValuesAndContainers
            1_048_576L,   // stringUtf8Bytes
            256           // numberTokenBytes
    );

    private static final int MAX_CUSTOM_VALUE_ENTRIES = 256;
    private static final Set<String> ENVELOPE_MEMBERS = Set.of("rootDocument", "customValues");
    private static final Set<String> ASSIGNMENT_MEMBERS = Set.of("definitionId", "value");
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );

    record CustomAssignment(String definitionId, RenderJson value) {
    }

    record ParsedEnvelope(
            RenderJson rootDocument,
            byte[] rootDocumentBytes,
            List<CustomAssignment> assignments
    ) {
        ParsedEnvelope {
            Objects.requireNonNull(rootDocument, "rootDocument");
            Objects.requireNonNull(rootDocumentBytes, "rootDocumentBytes");
            assignments = List.copyOf(assignments);
        }
    }

    sealed interface EnvelopeResult permits EnvelopeAdmitted, EnvelopeRejected {
    }

    record EnvelopeAdmitted(ParsedEnvelope envelope) implements EnvelopeResult {
    }

    record EnvelopeRejected(List<RenderingProblem> problems) implements EnvelopeResult {
        EnvelopeRejected {
            problems = List.copyOf(problems);
            if (problems.isEmpty()) {
                throw new IllegalArgumentException("envelope rejection requires at least one problem");
            }
        }
    }

    private RenderInputEnvelope() {
    }

    static EnvelopeResult parse(byte[] body) {
        Objects.requireNonNull(body, "body");
        var parseResult = RenderJsonParser.parse(body, RENDER_INPUT_BUDGET);
        if (parseResult instanceof RenderJsonParser.ParseRejected rejected) {
            return new EnvelopeRejected(List.of(toProblem(rejected.failure())));
        }
        var value = ((RenderJsonParser.Parsed) parseResult).value();
        if (!(value instanceof RenderJson.ObjectValue envelope)) {
            return rejected("", "RenderInput must be an object");
        }
        for (var member : envelope.members().keySet()) {
            if (!ENVELOPE_MEMBERS.contains(member)) {
                return rejected("/" + member, "RenderInput contains an unknown member");
            }
        }
        var rootDocument = envelope.members().get("rootDocument");
        if (rootDocument == null) {
            return rejected("/rootDocument", "rootDocument is required");
        }
        if (!(rootDocument instanceof RenderJson.ObjectValue)) {
            return rejected("/rootDocument", "rootDocument must be an object");
        }
        var assignments = new ArrayList<CustomAssignment>();
        var customValues = envelope.members().get("customValues");
        if (customValues != null) {
            if (!(customValues instanceof RenderJson.ArrayValue entries)) {
                return rejected("/customValues", "customValues must be an array");
            }
            if (entries.items().size() > MAX_CUSTOM_VALUE_ENTRIES) {
                return new EnvelopeRejected(List.of(new RenderingProblem(
                        ProblemCode.RENDER_INPUT_LIMIT_EXCEEDED,
                        EvaluationStage.REQUEST_ADMISSION,
                        Optional.of("/customValues"),
                        Optional.of(new LimitId("renderInput.customValueEntries"))
                )));
            }
            for (int index = 0; index < entries.items().size(); index++) {
                var pointer = "/customValues/" + index;
                if (!(entries.items().get(index) instanceof RenderJson.ObjectValue assignment)) {
                    return rejected(pointer, "each customValues entry must be an object");
                }
                for (var member : assignment.members().keySet()) {
                    if (!ASSIGNMENT_MEMBERS.contains(member)) {
                        return rejected(pointer + "/" + member,
                                "customValues entries must carry exactly definitionId and value");
                    }
                }
                var definitionIdValue = assignment.members().get("definitionId");
                if (definitionIdValue == null) {
                    return rejected(pointer + "/definitionId", "definitionId is required");
                }
                if (!(definitionIdValue instanceof RenderJson.StringValue definitionId)) {
                    return rejected(pointer + "/definitionId", "definitionId must be a string");
                }
                if (!UUID_V4.matcher(definitionId.value()).matches()) {
                    return rejected(pointer + "/definitionId",
                            "definitionId must use the UUID v4 lexical domain");
                }
                var assignmentValue = assignment.members().get("value");
                if (assignmentValue == null) {
                    return rejected(pointer + "/value", "value is required");
                }
                assignments.add(new CustomAssignment(definitionId.value(), assignmentValue));
            }
        }
        var rootBytes = new byte[(int) (rootDocument.endByte() - rootDocument.startByte())];
        System.arraycopy(body, (int) rootDocument.startByte(), rootBytes, 0, rootBytes.length);
        return new EnvelopeAdmitted(new ParsedEnvelope(rootDocument, rootBytes, assignments));
    }

    private static RenderingProblem toProblem(RenderJsonParser.JsonParseFailure failure) {
        return switch (failure.kind()) {
            case LIMIT_EXCEEDED -> new RenderingProblem(
                    ProblemCode.RENDER_INPUT_LIMIT_EXCEEDED,
                    EvaluationStage.REQUEST_ADMISSION,
                    pointer(failure.pointer()),
                    Optional.of(new LimitId(failure.limitId()))
            );
            case CONTENT_ENCODING_UNSUPPORTED, SYNTAX_INVALID, DUPLICATE_MEMBER, VALUE_EXPECTED ->
                    new RenderingProblem(
                            ProblemCode.RENDER_INPUT_CONTENT_ENCODING_UNSUPPORTED,
                            EvaluationStage.REQUEST_ADMISSION,
                            pointer(failure.pointer()),
                            Optional.empty()
                    );
        };
    }

    private static EnvelopeRejected rejected(String pointer, String ignoredReason) {
        return new EnvelopeRejected(List.of(new RenderingProblem(
                ProblemCode.RENDER_INPUT_CONTENT_ENCODING_UNSUPPORTED,
                EvaluationStage.REQUEST_ADMISSION,
                pointer(pointer),
                Optional.empty()
        )));
    }

    private static Optional<String> pointer(String value) {
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
