package cn.hbads.renderweave.validation;

import cn.hbads.renderweave.schema.definition.ArrayValue;
import cn.hbads.renderweave.schema.definition.BooleanValue;
import cn.hbads.renderweave.schema.definition.DateValue;
import cn.hbads.renderweave.schema.definition.DecimalValue;
import cn.hbads.renderweave.schema.definition.ReferenceValue;
import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.definition.TextValue;
import cn.hbads.renderweave.schema.definition.TimeValue;
import cn.hbads.renderweave.schema.definition.ValueDescriptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Authoritative interpreter for RenderWeave DSL 1.0 RootDocument values. */
public final class RootDocumentValidator {

    public static final int MAX_PROBLEMS = 100;
    public static final int MAX_TEXT_CODE_POINTS = 65_536;
    public static final int MAX_DECIMAL_TOKEN_BYTES = 256;
    private static final int MAX_DECIMAL_PRECISION = 128;
    private static final int MIN_DECIMAL_SCALE = -64;
    private static final int MAX_DECIMAL_SCALE = 64;

    private static final DateTimeFormatter DATE_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd")
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME_FORMAT = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    public DocumentValidationResult validate(
            int index,
            StrictJsonValue document,
            ResolvedValidationTarget target
    ) {
        var collector = new ProblemCollector();
        try {
            var root = target.rootSchema();
            if (!(document instanceof StrictJsonValue.ObjectValue object)) {
                collector.add(
                        "ROOT_TYPE_UNSUPPORTED",
                        "",
                        schemaPrefix(root.identity()),
                        args("expected", "object", "actual", document.kind())
                );
            } else {
                validateObject(object, root, "", target, collector);
            }
        } catch (ProblemLimitReached ignored) {
            // The collector deliberately stops at the deterministic per-document cap.
        }
        return new DocumentValidationResult(
                index,
                collector.problems().isEmpty(),
                collector.problems(),
                collector.truncated()
        );
    }

    private static void validateObject(
            StrictJsonValue.ObjectValue object,
            ResolvedSchema schema,
            String instancePath,
            ResolvedValidationTarget target,
            ProblemCollector collector
    ) {
        var definition = schema.definition();
        var schemaBase = schemaPrefix(schema.identity());
        for (int fieldIndex = 0; fieldIndex < definition.fields().size(); fieldIndex++) {
            var field = definition.fields().get(fieldIndex);
            var key = field.fieldKey().value();
            var fieldInstancePath = instancePath + "/" + field.fieldKey().jsonPointerSegment();
            var fieldSchemaPath = schemaBase + "/fields/" + fieldIndex;
            if (!object.members().containsKey(key)) {
                if (field.required()) {
                    collector.add(
                            "REQUIRED_FIELD_MISSING",
                            fieldInstancePath,
                            fieldSchemaPath + "/required",
                            args("fieldKey", key)
                    );
                }
                continue;
            }
            validateValue(
                    object.members().get(key),
                    field.value(),
                    fieldInstancePath,
                    fieldSchemaPath + "/value",
                    target,
                    collector
            );
        }
    }

    private static void validateValue(
            StrictJsonValue value,
            ValueDescriptor descriptor,
            String instancePath,
            String schemaPath,
            ResolvedValidationTarget target,
            ProblemCollector collector
    ) {
        if (value instanceof StrictJsonValue.NullValue) {
            collector.add(
                    "NULL_VALUE_UNSUPPORTED",
                    instancePath,
                    schemaPath + "/type",
                    args("expected", expectedJsonKind(descriptor))
            );
            return;
        }
        if (descriptor instanceof TextValue text) {
            validateText(value, text, instancePath, schemaPath, collector);
        } else if (descriptor instanceof DecimalValue decimal) {
            validateDecimal(value, decimal, instancePath, schemaPath, collector);
        } else if (descriptor instanceof DateValue date) {
            validateDate(value, date, instancePath, schemaPath, collector);
        } else if (descriptor instanceof TimeValue time) {
            validateTime(value, time, instancePath, schemaPath, collector);
        } else if (descriptor instanceof BooleanValue bool) {
            validateBoolean(value, bool, instancePath, schemaPath, collector);
        } else if (descriptor instanceof ReferenceValue reference) {
            validateReference(value, reference, instancePath, schemaPath, target, collector);
        } else if (descriptor instanceof ArrayValue array) {
            validateArray(value, array, instancePath, schemaPath, target, collector);
        } else {
            throw new IllegalStateException("Unsupported persisted value descriptor " + descriptor.type());
        }
    }

