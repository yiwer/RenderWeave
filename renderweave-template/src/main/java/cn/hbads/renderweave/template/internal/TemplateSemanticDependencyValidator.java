package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.schema.definition.ArrayValue;
import cn.hbads.renderweave.schema.definition.ReferenceValue;
import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.definition.SchemaRef;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.definition.ValueDescriptor;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.DependencyResolution;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Template-owned E4b semantic dependency validator. It consumes only admitted canonical
 * DesignDSL, exact immutable StaticSchema definitions and exact child-current facts. It
 * never reads RootDocument values and never delegates RenderWeave semantics to a generic
 * JSON Schema validator.
 */
final class TemplateSemanticDependencyValidator {
    private static final StaticSchemaRef SYSTEM_EMPTY = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"), VersionTag.of("v1"));

    private final StaticSchemaAuthority schemas;
    private final DesignDslAuthority designs;
    private final StrictJsonParser parser = new StrictJsonParser();

    TemplateSemanticDependencyValidator(
            StaticSchemaAuthority schemas,
            DesignDslAuthority designs
    ) {
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.designs = Objects.requireNonNull(designs, "designs");
    }

    record Validation(
            List<TemplateApplication.ValidationProblem> problems,
            boolean hard
    ) {
        Validation {
            problems = List.copyOf(problems);
        }
    }

    static final class Unavailable extends RuntimeException {
        Unavailable() {
            super("StaticSchema semantic authority unavailable");
        }
    }

    Validation validate(
            byte[] canonicalDesignDslUtf8,
            StaticSchemaRef rootSchema,
            Map<String, DependencyResolution.TemplateState> templates
    ) {
        return validate(
                canonicalDesignDslUtf8,
                rootSchema,
                templates,
                new TemplateProblemBudget()
        );
    }

    Validation validate(
            byte[] canonicalDesignDslUtf8,
            StaticSchemaRef rootSchema,
            Map<String, DependencyResolution.TemplateState> templates,
            TemplateProblemBudget problems
    ) {
        Objects.requireNonNull(canonicalDesignDslUtf8, "canonicalDesignDslUtf8");
        Objects.requireNonNull(rootSchema, "rootSchema");
        Objects.requireNonNull(templates, "templates");
        var context = new ValidationContext(
                rootSchema,
                templates,
                Objects.requireNonNull(problems, "problems")
        );
        final JsonValue.ObjectValue document;
        try {
            var parsed = parser.parse(canonicalDesignDslUtf8);
            if (!(parsed instanceof JsonValue.ObjectValue object)) {
                context.hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", "");
                return context.result();
            }
            document = object;
        } catch (DesignDslFailureException invariantFault) {
            context.hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", "");
            return context.result();
        }
        context.validate(document);
        return context.result();
    }

    private final class ValidationContext {
        private final StaticSchemaRef rootSchema;
        private final Map<String, DependencyResolution.TemplateState> templates;
        private final Map<StaticSchemaRef, SchemaDefinition> schemaDefinitions = new HashMap<>();
        private final Map<String, ChildDesign> childDesigns = new HashMap<>();
        private final Set<String> invalidChildDesigns = new HashSet<>();
        private final Map<String, DefinitionInfo> definitions = new LinkedHashMap<>();
        private final Map<String, Scope> loopScopes = new HashMap<>();
        private final TemplateProblemBudget problems;
        private boolean hard;
        private boolean stopped;

        private ValidationContext(
                StaticSchemaRef rootSchema,
                Map<String, DependencyResolution.TemplateState> templates,
                TemplateProblemBudget problems
        ) {
            this.rootSchema = rootSchema;
            this.templates = Map.copyOf(templates);
            this.problems = problems;
            this.stopped = problems.stopped();
        }

        private void validate(JsonValue.ObjectValue document) {
            loadDefinitions(document);
            var root = objectMember(document, "designRoot");
            if (root == null) {
                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", "/designRoot");
                return;
            }
            var rootScope = Scope.root(rootSchema);
            deriveLoopContexts(root, "/designRoot", rootScope);
            if (stopped) {
                return;
            }
            validateDefinitions();
            if (stopped) {
                return;
            }
            validateNode(root, "/designRoot", rootScope);
        }

