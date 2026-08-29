package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.EvaluationStage;
import cn.hbads.renderweave.rendering.api.RenderingProblem;
import cn.hbads.renderweave.rendering.api.RenderingProblem.ProblemCode;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority.TemplateSnapshot;
import cn.hbads.renderweave.schema.definition.ArrayValue;
import cn.hbads.renderweave.schema.definition.BooleanValue;
import cn.hbads.renderweave.schema.definition.DateValue;
import cn.hbads.renderweave.schema.definition.DecimalValue;
import cn.hbads.renderweave.schema.definition.ReferenceValue;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.definition.TextValue;
import cn.hbads.renderweave.schema.definition.TimeValue;
import cn.hbads.renderweave.schema.definition.ValueDescriptor;
import cn.hbads.renderweave.validation.InvalidValidationRequestException;
import cn.hbads.renderweave.validation.ResolvedSchema;
import cn.hbads.renderweave.validation.ResolvedSchemaIdentity;
import cn.hbads.renderweave.validation.ResolvedValidationTarget;
import cn.hbads.renderweave.validation.RootDocumentValidator;
import cn.hbads.renderweave.validation.StrictJsonValue;
import cn.hbads.renderweave.validation.ValidationBatchRequestParser;
import cn.hbads.renderweave.validation.ValidationTarget;
import cn.hbads.renderweave.validation.ValidationTargetResolver;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * INPUT_ADMISSION（冻结规格 stage 4，票据 06）：envelope 检查 → 按根 TemplateSnapshot 的精确
 * StaticSchemaRef 权威验证（保留 decimal 原始 token 的无损边界）→ closed typed context view →
 * 根 Custom override 消解，形成不可变 {@link AdmittedRenderInput}。Schema 目标只来自
 * TemplateSnapshot；调用方不能选择、重复或覆盖 Schema。任何失败不创建 Evaluation frame。
 */
final class InputAdmission {

    private static final int MAX_REPORTED_PROBLEMS = 16;

    sealed interface AdmissionResult
            permits AdmissionAdmitted, AdmissionRejected, AdmissionUnavailable,
            AdmissionDeadlineExceeded {
    }

    record AdmissionAdmitted(AdmittedRenderInput input) implements AdmissionResult {
    }

    record AdmissionRejected(List<RenderingProblem> problems) implements AdmissionResult {
        AdmissionRejected {
            problems = List.copyOf(problems);
            if (problems.isEmpty()) {
                throw new IllegalArgumentException("admission rejection requires at least one problem");
            }
        }
    }

    /** 依赖（Schema 解析）不可用：失败封闭，由编排层决定重试语义。 */
    record AdmissionUnavailable() implements AdmissionResult {
    }

    record AdmissionDeadlineExceeded() implements AdmissionResult {
    }

    /** 已准入文档与权威验证之间的不变量违约：对外折叠 RENDER_INTERNAL_ERROR。 */
    private static final class InternalFault extends RuntimeException {
    }

    private InputAdmission() {
    }

    static AdmissionResult admit(
            byte[] envelopeBody,
            TemplateSnapshot rootSnapshot,
            ValidationTargetResolver resolver
    ) {
        return admit(
                envelopeBody,
                rootSnapshot,
                resolver,
                EvaluationStageControl.unbounded());
    }

    static AdmissionResult admit(
            byte[] envelopeBody,
            TemplateSnapshot rootSnapshot,
            ValidationTargetResolver resolver,
            EvaluationStageControl stageControl
    ) {
        Objects.requireNonNull(envelopeBody, "envelopeBody");
        Objects.requireNonNull(rootSnapshot, "rootSnapshot");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(stageControl, "stageControl");
        try {
            return admitControlled(envelopeBody, rootSnapshot, resolver, stageControl);
        } catch (EvaluationStageControl.DeadlineExceeded ignored) {
            return new AdmissionDeadlineExceeded();
        }
    }