    private static void validateText(
            StrictJsonValue value,
            TextValue descriptor,
            String instancePath,
            String schemaPath,
            ProblemCollector collector
    ) {
        if (!(value instanceof StrictJsonValue.StringValue text)) {
            typeMismatch(value, "string", instancePath, schemaPath, collector);
            return;
        }
        var constraints = descriptor.constraints();
        var length = text.value().codePointCount(0, text.value().length());
        if (length > MAX_TEXT_CODE_POINTS) {
            collector.add(
                    "TEXT_CODE_POINT_LIMIT_EXCEEDED",
                    instancePath,
                    schemaPath + "/type",
                    args("actual", length, "maximum", MAX_TEXT_CODE_POINTS)
            );
            return;
        }
        if (constraints.minLength().isPresent() && length < constraints.minLength().getAsInt()) {
            collector.add(
                    "TEXT_MIN_LENGTH_VIOLATED",
                    instancePath,
                    schemaPath + "/constraints/minLength",
                    args("actual", length, "minimum", constraints.minLength().getAsInt())
            );
        }
        if (constraints.maxLength().isPresent() && length > constraints.maxLength().getAsInt()) {
            collector.add(
                    "TEXT_MAX_LENGTH_VIOLATED",
                    instancePath,
                    schemaPath + "/constraints/maxLength",
                    args("actual", length, "maximum", constraints.maxLength().getAsInt())
            );
        }
        constraints.pattern().ifPresent(pattern -> {
            if (!Pattern.compile(pattern).matcher(text.value()).find()) {
                collector.add(
                        "TEXT_PATTERN_VIOLATED",
                        instancePath,
                        schemaPath + "/constraints/pattern",
                        args("pattern", pattern)
                );
            }
        });
        if (!constraints.enumValues().isEmpty() && !constraints.enumValues().contains(text.value())) {
            collector.add(
                    "TEXT_ENUM_VIOLATED",
                    instancePath,
                    schemaPath + "/constraints/enum",
                    args("allowedValues", constraints.enumValues())
            );
        }
        constraints.constValue().ifPresent(expected -> {
            if (!expected.equals(text.value())) {
                collector.add(
                        "TEXT_CONST_VIOLATED",
                        instancePath,
                        schemaPath + "/constraints/const",
                        args("expected", expected)
                );
            }
        });
    }

    private static void validateDecimal(
            StrictJsonValue value,
            DecimalValue descriptor,
            String instancePath,
            String schemaPath,
            ProblemCollector collector
    ) {
        if (!(value instanceof StrictJsonValue.NumberValue number)) {
            typeMismatch(value, "number", instancePath, schemaPath, collector);
            return;
        }
        var parsed = parseDecimal(number, instancePath, schemaPath, collector);
        if (parsed.isEmpty()) {
            return;
        }
        var decimal = parsed.orElseThrow();
        var constraints = descriptor.constraints();
        constraints.min().ifPresent(min -> {
            if (decimal.compareTo(min) < 0) {
                collector.add(
                        "DECIMAL_MIN_VIOLATED", instancePath, schemaPath + "/constraints/min",
                        args("actual", decimal.toPlainString(), "minimum", min.toPlainString())
                );
            }
        });
        constraints.exclusiveMin().ifPresent(min -> {
            if (decimal.compareTo(min) <= 0) {
                collector.add(
                        "DECIMAL_EXCLUSIVE_MIN_VIOLATED",
                        instancePath,
                        schemaPath + "/constraints/exclusiveMin",
                        args("actual", decimal.toPlainString(), "exclusiveMinimum", min.toPlainString())
                );
            }
        });
        constraints.max().ifPresent(max -> {
            if (decimal.compareTo(max) > 0) {
                collector.add(
                        "DECIMAL_MAX_VIOLATED", instancePath, schemaPath + "/constraints/max",
                        args("actual", decimal.toPlainString(), "maximum", max.toPlainString())
                );
            }
        });
        constraints.exclusiveMax().ifPresent(max -> {
            if (decimal.compareTo(max) >= 0) {
                collector.add(
                        "DECIMAL_EXCLUSIVE_MAX_VIOLATED",
                        instancePath,
                        schemaPath + "/constraints/exclusiveMax",
                        args("actual", decimal.toPlainString(), "exclusiveMaximum", max.toPlainString())
                );
            }
        });
        constraints.multipleOf().ifPresent(multiple -> {
            if (decimal.remainder(multiple).signum() != 0) {
                collector.add(
                        "DECIMAL_MULTIPLE_OF_VIOLATED",
                        instancePath,
                        schemaPath + "/constraints/multipleOf",
                        args("actual", decimal.toPlainString(), "multipleOf", multiple.toPlainString())
                );
            }
        });
        if (!constraints.enumValues().isEmpty()
                && constraints.enumValues().stream().noneMatch(candidate -> candidate.compareTo(decimal) == 0)) {
            collector.add(
                    "DECIMAL_ENUM_VIOLATED",
                    instancePath,
                    schemaPath + "/constraints/enum",
                    args("allowedValues", constraints.enumValues().stream().map(BigDecimal::toPlainString).toList())
            );
        }
        constraints.constValue().ifPresent(expected -> {
            if (expected.compareTo(decimal) != 0) {
                collector.add(
                        "DECIMAL_CONST_VIOLATED",
                        instancePath,
                        schemaPath + "/constraints/const",
                        args("expected", expected.toPlainString())
                );
            }
        });
    }