        private void loadDefinitions(JsonValue.ObjectValue document) {
            var entries = arrayMember(document, "definitions");
            if (entries == null) {
                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", "/definitions");
                return;
            }
            for (int index = 0; index < entries.items().size(); index++) {
                if (!(entries.items().get(index) instanceof JsonValue.ObjectValue definition)) {
                    hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", "/definitions/" + index);
                    continue;
                }
                var id = text(definition, "definitionId");
                var kind = text(definition, "kind");
                if (id == null || kind == null) {
                    hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", "/definitions/" + index);
                    continue;
                }
                var type = switch (kind) {
                    case "custom" -> valueType(definition.members().get("valueType"));
                    case "mapping", "expression" -> valueType(definition.members().get("output"));
                    default -> null;
                };
                if (type == null) {
                    hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", "/definitions/" + index);
                    continue;
                }
                definitions.put(id, new DefinitionInfo(
                        id,
                        kind,
                        "custom".equals(kind) ? "PUBLIC".equals(text(definition, "exposure")) : false,
                        type,
                        domainLoopId(definition.members().get("domain")),
                        definition,
                        "/definitions/" + index
                ));
            }
        }

        /** Preorder derives each Repeat item context from its parent lexical scope. */
        private void deriveLoopContexts(
                JsonValue.ObjectValue node,
                String pointer,
                Scope scope
        ) {
            if (stopped) {
                return;
            }
            var kind = text(node, "kind");
            var childScope = scope;
            if ("repeat".equals(kind)) {
                var loopId = text(node, "loopId");
                var itemSource = objectMember(node, "items");
                StaticType itemCollection = StaticType.unknown();
                if (loopId == null || itemSource == null) {
                    hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", pointer);
                } else {
                    itemCollection = resolveSource(
                            itemSource, pointer + "/items", scope, false, true);
                }
                var itemContext = itemContext(itemCollection, pointer + "/items");
                childScope = scope.withLoop(loopId, itemContext);
                loopScopes.put(loopId, childScope);
            }
            var children = arrayMember(node, "children");
            if (children == null) {
                return;
            }
            for (int index = 0; index < children.items().size(); index++) {
                if (children.items().get(index) instanceof JsonValue.ObjectValue child) {
                    deriveLoopContexts(child, pointer + "/children/" + index, childScope);
                }
            }
        }

        private ContextProof itemContext(StaticType collection, String pointer) {
            var scalarItemType = collection.scalarListItemType();
            if (scalarItemType != null
                    && Set.of("text", "decimal", "date", "time", "boolean")
                    .contains(scalarItemType)) {
                return ContextProof.known(systemBasic(scalarItemType));
            }
            if (collection.kind() == TypeKind.REFERENCE_LIST) {
                return ContextProof.known(collection.reference());
            }
            if (collection.kind() != TypeKind.UNKNOWN) {
                dependency("TEMPLATE_REPEAT_ITEMS_TYPE_MISMATCH", pointer);
            }
            return ContextProof.unknown();
        }

        private StaticSchemaRef systemBasic(String scalarType) {
            return new StaticSchemaRef(
                    SchemaKey.systemProvided("system-basic-" + scalarType),
                    VersionTag.of("v1")
            );
        }

        private void validateDefinitions() {
            for (var definition : definitions.values()) {
                if (stopped || "custom".equals(definition.kind())) {
                    continue;
                }
                var scope = definition.domainLoopId() == null
                        ? Scope.root(rootSchema)
                        : loopScopes.get(definition.domainLoopId());
                if (scope == null) {
                    hard("TEMPLATE_LEXICAL_DOMAIN_INVALID", definition.pointer() + "/domain/loopId");
                    continue;
                }
                if ("mapping".equals(definition.kind())) {
                    validateMapping(definition, scope);
                } else if ("expression".equals(definition.kind())) {
                    validateExpressionInputs(definition, scope);
                }
            }
        }