    private static AdmissionResult admitControlled(
            byte[] envelopeBody,
            TemplateSnapshot rootSnapshot,
            ValidationTargetResolver resolver,
            EvaluationStageControl stageControl
    ) {
        stageControl.checkpoint();

        var envelopeResult = RenderInputEnvelope.parse(envelopeBody);
        if (envelopeResult instanceof RenderInputEnvelope.EnvelopeRejected rejected) {
            return new AdmissionRejected(rejected.problems());
        }
        stageControl.checkpoint();
        var envelope = ((RenderInputEnvelope.EnvelopeAdmitted) envelopeResult).envelope();

        var customsResult = CustomDefinitionView.extract(rootSnapshot.canonicalDesignDslUtf8());
        if (customsResult instanceof CustomDefinitionView.ExtractionFailed) {
            return internalError();
        }
        stageControl.checkpoint();
        var customs = ((CustomDefinitionView.Extracted) customsResult).byDefinitionId();

        ResolvedValidationTarget target;
        stageControl.checkpoint();
        try {
            target = resolver.resolve(new ValidationTarget.StaticTarget(rootSnapshot.staticSchema()));
        } catch (RuntimeException unavailable) {
            return new AdmissionUnavailable();
        }
        stageControl.checkpoint();

        var batchBytes = spliceBatchRequest(rootSnapshot.staticSchema(), envelope.rootDocumentBytes());
        StrictJsonValue rootDocument;
        stageControl.checkpoint();
        try {
            var parsedBatch = new ValidationBatchRequestParser().parse(batchBytes);
            var validator = new RootDocumentValidator();
            var result = validator.validate(0, parsedBatch.documents().get(0), target);
            if (!result.valid()) {
                var problems = new ArrayList<RenderingProblem>();
                for (var problem : result.problems()) {
                    stageControl.checkpoint();
                    if (problems.size() == MAX_REPORTED_PROBLEMS) {
                        break;
                    }
                    problems.add(problemAt(problem.instancePath()));
                }
                if (problems.isEmpty()) {
                    problems.add(problemAt(""));
                }
                return new AdmissionRejected(problems);
            }
            rootDocument = parsedBatch.documents().get(0);
        } catch (InvalidValidationRequestException invalid) {
            return new AdmissionRejected(List.of(new RenderingProblem(
                    ProblemCode.EVALUATION_FAILED,
                    EvaluationStage.INPUT_ADMISSION,
                    invalid.pointer().isEmpty() ? Optional.empty() : Optional.of(invalid.pointer()),
                    Optional.empty()
            )));
        }
        stageControl.checkpoint();

        try {
            if (!(rootDocument instanceof StrictJsonValue.ObjectValue documentObject)) {
                throw new InternalFault();
            }
            var typedRoot = buildTypedObject(
                    documentObject, target.rootSchema(), target, stageControl);

            var winners = new LinkedHashMap<String, IndexedAssignment>();
            for (int index = 0; index < envelope.assignments().size(); index++) {
                stageControl.checkpoint();
                var assignment = envelope.assignments().get(index);
                winners.put(assignment.definitionId(), new IndexedAssignment(index, assignment.value()));
            }
            var effectiveCustoms = new LinkedHashMap<String, DesignValue>();
            var externalCustomOverrides = new LinkedHashMap<String, DesignValue>();
            for (var definition : customs.values()) {
                stageControl.checkpoint();
                var winner = winners.get(definition.definitionId());
                if (definition.exposure() != CustomDefinitionView.Exposure.PUBLIC || winner == null) {
                    effectiveCustoms.put(definition.definitionId(), definition.defaultValue());
                    continue;
                }
                var decoded = DesignValueDecoder.decode(
                        winner.value(), definition.valueType(),
                        "/customValues/" + winner.index() + "/value");
                if (!(decoded instanceof DesignValueDecoder.Decoded success)) {
                    return new AdmissionRejected(List.of(problemAt(
                            "/customValues/" + winner.index() + "/value")));
                }
                stageControl.checkpoint();
                effectiveCustoms.put(definition.definitionId(), success.value());
                externalCustomOverrides.put(definition.definitionId(), success.value());
            }
            return new AdmissionAdmitted(new AdmittedRenderInput(
                    rootSnapshot.staticSchema(),
                    typedRoot,
                    effectiveCustoms,
                    externalCustomOverrides));
        } catch (InternalFault fault) {
            return internalError();
        }
    }

    private record IndexedAssignment(int index, RenderJson value) {
    }