    private static Optional<BigDecimal> parseDecimal(
            StrictJsonValue.NumberValue number,
            String instancePath,
            String schemaPath,
            ProblemCollector collector
    ) {
        var tokenBytes = number.rawToken().getBytes(StandardCharsets.UTF_8).length;
        if (tokenBytes > MAX_DECIMAL_TOKEN_BYTES) {
            collector.add(
                    "DECIMAL_TOKEN_TOO_LONG",
                    instancePath,
                    schemaPath + "/type",
                    args("actualBytes", tokenBytes, "maximumBytes", MAX_DECIMAL_TOKEN_BYTES)
            );
            return Optional.empty();
        }
        final BigDecimal parsed;
        try {
            parsed = new BigDecimal(number.rawToken());
        } catch (NumberFormatException exception) {
            collector.add(
                    "DECIMAL_VALUE_INVALID",
                    instancePath,
                    schemaPath + "/type",
                    Map.of()
            );
            return Optional.empty();
        }
        var normalized = parsed.signum() == 0 ? BigDecimal.ZERO : parsed.stripTrailingZeros();
        var valid = true;
        if (normalized.precision() > MAX_DECIMAL_PRECISION) {
            collector.add(
                    "DECIMAL_PRECISION_EXCEEDED",
                    instancePath,
                    schemaPath + "/type",
                    args("actual", normalized.precision(), "maximum", MAX_DECIMAL_PRECISION)
            );
            valid = false;
        }
        if (normalized.scale() < MIN_DECIMAL_SCALE || normalized.scale() > MAX_DECIMAL_SCALE) {
            collector.add(
                    "DECIMAL_SCALE_OUT_OF_RANGE",
                    instancePath,
                    schemaPath + "/type",
                    args("actual", normalized.scale(), "minimum", MIN_DECIMAL_SCALE,
                            "maximum", MAX_DECIMAL_SCALE)
            );
            valid = false;
        }
        return valid ? Optional.of(normalized) : Optional.empty();
    }