        private void validateMapping(DefinitionInfo definition, Scope scope) {
            var body = definition.body();
            var input = objectMember(body, "input");
            var inputType = StaticType.unknown();
            if (input != null) {
                inputType = resolveSource(
                        input, definition.pointer() + "/input", scope, true);
            }
            var cases = arrayMember(body, "cases");
            if (cases != null) {
                for (int index = 0; index < cases.items().size(); index++) {
                    if (cases.items().get(index) instanceof JsonValue.ObjectValue entry) {
                        var operand = objectMember(entry, "operand");
                        if (operand != null) {
                            var operandType = valueType(operand.members().get("valueType"));
                            if (inputType.isValue()
                                    && operandType != null
                                    && !operandType.equals(inputType.valueType())) {
                                classifiedTypeMismatch(
                                        inputType,
                                        "TEMPLATE_MAPPING_INPUT_TYPE_MISMATCH",
                                        definition.pointer() + "/cases/" + index + "/operand"
                                );
                            }
                        }
                        validateDeclaredResult(
                                objectMember(entry, "then"),
                                definition.pointer() + "/cases/" + index + "/then",
                                scope,
                                definition.valueType()
                        );
                    }
                }
            }
            validateDeclaredResult(
                    objectMember(body, "otherwise"),
                    definition.pointer() + "/otherwise",
                    scope,
                    definition.valueType()
            );
        }

        private void validateExpressionInputs(DefinitionInfo definition, Scope scope) {
            var inputs = arrayMember(definition.body(), "inputs");
            if (inputs == null) {
                return;
            }
            for (int index = 0; index < inputs.items().size(); index++) {
                if (inputs.items().get(index) instanceof JsonValue.ObjectValue input) {
                    var source = objectMember(input, "source");
                    if (source != null) {
                        resolveSource(source,
                                definition.pointer() + "/inputs/" + index + "/source",
                                scope, true);
                    }
                }
            }
        }

        private void validateDeclaredResult(
                JsonValue.ObjectValue source,
                String pointer,
                Scope scope,
                String expectedType
        ) {
            if (source == null) {
                return;
            }
            var actual = resolveSource(source, pointer, scope, false);
            if (actual.isValue() && !expectedType.equals(actual.valueType())) {
                if (actual.dependencyDriven()) {
                    dependency("TEMPLATE_VALUE_SOURCE_TYPE_MISMATCH", pointer);
                } else {
                    hard("TEMPLATE_VALUE_SOURCE_TYPE_MISMATCH", pointer);
                }
            }
        }

        private void validateNode(JsonValue.ObjectValue node, String pointer, Scope scope) {
            if (stopped) {
                return;
            }
            var bindings = arrayMember(node, "bindings");
            if (bindings != null) {
                for (int index = 0; index < bindings.items().size(); index++) {
                    if (bindings.items().get(index) instanceof JsonValue.ObjectValue binding) {
                        var source = objectMember(binding, "source");
                        if (source != null) {
                            var actual = resolveSource(source,
                                    pointer + "/bindings/" + index + "/source",
                                    scope, false);
                            var targetType = bindingTargetType(node, binding);
                            if (targetType == null) {
                                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH",
                                        pointer + "/bindings/" + index
                                                + "/targetPropertyRef");
                            } else if (actual.isValue()
                                    && !targetType.equals(actual.valueType())) {
                                classifiedTypeMismatch(
                                        actual,
                                        "TEMPLATE_VALUE_SOURCE_TYPE_MISMATCH",
                                        pointer + "/bindings/" + index + "/source"
                                );
                            }
                        }
                    }
                }
            }

            var kind = text(node, "kind");
            var childScope = scope;
            if ("repeat".equals(kind)) {
                var loopId = text(node, "loopId");
                childScope = loopScopes.getOrDefault(loopId, scope.withLoop(
                        loopId, ContextProof.unknown()));
            } else if ("conditional".equals(kind)) {
                var condition = objectMember(node, "condition");
                if (condition != null) {
                    var type = resolveSource(condition, pointer + "/condition", scope, true);
                    if (type.isValue() && !"boolean".equals(type.valueType())) {
                        classifiedTypeMismatch(
                                type, "TEMPLATE_CONDITION_TYPE_MISMATCH", pointer + "/condition");
                    }
                }
            } else if ("templateUse".equals(kind)) {
                validateTemplateUse(node, pointer, scope);
            }