    private static TypedObject buildTypedObject(
            StrictJsonValue.ObjectValue document,
            ResolvedSchema schema,
            ResolvedValidationTarget target,
            EvaluationStageControl stageControl
    ) {
        stageControl.checkpoint();
        if (!(schema.identity() instanceof ResolvedSchemaIdentity.StaticIdentity staticIdentity)) {
            throw new InternalFault();
        }
        var fields = new LinkedHashMap<String, Optional<TypedValue>>();
        for (var field : schema.definition().fields()) {
            stageControl.checkpoint();
            var member = document.members().get(field.fieldKey().value());
            if (member == null) {
                if (field.required()) {
                    throw new InternalFault();
                }
                fields.put(field.fieldKey().value(), Optional.empty());
                continue;
            }
            fields.put(
                    field.fieldKey().value(),
                    Optional.of(buildTypedValue(
                            member, field.value(), target, stageControl))
            );
        }
        return new TypedObject(staticIdentity.reference(), fields);
    }

    private static TypedValue buildTypedValue(
            StrictJsonValue value,
            ValueDescriptor descriptor,
            ResolvedValidationTarget target,
            EvaluationStageControl stageControl
    ) {
        stageControl.checkpoint();
        if (descriptor instanceof TextValue) {
            if (value instanceof StrictJsonValue.StringValue string) {
                return new TypedValue.Text(string.value());
            }
            throw new InternalFault();
        }
        if (descriptor instanceof DecimalValue) {
            if (value instanceof StrictJsonValue.NumberValue number) {
                return new TypedValue.Decimal(new BigDecimal(number.rawToken()));
            }
            throw new InternalFault();
        }
        if (descriptor instanceof BooleanValue) {
            if (value instanceof StrictJsonValue.BooleanValue bool) {
                return new TypedValue.Bool(bool.value());
            }
            throw new InternalFault();
        }
        if (descriptor instanceof DateValue) {
            if (value instanceof StrictJsonValue.StringValue string) {
                return new TypedValue.Date(string.value());
            }
            throw new InternalFault();
        }
        if (descriptor instanceof TimeValue) {
            if (value instanceof StrictJsonValue.StringValue string) {
                return new TypedValue.Time(string.value());
            }
            throw new InternalFault();
        }
        if (descriptor instanceof ReferenceValue reference) {
            if (!(value instanceof StrictJsonValue.ObjectValue nested)) {
                throw new InternalFault();
            }
            var resolved = target.resolve(reference.ref());
            return new TypedValue.Nested(
                    staticReference(resolved),
                    buildTypedObject(nested, resolved, target, stageControl)
            );
        }
        if (descriptor instanceof ArrayValue array) {
            if (!(value instanceof StrictJsonValue.ArrayValue items)) {
                throw new InternalFault();
            }
            var typedItems = new ArrayList<TypedValue>(items.items().size());
            for (var item : items.items()) {
                stageControl.checkpoint();
                typedItems.add(buildTypedValue(
                        item, array.items(), target, stageControl));
            }
            return new TypedValue.Array(typedItems);
        }
        throw new InternalFault();
    }

    private static StaticSchemaRef staticReference(ResolvedSchema schema) {
        if (!(schema.identity() instanceof ResolvedSchemaIdentity.StaticIdentity staticIdentity)) {
            throw new InternalFault();
        }
        return staticIdentity.reference();
    }

    private static byte[] spliceBatchRequest(StaticSchemaRef reference, byte[] rootDocumentBytes) {
        var prefix = "{\"target\":{\"kind\":\"static\",\"schemaKey\":\""
                + reference.schemaKey().value()
                + "\",\"versionTag\":\""
                + reference.versionTag().value()
                + "\"},\"documents\":[{\"document\":";
        var suffix = "}]}";
        var prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        var suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        var spliced = new byte[prefixBytes.length + rootDocumentBytes.length + suffixBytes.length];
        System.arraycopy(prefixBytes, 0, spliced, 0, prefixBytes.length);
        System.arraycopy(rootDocumentBytes, 0, spliced, prefixBytes.length, rootDocumentBytes.length);
        System.arraycopy(suffixBytes, 0, spliced,
                prefixBytes.length + rootDocumentBytes.length, suffixBytes.length);
        return spliced;
    }

    private static AdmissionRejected internalError() {
        return new AdmissionRejected(List.of(RenderingProblem.of(
                ProblemCode.RENDER_INTERNAL_ERROR, EvaluationStage.INPUT_ADMISSION)));
    }

    private static RenderingProblem problemAt(String safeLocation) {
        return new RenderingProblem(
                ProblemCode.EVALUATION_FAILED,
                EvaluationStage.INPUT_ADMISSION,
                safeLocation == null || safeLocation.isEmpty()
                        ? Optional.empty()
                        : Optional.of(safeLocation),
                Optional.empty()
        );
    }
}