    private static void validateDate(
            StrictJsonValue value,
            DateValue descriptor,
            String instancePath,
            String schemaPath,
            ProblemCollector collector
    ) {
        if (!(value instanceof StrictJsonValue.StringValue text)) {
            typeMismatch(value, "string", instancePath, schemaPath, collector);
            return;
        }
        var parsed = parseDate(text.value());
        if (parsed.isEmpty()) {
            collector.add(
                    "DATE_FORMAT_INVALID",
                    instancePath,
                    schemaPath + "/type",
                    args("format", "YYYY-MM-DD")
            );
            return;
        }
        var date = parsed.orElseThrow();
        var constraints = descriptor.constraints();
        constraints.min().ifPresent(min -> orderedMinimum(
                date, min, false, "DATE_MIN_VIOLATED", instancePath,
                schemaPath + "/constraints/min", collector
        ));
        constraints.exclusiveMin().ifPresent(min -> orderedMinimum(
                date, min, true, "DATE_EXCLUSIVE_MIN_VIOLATED", instancePath,
                schemaPath + "/constraints/exclusiveMin", collector
        ));
        constraints.max().ifPresent(max -> orderedMaximum(
                date, max, false, "DATE_MAX_VIOLATED", instancePath,
                schemaPath + "/constraints/max", collector
        ));
        constraints.exclusiveMax().ifPresent(max -> orderedMaximum(
                date, max, true, "DATE_EXCLUSIVE_MAX_VIOLATED", instancePath,
                schemaPath + "/constraints/exclusiveMax", collector
        ));
        if (!constraints.enumValues().isEmpty() && !constraints.enumValues().contains(date)) {
            collector.add(
                    "DATE_ENUM_VIOLATED", instancePath, schemaPath + "/constraints/enum",
                    args("allowedValues", constraints.enumValues().stream().map(LocalDate::toString).toList())
            );
        }
        constraints.constValue().ifPresent(expected -> {
            if (!expected.equals(date)) {
                collector.add(
                        "DATE_CONST_VIOLATED", instancePath, schemaPath + "/constraints/const",
                        args("expected", expected.toString())
                );
            }
        });
    }

    private static void validateTime(
            StrictJsonValue value,
            TimeValue descriptor,
            String instancePath,
            String schemaPath,
            ProblemCollector collector
    ) {
        if (!(value instanceof StrictJsonValue.StringValue text)) {
            typeMismatch(value, "string", instancePath, schemaPath, collector);
            return;
        }
        var parsed = parseTime(text.value());
        if (parsed.isEmpty()) {
            collector.add(
                    "TIME_FORMAT_INVALID",
                    instancePath,
                    schemaPath + "/type",
                    args("format", "HH:mm:ss")
            );
            return;
        }
        var time = parsed.orElseThrow();
        var constraints = descriptor.constraints();
        constraints.min().ifPresent(min -> orderedMinimum(
                time, min, false, "TIME_MIN_VIOLATED", instancePath,
                schemaPath + "/constraints/min", collector
        ));
        constraints.exclusiveMin().ifPresent(min -> orderedMinimum(
                time, min, true, "TIME_EXCLUSIVE_MIN_VIOLATED", instancePath,
                schemaPath + "/constraints/exclusiveMin", collector
        ));
        constraints.max().ifPresent(max -> orderedMaximum(
                time, max, false, "TIME_MAX_VIOLATED", instancePath,
                schemaPath + "/constraints/max", collector
        ));
        constraints.exclusiveMax().ifPresent(max -> orderedMaximum(
                time, max, true, "TIME_EXCLUSIVE_MAX_VIOLATED", instancePath,
                schemaPath + "/constraints/exclusiveMax", collector
        ));
        if (!constraints.enumValues().isEmpty() && !constraints.enumValues().contains(time)) {
            collector.add(
                    "TIME_ENUM_VIOLATED", instancePath, schemaPath + "/constraints/enum",
                    args("allowedValues", constraints.enumValues().stream()
                            .map(allowed -> allowed.format(TIME_FORMAT))
                            .toList())
            );
        }
        constraints.constValue().ifPresent(expected -> {
            if (!expected.equals(time)) {
                collector.add(
                        "TIME_CONST_VIOLATED", instancePath, schemaPath + "/constraints/const",
                        args("expected", expected.format(TIME_FORMAT))
                );
            }
        });
    }

    private static <T extends Comparable<T>> void orderedMinimum(
            T actual,
            T minimum,
            boolean exclusive,
            String code,
            String instancePath,
            String schemaPath,
            ProblemCollector collector
    ) {
        var comparison = actual.compareTo(minimum);
        if (comparison < 0 || (exclusive && comparison == 0)) {
            collector.add(
                    code, instancePath, schemaPath,
                    args("actual", orderedValue(actual),
                            exclusive ? "exclusiveMinimum" : "minimum", orderedValue(minimum))
            );
        }
    }