            var children = arrayMember(node, "children");
            if (children == null) {
                return;
            }
            for (int index = 0; index < children.items().size(); index++) {
                if (children.items().get(index) instanceof JsonValue.ObjectValue child) {
                    validateNode(child, pointer + "/children/" + index, childScope);
                }
            }
        }

        private void validateTemplateUse(
                JsonValue.ObjectValue node,
                String pointer,
                Scope scope
        ) {
            var ref = objectMember(node, "templateRef");
            var targetId = ref == null ? null : text(ref, "templateId");
            if (targetId == null) {
                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH",
                        pointer + "/templateRef/templateId");
                return;
            }
            var state = templates.get(targetId);
            if (state == null || state.lifecycle() != DependencyResolution.Lifecycle.ACTIVE) {
                return;
            }
            var targetPointer = pointer + "/templateRef/templateId";
            var child = childDesign(state, targetPointer);
            if (child == null) {
                return;
            }
            validateSelector(node, pointer, scope, state.staticSchema());
            var fills = arrayMember(node, "fills");
            if (fills == null) {
                return;
            }
            for (int index = 0; index < fills.items().size(); index++) {
                if (!(fills.items().get(index) instanceof JsonValue.ObjectValue fill)) {
                    continue;
                }
                var fillPointer = pointer + "/fills/" + index;
                var targetDefinitionId = text(fill, "targetDefinitionId");
                var target = child.definitions().get(targetDefinitionId);
                if (target == null) {
                    dependency("TEMPLATE_USE_FILL_TARGET_MISSING",
                            fillPointer + "/targetDefinitionId");
                    continue;
                }
                if (!"custom".equals(target.kind()) || !target.publicCustom()) {
                    dependency("TEMPLATE_USE_FILL_TARGET_NOT_PUBLIC",
                            fillPointer + "/targetDefinitionId");
                    continue;
                }
                var source = objectMember(fill, "source");
                if (source == null) {
                    continue;
                }
                var actual = resolveSource(source, fillPointer + "/source", scope, true);
                if (actual.isValue() && !target.valueType().equals(actual.valueType())) {
                    dependency("TEMPLATE_USE_FILL_TYPE_MISMATCH", fillPointer + "/source");
                }
            }
        }

        private void validateSelector(
                JsonValue.ObjectValue node,
                String pointer,
                Scope scope,
                StaticSchemaRef childSchema
        ) {
            var selector = objectMember(node, "contextSelector");
            if (selector == null) {
                return;
            }
            var kind = text(selector, "kind");
            if ("empty".equals(kind)) {
                if (!SYSTEM_EMPTY.equals(childSchema)) {
                    dependency("TEMPLATE_USE_EMPTY_CONTEXT_SCHEMA_MISMATCH",
                            pointer + "/contextSelector");
                }
                return;
            }
            if (!"context".equals(kind)) {
                return;
            }
            var domain = objectMember(selector, "domain");
            var context = selectorContext(domain, pointer + "/contextSelector/domain", scope);
            if (context == null || context.reference() == null) {
                return;
            }
            var path = text(selector, "pointer");
            if (path == null || path.isEmpty()) {
                if (!childSchema.equals(context.reference())) {
                    dependency("TEMPLATE_USE_CONTEXT_SCHEMA_MISMATCH",
                            pointer + "/contextSelector/pointer");
                }
                return;
            }
            var selected = resolvePath(
                    context.reference(), path, pointer + "/contextSelector/pointer");
            if (selected.kind() != TypeKind.REFERENCE
                    || !childSchema.equals(selected.reference())) {
                if (selected.kind() != TypeKind.UNKNOWN) {
                    dependency("TEMPLATE_USE_CONTEXT_SCHEMA_MISMATCH",
                            pointer + "/contextSelector/pointer");
                }
            }
        }

        private ContextProof selectorContext(
                JsonValue.ObjectValue domain,
                String pointer,
                Scope scope
        ) {
            if (domain == null) {
                return null;
            }
            var kind = text(domain, "kind");
            if ("invocation".equals(kind)) {
                return ContextProof.known(rootSchema);
            }
            if ("loop".equals(kind)) {
                var loopId = text(domain, "loopId");
                var proof = scope.loops().get(loopId);
                if (proof == null) {
                    hard("TEMPLATE_LEXICAL_DOMAIN_INVALID", pointer + "/loopId");
                    return null;
                }
                return proof;
            }
            return null;
        }

        private ChildDesign childDesign(
                DependencyResolution.TemplateState state,
                String pointer
        ) {
            if (childDesigns.containsKey(state.templateId())) {
                return childDesigns.get(state.templateId());
            }
            if (invalidChildDesigns.contains(state.templateId())) {
                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", pointer);
                return null;
            }
            var bytes = state.canonicalDesignDsl().getBytes(StandardCharsets.UTF_8);
            var admission = designs.admit(bytes);
            if (!(admission instanceof DesignDslAuthority.Admitted admitted)
                    || !Arrays.equals(bytes, admitted.canonicalUtf8())
                    || !state.contentHash().equals(admitted.contentHash())) {
                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", pointer);
                invalidChildDesigns.add(state.templateId());
                return null;
            }
            final JsonValue parsed;
            try {
                parsed = parser.parse(bytes);
            } catch (DesignDslFailureException invariantFault) {
                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", pointer);
                invalidChildDesigns.add(state.templateId());
                return null;
            }
            if (!(parsed instanceof JsonValue.ObjectValue document)) {
                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", pointer);
                invalidChildDesigns.add(state.templateId());
                return null;
            }
            var childDefinitions = new LinkedHashMap<String, DefinitionInfo>();
            var entries = arrayMember(document, "definitions");
            if (entries != null) {
                for (int index = 0; index < entries.items().size(); index++) {
                    if (!(entries.items().get(index) instanceof JsonValue.ObjectValue definition)) {
                        continue;
                    }
                    var id = text(definition, "definitionId");
                    var kind = text(definition, "kind");
                    var type = "custom".equals(kind)
                            ? valueType(definition.members().get("valueType"))
                            : valueType(definition.members().get("output"));
                    if (id != null && kind != null && type != null) {
                        childDefinitions.put(id, new DefinitionInfo(
                                id, kind,
                                "custom".equals(kind)
                                        && "PUBLIC".equals(text(definition, "exposure")),
                                type, domainLoopId(definition.members().get("domain")),
                                definition, "/definitions/" + index));
                    }
                }
            }
            var child = new ChildDesign(Map.copyOf(childDefinitions));
            childDesigns.put(state.templateId(), child);
            return child;
        }

        private StaticType resolveSource(
                JsonValue.ObjectValue source,
                String pointer,
                Scope scope,
                boolean allowAbsent
        ) {
            return resolveSource(source, pointer, scope, allowAbsent, false);
        }

        private StaticType resolveSource(
                JsonValue.ObjectValue source,
                String pointer,
                Scope scope,
                boolean allowAbsent,
                boolean allowReferenceList
        ) {
            var kind = text(source, "kind");
            if (kind == null) {
                return StaticType.unknown();
            }
            var resolved = switch (kind) {
                case "literal" -> StaticType.value(
                        valueType(source.members().get("valueType")), false, false);
                case "loopIndex" -> {
                    var loopId = text(source, "loopId");
                    if (!scope.loops().containsKey(loopId)) {
                        hard("TEMPLATE_LEXICAL_DOMAIN_INVALID", pointer + "/loopId");
                        yield StaticType.unknown();
                    }
                    yield StaticType.value("decimal", false, false);
                }
                case "definition" -> resolveDefinitionSource(source, pointer, scope);
                case "capability" -> capabilityType(source);
                case "context" -> resolveContextSource(
                        source, pointer, scope, allowReferenceList);
                default -> StaticType.unknown();
            };
            if (!allowAbsent && resolved.isValue() && resolved.mayBeAbsent()) {
                dependency("TEMPLATE_VALUE_SOURCE_MAY_BE_ABSENT", pointer);
            }
            return resolved;
        }

        private String bindingTargetType(
                JsonValue.ObjectValue node,
                JsonValue.ObjectValue binding
        ) {
            var target = objectMember(binding, "targetPropertyRef");
            var nodeKind = text(node, "kind");
            if (target == null || nodeKind == null) {
                return null;
            }
            var root = text(target, "rootPropertyId");
            var selectors = arrayMember(target, "selectors");
            if (root == null || selectors == null) {
                return null;
            }
            String member = null;
            boolean indexed = false;
            for (var selectorValue : selectors.items()) {
                if (!(selectorValue instanceof JsonValue.ObjectValue selector)) {
                    return null;
                }
                var selectorKind = text(selector, "kind");
                if ("member".equals(selectorKind)) {
                    member = text(selector, "name");
                } else if ("index".equals(selectorKind)) {
                    indexed = true;
                }
            }
            var pattern = root + (indexed ? "[*]" : "")
                    + (member == null ? "" : "." + member);
            return BindingPolicyCatalog.valueType(nodeKind, pattern);
        }

        private StaticType resolveDefinitionSource(
                JsonValue.ObjectValue source,
                String pointer,
                Scope scope
        ) {
            var id = text(source, "definitionId");
            var definition = definitions.get(id);
            if (definition == null) {
                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", pointer + "/definitionId");
                return StaticType.unknown();
            }
            if (definition.domainLoopId() != null
                    && !scope.loops().containsKey(definition.domainLoopId())) {
                hard("TEMPLATE_LEXICAL_DOMAIN_INVALID", pointer + "/definitionId");
                return StaticType.unknown();
            }
            return StaticType.value(definition.valueType(), false, false);
        }

        private StaticType capabilityType(JsonValue.ObjectValue source) {
            var operation = text(source, "operation");
            return switch (operation == null ? "" : operation) {
                case "UTC_DATE" -> StaticType.value("date", false, false);
                case "UTC_TIME" -> StaticType.value("time", false, false);
                case "UNIFORM_DECIMAL_0_1" -> StaticType.value("decimal", false, false);
                default -> StaticType.unknown();
            };
        }

        private StaticType resolveContextSource(
                JsonValue.ObjectValue source,
                String pointer,
                Scope scope,
                boolean allowReferenceList
        ) {
            var context = valueSourceContext(
                    source.members().get("domain"), pointer + "/domain", scope);
            if (context == null || context.reference() == null) {
                return StaticType.unknown();
            }
            var path = text(source, "pointer");
            if (path == null) {
                return StaticType.unknown();
            }
            var selected = resolvePath(context.reference(), path, pointer + "/pointer");
            if (selected.kind() == TypeKind.REFERENCE
                    || (!allowReferenceList && selected.kind() == TypeKind.REFERENCE_LIST)
                    || selected.kind() == TypeKind.CONTEXT) {
                dependency("TEMPLATE_VALUE_SOURCE_TYPE_MISMATCH", pointer + "/pointer");
                return StaticType.unknown();
            }
            return selected;
        }

        private ContextProof valueSourceContext(
                JsonValue domain,
                String pointer,
                Scope scope
        ) {
            if (domain instanceof JsonValue.StringValue invocation
                    && "invocation".equals(invocation.value())) {
                return ContextProof.known(rootSchema);
            }
            if (domain instanceof JsonValue.ObjectValue loop
                    && "loop".equals(text(loop, "kind"))) {
                var loopId = text(loop, "loopId");
                var proof = scope.loops().get(loopId);
                if (proof == null) {
                    hard("TEMPLATE_LEXICAL_DOMAIN_INVALID", pointer + "/loopId");
                    return null;
                }
                return proof;
            }
            return null;
        }

        private StaticType resolvePath(
                StaticSchemaRef context,
                String path,
                String problemPointer
        ) {
            if (path.isEmpty()) {
                return StaticType.context(context);
            }
            var segments = path.substring(1).split("/", -1);
            var current = context;
            boolean mayBeAbsent = false;
            for (int index = 0; index < segments.length; index++) {
                var definition = schema(current, problemPointer);
                if (definition == null) {
                    return StaticType.unknown();
                }
                var fieldName = segments[index].replace("~1", "/").replace("~0", "~");
                var field = definition.fields().stream()
                        .filter(candidate -> candidate.fieldKey().value().equals(fieldName))
                        .findFirst()
                        .orElse(null);
                if (field == null) {
                    dependency("TEMPLATE_STATIC_SCHEMA_PATH_NOT_FOUND", problemPointer);
                    return StaticType.unknown();
                }
                mayBeAbsent |= !field.required();
                var value = field.value();
                var last = index == segments.length - 1;
                if (last) {
                    return staticType(value, mayBeAbsent, problemPointer);
                }
                if (value instanceof ReferenceValue reference
                        && reference.ref() instanceof StaticSchemaRef staticRef) {
                    current = staticRef;
                    continue;
                }
                if (value instanceof ReferenceValue reference
                        && reference.ref() instanceof SchemaRef) {
                    hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", problemPointer);
                    return StaticType.unknown();
                }
                dependency("TEMPLATE_STATIC_SCHEMA_PATH_NOT_FOUND", problemPointer);
                return StaticType.unknown();
            }
            return StaticType.unknown();
        }

        private StaticType staticType(
                ValueDescriptor value,
                boolean mayBeAbsent,
                String problemPointer
        ) {
            if (value instanceof ReferenceValue reference) {
                if (reference.ref() instanceof StaticSchemaRef staticRef) {
                    return StaticType.reference(staticRef, mayBeAbsent);
                }
                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", problemPointer);
                return StaticType.unknown();
            }
            if (value instanceof ArrayValue array) {
                if (array.items() instanceof ReferenceValue reference) {
                    if (reference.ref() instanceof StaticSchemaRef staticRef) {
                        return StaticType.referenceList(staticRef, mayBeAbsent);
                    }
                    hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", problemPointer);
                    return StaticType.unknown();
                }
                return StaticType.list(array.items().type(), mayBeAbsent);
            }
            return StaticType.value(value.type(), mayBeAbsent, true);
        }

        private SchemaDefinition schema(StaticSchemaRef reference, String pointer) {
            if (schemaDefinitions.containsKey(reference)) {
                return schemaDefinitions.get(reference);
            }
            var resolution = schemas.resolve(reference);
            if (resolution instanceof StaticSchemaAuthority.Unavailable) {
                throw new Unavailable();
            }
            if (resolution instanceof StaticSchemaAuthority.NotFound) {
                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", pointer);
                return null;
            }
            var resolved = (StaticSchemaAuthority.Resolved) resolution;
            if (!reference.equals(resolved.reference())) {
                hard("TEMPLATE_DEPENDENCY_INTEGRITY_MISMATCH", pointer);
                return null;
            }
            schemaDefinitions.put(reference, resolved.definition());
            return resolved.definition();
        }

        private void classifiedTypeMismatch(StaticType type, String code, String pointer) {
            if (type.dependencyDriven()) {
                dependency(code, pointer);
            } else {
                hard(code, pointer);
            }
        }

        private void dependency(String code, String pointer) {
            problem(code, TemplateApplication.ProblemCategory.DEPENDENCY, pointer);
        }

        private void hard(String code, String pointer) {
            hard = true;
            problem(code, TemplateApplication.ProblemCategory.HARD, pointer);
        }

        private void problem(
                String code,
                TemplateApplication.ProblemCategory category,
                String pointer
        ) {
            if (stopped) {
                return;
            }
            if (!problems.add(new TemplateApplication.ValidationProblem(
                    code,
                    category,
                    TemplateApplication.ProblemSeverity.ERROR,
                    pointer,
                    List.of()
            ))) {
                hard = true;
                stopped = true;
            }
        }

        private Validation result() {
            var report = problems.report();
            return new Validation(report.problems(), hard || report.truncated());
        }
    }

    private enum TypeKind {
        VALUE,
        LIST,
        REFERENCE,
        REFERENCE_LIST,
        CONTEXT,
        UNKNOWN
    }

    private record StaticType(
            TypeKind kind,
            String valueType,
            StaticSchemaRef reference,
            boolean mayBeAbsent,
            boolean dependencyDriven
    ) {
        static StaticType value(String type, boolean mayBeAbsent, boolean dependencyDriven) {
            if (type == null) {
                return unknown();
            }
            if (type.startsWith("list<")) {
                return new StaticType(TypeKind.LIST, type, null, mayBeAbsent, dependencyDriven);
            }
            return new StaticType(TypeKind.VALUE, type, null, mayBeAbsent, dependencyDriven);
        }

        static StaticType list(String itemType, boolean mayBeAbsent) {
            return new StaticType(
                    TypeKind.LIST, "list<" + itemType + ">", null,
                    mayBeAbsent, true);
        }

        static StaticType reference(StaticSchemaRef reference, boolean mayBeAbsent) {
            return new StaticType(
                    TypeKind.REFERENCE, null, reference, mayBeAbsent, true);
        }

        static StaticType referenceList(StaticSchemaRef reference, boolean mayBeAbsent) {
            return new StaticType(
                    TypeKind.REFERENCE_LIST, null, reference, mayBeAbsent, true);
        }

        static StaticType context(StaticSchemaRef reference) {
            return new StaticType(TypeKind.CONTEXT, null, reference, false, true);
        }

        static StaticType unknown() {
            return new StaticType(TypeKind.UNKNOWN, null, null, false, false);
        }

        boolean isValue() {
            return kind == TypeKind.VALUE || kind == TypeKind.LIST;
        }

        String scalarListItemType() {
            if (kind != TypeKind.LIST
                    || valueType == null
                    || !valueType.startsWith("list<")
                    || !valueType.endsWith(">")) {
                return null;
            }
            return valueType.substring("list<".length(), valueType.length() - 1);
        }
    }

    private record ContextProof(StaticSchemaRef reference) {
        static ContextProof known(StaticSchemaRef reference) {
            return new ContextProof(reference);
        }

        static ContextProof unknown() {
            return new ContextProof(null);
        }
    }

    private record Scope(
            StaticSchemaRef invocation,
            Map<String, ContextProof> loops
    ) {
        Scope {
            loops = Map.copyOf(loops);
        }

        static Scope root(StaticSchemaRef invocation) {
            return new Scope(invocation, Map.of());
        }

        Scope withLoop(String loopId, ContextProof proof) {
            var next = new LinkedHashMap<>(loops);
            next.put(loopId, proof);
            return new Scope(invocation, next);
        }
    }

    private record DefinitionInfo(
            String id,
            String kind,
            boolean publicCustom,
            String valueType,
            String domainLoopId,
            JsonValue.ObjectValue body,
            String pointer
    ) {
    }

    private record ChildDesign(Map<String, DefinitionInfo> definitions) {
    }

    private static JsonValue.ObjectValue objectMember(
            JsonValue.ObjectValue object,
            String name
    ) {
        return object.members().get(name) instanceof JsonValue.ObjectValue value ? value : null;
    }

    private static JsonValue.ArrayValue arrayMember(
            JsonValue.ObjectValue object,
            String name
    ) {
        return object.members().get(name) instanceof JsonValue.ArrayValue value ? value : null;
    }

    private static String text(JsonValue.ObjectValue object, String name) {
        return object.members().get(name) instanceof JsonValue.StringValue value
                ? value.value() : null;
    }

    private static String valueType(JsonValue value) {
        if (value instanceof JsonValue.StringValue scalar) {
            return scalar.value();
        }
        if (value instanceof JsonValue.ObjectValue derived
                && "list".equals(text(derived, "type"))) {
            var items = text(derived, "items");
            return items == null ? null : "list<" + items + ">";
        }
        return null;
    }

    private static String domainLoopId(JsonValue domain) {
        if (domain instanceof JsonValue.ObjectValue object
                && "loop".equals(text(object, "kind"))) {
            return text(object, "loopId");
        }
        return null;
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }
}