    private static <T extends Comparable<T>> void orderedMaximum(
            T actual,
            T maximum,
            boolean exclusive,
            String code,
            String instancePath,
            String schemaPath,
            ProblemCollector collector
    ) {
        var comparison = actual.compareTo(maximum);
        if (comparison > 0 || (exclusive && comparison == 0)) {
            collector.add(
                    code, instancePath, schemaPath,
                    args("actual", orderedValue(actual),
                            exclusive ? "exclusiveMaximum" : "maximum", orderedValue(maximum))
            );
        }
    }

    private static String orderedValue(Object value) {
        return value instanceof LocalTime time ? time.format(TIME_FORMAT) : value.toString();
    }

    private static void validateBoolean(
            StrictJsonValue value,
            BooleanValue descriptor,
            String instancePath,
            String schemaPath,
            ProblemCollector collector
    ) {
        if (!(value instanceof StrictJsonValue.BooleanValue bool)) {
            typeMismatch(value, "boolean", instancePath, schemaPath, collector);
            return;
        }
        descriptor.constraints().constValue().ifPresent(expected -> {
            if (expected != bool.value()) {
                collector.add(
                        "BOOLEAN_CONST_VIOLATED",
                        instancePath,
                        schemaPath + "/constraints/const",
                        args("expected", expected)
                );
            }
        });
    }

    private static void validateReference(
            StrictJsonValue value,
            ReferenceValue descriptor,
            String instancePath,
            String schemaPath,
            ResolvedValidationTarget target,
            ProblemCollector collector
    ) {
        if (!(value instanceof StrictJsonValue.ObjectValue object)) {
            typeMismatch(value, "object", instancePath, schemaPath, collector);
            return;
        }
        validateObject(object, target.resolve(descriptor.ref()), instancePath, target, collector);
    }

    private static void validateArray(
            StrictJsonValue value,
            ArrayValue descriptor,
            String instancePath,
            String schemaPath,
            ResolvedValidationTarget target,
            ProblemCollector collector
    ) {
        if (!(value instanceof StrictJsonValue.ArrayValue array)) {
            typeMismatch(value, "array", instancePath, schemaPath, collector);
            return;
        }
        var size = array.items().size();
        var constraints = descriptor.constraints();
        if (constraints.minItems().isPresent() && size < constraints.minItems().getAsInt()) {
            collector.add(
                    "ARRAY_MIN_ITEMS_VIOLATED",
                    instancePath,
                    schemaPath + "/constraints/minItems",
                    args("actual", size, "minimum", constraints.minItems().getAsInt())
            );
        }
        if (constraints.maxItems().isPresent() && size > constraints.maxItems().getAsInt()) {
            collector.add(
                    "ARRAY_MAX_ITEMS_VIOLATED",
                    instancePath,
                    schemaPath + "/constraints/maxItems",
                    args("actual", size, "maximum", constraints.maxItems().getAsInt())
            );
        }

        var seen = new LinkedHashMap<Object, Integer>();
        var unique = constraints.uniqueItems().orElse(false);
        for (int itemIndex = 0; itemIndex < array.items().size(); itemIndex++) {
            var currentIndex = itemIndex;
            var item = array.items().get(itemIndex);
            var itemPath = instancePath + "/" + itemIndex;
            validateValue(
                    item,
                    descriptor.items(),
                    itemPath,
                    schemaPath + "/items",
                    target,
                    collector
            );
            if (unique) {
                canonicalScalar(item, descriptor.items()).ifPresent(canonical -> {
                    var firstIndex = seen.putIfAbsent(canonical, currentIndex);
                    if (firstIndex != null) {
                        collector.add(
                                "ARRAY_UNIQUE_ITEMS_VIOLATED",
                                itemPath,
                                schemaPath + "/constraints/uniqueItems",
                                args("firstIndex", firstIndex, "duplicateIndex", currentIndex)
                        );
                    }
                });
            }
        }
    }

    private static Optional<Object> canonicalScalar(StrictJsonValue value, ValueDescriptor descriptor) {
        if (value instanceof StrictJsonValue.NullValue) {
            return Optional.empty();
        }
        if (descriptor instanceof TextValue && value instanceof StrictJsonValue.StringValue text) {
            return Optional.of(new CanonicalScalar("text", text.value()));
        }
        if (descriptor instanceof DecimalValue && value instanceof StrictJsonValue.NumberValue number) {
            try {
                var decimal = new BigDecimal(number.rawToken());
                var normalized = decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
                if (number.rawToken().length() <= MAX_DECIMAL_TOKEN_BYTES
                        && normalized.precision() <= MAX_DECIMAL_PRECISION
                        && normalized.scale() >= MIN_DECIMAL_SCALE
                        && normalized.scale() <= MAX_DECIMAL_SCALE) {
                    return Optional.of(new CanonicalScalar("decimal", normalized.toPlainString()));
                }
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
            return Optional.empty();
        }
        if (descriptor instanceof DateValue && value instanceof StrictJsonValue.StringValue text) {
            return parseDate(text.value()).map(date -> new CanonicalScalar("date", date.toString()));
        }
        if (descriptor instanceof TimeValue && value instanceof StrictJsonValue.StringValue text) {
            return parseTime(text.value()).map(time -> new CanonicalScalar("time", time.format(TIME_FORMAT)));
        }
        if (descriptor instanceof BooleanValue && value instanceof StrictJsonValue.BooleanValue bool) {
            return Optional.of(new CanonicalScalar("boolean", Boolean.toString(bool.value())));
        }
        return Optional.empty();
    }

    private static Optional<LocalDate> parseDate(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return Optional.empty();
        }
        try {
            var parsed = LocalDate.parse(value, DATE_FORMAT);
            return parsed.getYear() >= 1 && parsed.getYear() <= 9_999
                    ? Optional.of(parsed)
                    : Optional.empty();
        } catch (DateTimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<LocalTime> parseTime(String value) {
        if (!value.matches("[0-9]{2}:[0-9]{2}:[0-9]{2}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalTime.parse(value, TIME_FORMAT));
        } catch (DateTimeException exception) {
            return Optional.empty();
        }
    }

    private static void typeMismatch(
            StrictJsonValue actual,
            String expected,
            String instancePath,
            String schemaPath,
            ProblemCollector collector
    ) {
        collector.add(
                "VALUE_TYPE_MISMATCH",
                instancePath,
                schemaPath + "/type",
                args("expected", expected, "actual", actual.kind())
        );
    }

    private static String expectedJsonKind(ValueDescriptor descriptor) {
        if (descriptor instanceof TextValue || descriptor instanceof DateValue || descriptor instanceof TimeValue) {
            return "string";
        }
        if (descriptor instanceof DecimalValue) {
            return "number";
        }
        if (descriptor instanceof BooleanValue) {
            return "boolean";
        }
        if (descriptor instanceof ReferenceValue) {
            return "object";
        }
        if (descriptor instanceof ArrayValue) {
            return "array";
        }
        throw new IllegalStateException("Unsupported persisted value descriptor " + descriptor.type());
    }

    private static String schemaPrefix(ResolvedSchemaIdentity identity) {
        if (identity instanceof ResolvedSchemaIdentity.DraftIdentity draft) {
            return "/schemas/draft/" + escapePointerSegment(draft.schemaKey().value())
                    + "/" + draft.revision() + "/definition";
        }
        var exact = ((ResolvedSchemaIdentity.StaticIdentity) identity).reference();
        return "/schemas/static/" + escapePointerSegment(exact.schemaKey().value())
                + "/" + escapePointerSegment(exact.versionTag().value()) + "/definition";
    }

    private static String escapePointerSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static Map<String, Object> args(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    private record CanonicalScalar(String type, String value) {
    }

    private static final class ProblemCollector {
        private final ArrayList<ValidationProblem> problems = new ArrayList<>();
        private boolean truncated;

        void add(String code, String instancePath, String schemaPath, Map<String, Object> messageArgs) {
            problems.add(new ValidationProblem(code, instancePath, schemaPath, messageArgs));
            if (problems.size() == MAX_PROBLEMS) {
                truncated = true;
                throw ProblemLimitReached.INSTANCE;
            }
        }

        List<ValidationProblem> problems() {
            return List.copyOf(problems);
        }

        boolean truncated() {
            return truncated;
        }
    }

    private static final class ProblemLimitReached extends RuntimeException {
        private static final ProblemLimitReached INSTANCE = new ProblemLimitReached();

        private ProblemLimitReached() {
            super(null, null, false, false);
        }
    }
}
