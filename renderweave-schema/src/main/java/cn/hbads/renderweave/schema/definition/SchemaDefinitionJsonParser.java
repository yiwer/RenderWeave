package cn.hbads.renderweave.schema.definition;

import cn.hbads.renderweave.schema.identity.FieldKey;
import cn.hbads.renderweave.schema.identity.InvalidSchemaKeyException;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/** Strict, closed-world parser and semantic validator for RenderWeave DSL 1.0 snapshots. */
public final class SchemaDefinitionJsonParser {

    private static final int MAX_FIELDS = 256;
    private static final int MAX_TEXT_LENGTH = 65_536;
    private static final int MAX_ARRAY_ITEMS = 10_000;
    private static final int MAX_ENUM_VALUES = 256;
    private static final int MAX_PATTERN_LENGTH = 1_024;
    private static final int MAX_DECIMAL_PRECISION = 128;
    private static final int MIN_DECIMAL_SCALE = -64;
    private static final int MAX_DECIMAL_SCALE = 64;

    private static final Set<String> DEFINITION_MEMBERS = Set.of(
            "dslVersion", "displayName", "description", "fields"
    );
    private static final Set<String> FIELD_MEMBERS = Set.of(
            "fieldKey", "displayName", "description", "required", "value"
    );
    private static final Set<String> CONSTRAINED_VALUE_MEMBERS = Set.of("type", "constraints");
    private static final Set<String> REFERENCE_VALUE_MEMBERS = Set.of("type", "ref");
    private static final Set<String> ARRAY_VALUE_MEMBERS = Set.of("type", "constraints", "items");
    private static final Set<String> ALL_VALUE_MEMBERS = Set.of("type", "constraints", "ref", "items");
    private static final Set<String> REFERENCE_MEMBERS = Set.of("schemaKey", "versionTag");
    private static final Set<String> TEXT_CONSTRAINT_MEMBERS = Set.of(
            "minLength", "maxLength", "pattern", "enum", "const"
    );
    private static final Set<String> DECIMAL_CONSTRAINT_MEMBERS = Set.of(
            "min", "exclusiveMin", "max", "exclusiveMax", "multipleOf", "enum", "const"
    );
    private static final Set<String> ORDERED_CONSTRAINT_MEMBERS = Set.of(
            "min", "exclusiveMin", "max", "exclusiveMax", "enum", "const"
    );
    private static final Set<String> BOOLEAN_CONSTRAINT_MEMBERS = Set.of("const");
    private static final Set<String> ARRAY_CONSTRAINT_MEMBERS = Set.of(
            "minItems", "maxItems", "uniqueItems"
    );

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

    private static final ObjectMapper JSON = JsonMapper.builder(
                    JsonFactory.builder()
                            .streamReadConstraints(StreamReadConstraints.builder()
                                    .maxNumberLength(256)
                                    .build())
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();

    public SchemaDefinition parse(String json) {
        var root = parseJson(json);
        var problems = new ArrayList<SchemaProblem>();

        if (!root.isObject()) {
            throw invalid("DSL_MEMBER_TYPE_INVALID", "", "Definition root must be an object");
        }

        rejectUnknownMembers(root, DEFINITION_MEMBERS, "", problems);
        var dslVersion = requiredText(root, "dslVersion", "", problems);
        if (dslVersion != null && !SchemaDefinition.DSL_VERSION.equals(dslVersion)) {
            problems.add(problem(
                    "DSL_VERSION_UNSUPPORTED",
                    "/dslVersion",
                    "dslVersion must be " + SchemaDefinition.DSL_VERSION
            ));
        }

        var displayName = normalizedRequiredText(root, "displayName", "", 128, problems);
        var description = normalizedDescription(root, "description", "", problems);
        var fields = parseFields(root.get("fields"), problems);

        if (!problems.isEmpty()) {
            throw new InvalidSchemaDefinitionException(problems);
        }
        return new SchemaDefinition(dslVersion, displayName, description, fields);
    }

    private static JsonNode parseJson(String json) {
        if (json == null) {
            throw invalid("DSL_INVALID_JSON", "", "Definition JSON is required");
        }
        try {
            var root = JSON.readTree(json);
            if (root == null) {
                throw invalid("DSL_INVALID_JSON", "", "Definition JSON is required");
            }
            return root;
        } catch (JacksonException exception) {
            var message = exception.getMessage() == null ? "" : exception.getMessage();
            var normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("duplicate")) {
                throw invalid("DSL_DUPLICATE_MEMBER", "", "JSON object members must be unique");
            }
            if (normalized.contains("number") && normalized.contains("length")) {
                throw invalid("CONSTRAINT_VALUE_INVALID", "", "Decimal tokens must be at most 256 bytes");
            }
            throw invalid("DSL_INVALID_JSON", "", "Definition must be strict JSON");
        }
    }

    private static List<SchemaField> parseFields(JsonNode fieldsNode, List<SchemaProblem> problems) {
        if (fieldsNode == null) {
            problems.add(problem(
                    "DSL_REQUIRED_MEMBER_MISSING", "/fields", "Required member 'fields' is missing"
            ));
            return List.of();
        }
        if (!fieldsNode.isArray()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", "/fields", "fields must be an array"));
            return List.of();
        }
        if (fieldsNode.size() > MAX_FIELDS) {
            problems.add(problem("FIELD_LIMIT_EXCEEDED", "/fields", "A definition supports at most 256 fields"));
        }

        var fields = new ArrayList<SchemaField>();
        var seenKeys = new HashSet<String>();
        for (int index = 0; index < fieldsNode.size(); index++) {
            var field = parseField(fieldsNode.get(index), index, problems);
            if (field == null) {
                continue;
            }
            if (!seenKeys.add(field.fieldKey().value())) {
                problems.add(problem(
                        "FIELD_KEY_DUPLICATE",
                        "/fields/" + index + "/fieldKey",
                        "fieldKey must be unique within a definition"
                ));
                continue;
            }
            fields.add(field);
        }
        return fields;
    }

    private static SchemaField parseField(JsonNode node, int index, List<SchemaProblem> problems) {
        var base = "/fields/" + index;
        if (node == null || !node.isObject()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", base, "Each field must be an object"));
            return null;
        }

        rejectUnknownMembers(node, FIELD_MEMBERS, base, problems);
        var rawFieldKey = requiredText(node, "fieldKey", base, problems);
        FieldKey fieldKey = null;
        if (rawFieldKey != null) {
            try {
                fieldKey = FieldKey.of(rawFieldKey);
            } catch (IllegalArgumentException exception) {
                problems.add(problem("FIELD_KEY_INVALID", base + "/fieldKey", exception.getMessage()));
            }
        }

        var displayName = normalizedOptionalDisplayName(node, "displayName", base, problems);
        var description = normalizedDescription(node, "description", base, problems);
        var required = requiredBoolean(node, "required", base, problems);
        var value = parseValue(node.get("value"), base + "/value", problems, false);

        if (fieldKey == null || required == null || value == null) {
            return null;
        }
        return new SchemaField(fieldKey, displayName, description, required, value);
    }

    private static ValueDescriptor parseValue(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems,
            boolean arrayItem
    ) {
        if (node == null) {
            problems.add(problem(
                    "DSL_REQUIRED_MEMBER_MISSING", pointer, "Required value descriptor is missing"
            ));
            return null;
        }
        if (!node.isObject()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", pointer, "value descriptor must be an object"));
            return null;
        }

        var type = requiredText(node, "type", pointer, problems);
        if (type == null) {
            rejectUnknownMembers(node, ALL_VALUE_MEMBERS, pointer, problems);
            return null;
        }

        return switch (type) {
            case "text" -> {
                rejectUnknownMembers(node, CONSTRAINED_VALUE_MEMBERS, pointer, problems);
                yield new TextValue(parseTextConstraints(node.get("constraints"), pointer + "/constraints", problems));
            }
            case "decimal" -> {
                rejectUnknownMembers(node, CONSTRAINED_VALUE_MEMBERS, pointer, problems);
                yield new DecimalValue(parseDecimalConstraints(node.get("constraints"), pointer + "/constraints", problems));
            }
            case "date" -> {
                rejectUnknownMembers(node, CONSTRAINED_VALUE_MEMBERS, pointer, problems);
                yield new DateValue(parseDateConstraints(node.get("constraints"), pointer + "/constraints", problems));
            }
            case "time" -> {
                rejectUnknownMembers(node, CONSTRAINED_VALUE_MEMBERS, pointer, problems);
                yield new TimeValue(parseTimeConstraints(node.get("constraints"), pointer + "/constraints", problems));
            }
            case "boolean" -> {
                rejectUnknownMembers(node, CONSTRAINED_VALUE_MEMBERS, pointer, problems);
                yield new BooleanValue(parseBooleanConstraints(node.get("constraints"), pointer + "/constraints", problems));
            }
            case "reference" -> {
                rejectUnknownMembers(node, REFERENCE_VALUE_MEMBERS, pointer, problems);
                var reference = parseReference(node.get("ref"), pointer + "/ref", problems);
                yield reference == null ? null : new ReferenceValue(reference);
            }
            case "array" -> {
                rejectUnknownMembers(node, ARRAY_VALUE_MEMBERS, pointer, problems);
                if (arrayItem) {
                    problems.add(problem(
                            "ARRAY_NESTED_UNSUPPORTED",
                            pointer + "/type",
                            "Array items cannot themselves be arrays"
                    ));
                    yield null;
                }
                yield parseArrayValue(node, pointer, problems);
            }
            default -> {
                rejectUnknownMembers(node, ALL_VALUE_MEMBERS, pointer, problems);
                problems.add(problem(
                        "DSL_VALUE_TYPE_UNSUPPORTED",
                        pointer + "/type",
                        "value.type must be text, decimal, date, time, boolean, reference or array"
                ));
                yield null;
            }
        };
    }

    private static TextConstraints parseTextConstraints(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        var constraints = constraintsObject(node, pointer, problems);
        if (constraints == null) {
            return TextConstraints.none();
        }
        rejectUnknownMembers(constraints, TEXT_CONSTRAINT_MEMBERS, pointer, problems);

        var minLength = optionalBoundedInteger(
                constraints, "minLength", pointer, MAX_TEXT_LENGTH, problems
        );
        var maxLength = optionalBoundedInteger(
                constraints, "maxLength", pointer, MAX_TEXT_LENGTH, problems
        );
        if (minLength.isPresent() && maxLength.isPresent()
                && minLength.getAsInt() > maxLength.getAsInt()) {
            problems.add(problem(
                    "CONSTRAINT_RANGE_INVALID", pointer, "minLength must not exceed maxLength"
            ));
        }

        var parsedPattern = parsePattern(constraints, pointer, problems);
        var enumValues = optionalEnum(
                constraints, pointer, problems, SchemaDefinitionJsonParser::parseTextLiteral, Objects::equals
        );
        var constValue = optionalLiteral(
                constraints, "const", pointer, problems, SchemaDefinitionJsonParser::parseTextLiteral
        );
        rejectEnumConstConflict(constraints, pointer, problems);

        var result = new TextConstraints(
                minLength, maxLength, parsedPattern.source(), enumValues, constValue
        );
        for (int index = 0; index < enumValues.size(); index++) {
            validateTextLiteral(
                    enumValues.get(index), result, parsedPattern.compiled(), pointer + "/enum/" + index, problems
            );
        }
        constValue.ifPresent(value -> validateTextLiteral(
                value, result, parsedPattern.compiled(), pointer + "/const", problems
        ));
        return result;
    }

    private static DecimalConstraints parseDecimalConstraints(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        var constraints = constraintsObject(node, pointer, problems);
        if (constraints == null) {
            return DecimalConstraints.none();
        }
        rejectUnknownMembers(constraints, DECIMAL_CONSTRAINT_MEMBERS, pointer, problems);

        var min = optionalLiteral(constraints, "min", pointer, problems,
                SchemaDefinitionJsonParser::parseDecimalLiteral);
        var exclusiveMin = optionalLiteral(constraints, "exclusiveMin", pointer, problems,
                SchemaDefinitionJsonParser::parseDecimalLiteral);
        var max = optionalLiteral(constraints, "max", pointer, problems,
                SchemaDefinitionJsonParser::parseDecimalLiteral);
        var exclusiveMax = optionalLiteral(constraints, "exclusiveMax", pointer, problems,
                SchemaDefinitionJsonParser::parseDecimalLiteral);
        var multipleOf = optionalLiteral(constraints, "multipleOf", pointer, problems,
                SchemaDefinitionJsonParser::parseDecimalLiteral);
        if (multipleOf.isPresent() && multipleOf.orElseThrow().signum() <= 0) {
            problems.add(problem(
                    "CONSTRAINT_VALUE_INVALID", pointer + "/multipleOf", "multipleOf must be greater than zero"
            ));
            multipleOf = Optional.empty();
        }
        checkOrderedBounds(min, exclusiveMin, max, exclusiveMax, BigDecimal::compareTo, pointer, problems);

        var enumValues = optionalEnum(
                constraints,
                pointer,
                problems,
                SchemaDefinitionJsonParser::parseDecimalLiteral,
                (left, right) -> left.compareTo(right) == 0
        );
        var constValue = optionalLiteral(
                constraints, "const", pointer, problems, SchemaDefinitionJsonParser::parseDecimalLiteral
        );
        rejectEnumConstConflict(constraints, pointer, problems);

        var result = new DecimalConstraints(
                min, exclusiveMin, max, exclusiveMax, multipleOf, enumValues, constValue
        );
        for (int index = 0; index < enumValues.size(); index++) {
            validateDecimalLiteral(enumValues.get(index), result, pointer + "/enum/" + index, problems);
        }
        constValue.ifPresent(value -> validateDecimalLiteral(value, result, pointer + "/const", problems));
        return result;
    }

    private static DateConstraints parseDateConstraints(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        var constraints = constraintsObject(node, pointer, problems);
        if (constraints == null) {
            return DateConstraints.none();
        }
        rejectUnknownMembers(constraints, ORDERED_CONSTRAINT_MEMBERS, pointer, problems);

        var min = optionalLiteral(constraints, "min", pointer, problems,
                SchemaDefinitionJsonParser::parseDateLiteral);
        var exclusiveMin = optionalLiteral(constraints, "exclusiveMin", pointer, problems,
                SchemaDefinitionJsonParser::parseDateLiteral);
        var max = optionalLiteral(constraints, "max", pointer, problems,
                SchemaDefinitionJsonParser::parseDateLiteral);
        var exclusiveMax = optionalLiteral(constraints, "exclusiveMax", pointer, problems,
                SchemaDefinitionJsonParser::parseDateLiteral);
        checkOrderedBounds(min, exclusiveMin, max, exclusiveMax, LocalDate::compareTo, pointer, problems);

        var enumValues = optionalEnum(
                constraints, pointer, problems, SchemaDefinitionJsonParser::parseDateLiteral, Objects::equals
        );
        var constValue = optionalLiteral(
                constraints, "const", pointer, problems, SchemaDefinitionJsonParser::parseDateLiteral
        );
        rejectEnumConstConflict(constraints, pointer, problems);

        var result = new DateConstraints(min, exclusiveMin, max, exclusiveMax, enumValues, constValue);
        for (int index = 0; index < enumValues.size(); index++) {
            validateOrderedLiteral(
                    enumValues.get(index), min, exclusiveMin, max, exclusiveMax, LocalDate::compareTo,
                    pointer + "/enum/" + index, problems
            );
        }
        constValue.ifPresent(value -> validateOrderedLiteral(
                value, min, exclusiveMin, max, exclusiveMax, LocalDate::compareTo,
                pointer + "/const", problems
        ));
        return result;
    }

    private static TimeConstraints parseTimeConstraints(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        var constraints = constraintsObject(node, pointer, problems);
        if (constraints == null) {
            return TimeConstraints.none();
        }
        rejectUnknownMembers(constraints, ORDERED_CONSTRAINT_MEMBERS, pointer, problems);

        var min = optionalLiteral(constraints, "min", pointer, problems,
                SchemaDefinitionJsonParser::parseTimeLiteral);
        var exclusiveMin = optionalLiteral(constraints, "exclusiveMin", pointer, problems,
                SchemaDefinitionJsonParser::parseTimeLiteral);
        var max = optionalLiteral(constraints, "max", pointer, problems,
                SchemaDefinitionJsonParser::parseTimeLiteral);
        var exclusiveMax = optionalLiteral(constraints, "exclusiveMax", pointer, problems,
                SchemaDefinitionJsonParser::parseTimeLiteral);
        checkOrderedBounds(min, exclusiveMin, max, exclusiveMax, LocalTime::compareTo, pointer, problems);

        var enumValues = optionalEnum(
                constraints, pointer, problems, SchemaDefinitionJsonParser::parseTimeLiteral, Objects::equals
        );
        var constValue = optionalLiteral(
                constraints, "const", pointer, problems, SchemaDefinitionJsonParser::parseTimeLiteral
        );
        rejectEnumConstConflict(constraints, pointer, problems);

        var result = new TimeConstraints(min, exclusiveMin, max, exclusiveMax, enumValues, constValue);
        for (int index = 0; index < enumValues.size(); index++) {
            validateOrderedLiteral(
                    enumValues.get(index), min, exclusiveMin, max, exclusiveMax, LocalTime::compareTo,
                    pointer + "/enum/" + index, problems
            );
        }
        constValue.ifPresent(value -> validateOrderedLiteral(
                value, min, exclusiveMin, max, exclusiveMax, LocalTime::compareTo,
                pointer + "/const", problems
        ));
        return result;
    }

    private static BooleanConstraints parseBooleanConstraints(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        var constraints = constraintsObject(node, pointer, problems);
        if (constraints == null) {
            return BooleanConstraints.none();
        }
        rejectUnknownMembers(constraints, BOOLEAN_CONSTRAINT_MEMBERS, pointer, problems);
        return new BooleanConstraints(optionalLiteral(
                constraints, "const", pointer, problems, SchemaDefinitionJsonParser::parseBooleanLiteral
        ));
    }

    private static ArrayValue parseArrayValue(
            JsonNode valueNode,
            String pointer,
            List<SchemaProblem> problems
    ) {
        var constraints = parseArrayConstraints(valueNode.get("constraints"), pointer + "/constraints", problems);
        var items = parseValue(valueNode.get("items"), pointer + "/items", problems, true);
        if (items == null) {
            return null;
        }
        if (constraints.uniqueItems().isPresent() && items instanceof ReferenceValue) {
            problems.add(problem(
                    "ARRAY_UNIQUE_ITEMS_UNSUPPORTED",
                    pointer + "/constraints/uniqueItems",
                    "uniqueItems is not supported for reference/object arrays"
            ));
        }
        return new ArrayValue(constraints, items);
    }

    private static ArrayConstraints parseArrayConstraints(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        var constraints = constraintsObject(node, pointer, problems);
        if (constraints == null) {
            return ArrayConstraints.none();
        }
        rejectUnknownMembers(constraints, ARRAY_CONSTRAINT_MEMBERS, pointer, problems);
        var minItems = optionalBoundedInteger(
                constraints, "minItems", pointer, MAX_ARRAY_ITEMS, problems
        );
        var maxItems = optionalBoundedInteger(
                constraints, "maxItems", pointer, MAX_ARRAY_ITEMS, problems
        );
        var uniqueItems = optionalLiteral(
                constraints, "uniqueItems", pointer, problems, SchemaDefinitionJsonParser::parseBooleanLiteral
        );
        if (minItems.isPresent() && maxItems.isPresent() && minItems.getAsInt() > maxItems.getAsInt()) {
            problems.add(problem(
                    "CONSTRAINT_RANGE_INVALID", pointer, "minItems must not exceed maxItems"
            ));
        }
        return new ArrayConstraints(minItems, maxItems, uniqueItems);
    }

    private static SchemaReference parseReference(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        if (node == null) {
            problems.add(problem("DSL_REQUIRED_MEMBER_MISSING", pointer, "Required member 'ref' is missing"));
            return null;
        }
        if (!node.isObject()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", pointer, "ref must be an object"));
            return null;
        }
        rejectUnknownMembers(node, REFERENCE_MEMBERS, pointer, problems);
        var rawSchemaKey = requiredText(node, "schemaKey", pointer, problems);
        SchemaKey schemaKey = null;
        if (rawSchemaKey != null) {
            try {
                schemaKey = rawSchemaKey.startsWith("system-")
                        ? SchemaKey.systemProvided(rawSchemaKey)
                        : SchemaKey.userProvided(rawSchemaKey);
            } catch (InvalidSchemaKeyException exception) {
                problems.add(problem("REFERENCE_TARGET_INVALID", pointer + "/schemaKey", exception.getMessage()));
            }
        }
        if (schemaKey == null) {
            return null;
        }

        var versionNode = node.get("versionTag");
        if (versionNode == null) {
            return new SchemaRef(schemaKey);
        }
        if (!versionNode.isString()) {
            problems.add(problem(
                    "DSL_MEMBER_TYPE_INVALID", pointer + "/versionTag", "versionTag must be a string"
            ));
            return null;
        }
        try {
            return new StaticSchemaRef(schemaKey, VersionTag.of(versionNode.stringValue()));
        } catch (IllegalArgumentException exception) {
            problems.add(problem("REFERENCE_TARGET_INVALID", pointer + "/versionTag", exception.getMessage()));
            return null;
        }
    }

    private static JsonNode constraintsObject(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        if (node == null) {
            return null;
        }
        if (!node.isObject()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", pointer, "constraints must be an object"));
            return null;
        }
        if (node.isEmpty()) {
            problems.add(problem("DSL_EMPTY_CONSTRAINTS", pointer, "Empty constraints must be omitted"));
            return null;
        }
        return node;
    }

    private static ParsedPattern parsePattern(
            JsonNode constraints,
            String pointer,
            List<SchemaProblem> problems
    ) {
        var source = optionalRawText(constraints, "pattern", pointer, problems);
        if (source.isEmpty()) {
            return ParsedPattern.NONE;
        }
        var pattern = source.orElseThrow();
        if (pattern.codePointCount(0, pattern.length()) > MAX_PATTERN_LENGTH) {
            problems.add(problem(
                    "CONSTRAINT_VALUE_INVALID",
                    pointer + "/pattern",
                    "pattern must be at most 1024 Unicode code points"
            ));
            return ParsedPattern.NONE;
        }
        var result = UserRegexPolicy.inspect(pattern);
        if (!result.valid()) {
            problems.add(problem(result.code(), pointer + "/pattern", result.message()));
            return ParsedPattern.NONE;
        }
        return new ParsedPattern(Optional.of(pattern), result.pattern());
    }

    private static String parseTextLiteral(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        if (!node.isString()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", pointer, "text literal must be a string"));
            return null;
        }
        var value = node.stringValue();
        if (value.codePointCount(0, value.length()) > MAX_TEXT_LENGTH) {
            problems.add(problem(
                    "CONSTRAINT_VALUE_INVALID", pointer, "text literal must be at most 65536 Unicode code points"
            ));
            return null;
        }
        return value;
    }

    private static BigDecimal parseDecimalLiteral(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        if (!node.isNumber()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", pointer, "decimal literal must be a JSON number"));
            return null;
        }
        final BigDecimal value;
        try {
            value = node.decimalValue();
        } catch (ArithmeticException exception) {
            problems.add(problem("CONSTRAINT_VALUE_INVALID", pointer, "decimal literal is out of range"));
            return null;
        }
        var normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        if (normalized.precision() > MAX_DECIMAL_PRECISION
                || normalized.scale() < MIN_DECIMAL_SCALE
                || normalized.scale() > MAX_DECIMAL_SCALE) {
            problems.add(problem(
                    "CONSTRAINT_VALUE_INVALID",
                    pointer,
                    "decimal precision must be at most 128 and normalized scale must be between -64 and 64"
            ));
            return null;
        }
        return normalized;
    }

    private static LocalDate parseDateLiteral(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        if (!node.isString() || !node.stringValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            problems.add(problem(
                    "CONSTRAINT_VALUE_INVALID", pointer, "date literal must use exact YYYY-MM-DD syntax"
            ));
            return null;
        }
        try {
            var value = LocalDate.parse(node.stringValue(), DATE_FORMAT);
            if (value.getYear() < 1 || value.getYear() > 9_999) {
                throw new DateTimeException("year out of range");
            }
            return value;
        } catch (DateTimeException exception) {
            problems.add(problem(
                    "CONSTRAINT_VALUE_INVALID", pointer, "date literal must be a valid Gregorian date in years 0001-9999"
            ));
            return null;
        }
    }

    private static LocalTime parseTimeLiteral(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        if (!node.isString() || !node.stringValue().matches("[0-9]{2}:[0-9]{2}:[0-9]{2}")) {
            problems.add(problem(
                    "CONSTRAINT_VALUE_INVALID", pointer, "time literal must use exact HH:mm:ss syntax"
            ));
            return null;
        }
        try {
            return LocalTime.parse(node.stringValue(), TIME_FORMAT);
        } catch (DateTimeException exception) {
            problems.add(problem(
                    "CONSTRAINT_VALUE_INVALID", pointer, "time literal must be between 00:00:00 and 23:59:59"
            ));
            return null;
        }
    }

    private static Boolean parseBooleanLiteral(
            JsonNode node,
            String pointer,
            List<SchemaProblem> problems
    ) {
        if (!node.isBoolean()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", pointer, "boolean literal must be true or false"));
            return null;
        }
        return node.booleanValue();
    }

    private static void validateTextLiteral(
            String value,
            TextConstraints constraints,
            Pattern pattern,
            String pointer,
            List<SchemaProblem> problems
    ) {
        var length = value.codePointCount(0, value.length());
        var valid = constraints.minLength().isEmpty() || length >= constraints.minLength().getAsInt();
        valid &= constraints.maxLength().isEmpty() || length <= constraints.maxLength().getAsInt();
        valid &= pattern == null || pattern.matcher(value).find();
        if (!valid) {
            problems.add(problem(
                    "CONSTRAINT_LITERAL_VIOLATION", pointer, "literal must satisfy all other text constraints"
            ));
        }
    }

    private static void validateDecimalLiteral(
            BigDecimal value,
            DecimalConstraints constraints,
            String pointer,
            List<SchemaProblem> problems
    ) {
        var valid = orderedLiteralIsValid(
                value,
                constraints.min(),
                constraints.exclusiveMin(),
                constraints.max(),
                constraints.exclusiveMax(),
                BigDecimal::compareTo
        );
        if (valid && constraints.multipleOf().isPresent()) {
            valid = value.remainder(constraints.multipleOf().orElseThrow()).signum() == 0;
        }
        if (!valid) {
            problems.add(problem(
                    "CONSTRAINT_LITERAL_VIOLATION", pointer, "literal must satisfy all other decimal constraints"
            ));
        }
    }

    private static <T> void validateOrderedLiteral(
            T value,
            Optional<T> min,
            Optional<T> exclusiveMin,
            Optional<T> max,
            Optional<T> exclusiveMax,
            Comparator<T> comparator,
            String pointer,
            List<SchemaProblem> problems
    ) {
        if (!orderedLiteralIsValid(value, min, exclusiveMin, max, exclusiveMax, comparator)) {
            problems.add(problem(
                    "CONSTRAINT_LITERAL_VIOLATION", pointer, "literal must satisfy all other ordered constraints"
            ));
        }
    }

    private static <T> boolean orderedLiteralIsValid(
            T value,
            Optional<T> min,
            Optional<T> exclusiveMin,
            Optional<T> max,
            Optional<T> exclusiveMax,
            Comparator<T> comparator
    ) {
        if (min.isPresent() && comparator.compare(value, min.orElseThrow()) < 0) {
            return false;
        }
        if (exclusiveMin.isPresent() && comparator.compare(value, exclusiveMin.orElseThrow()) <= 0) {
            return false;
        }
        if (max.isPresent() && comparator.compare(value, max.orElseThrow()) > 0) {
            return false;
        }
        return exclusiveMax.isEmpty() || comparator.compare(value, exclusiveMax.orElseThrow()) < 0;
    }

    private static <T> void checkOrderedBounds(
            Optional<T> min,
            Optional<T> exclusiveMin,
            Optional<T> max,
            Optional<T> exclusiveMax,
            Comparator<T> comparator,
            String pointer,
            List<SchemaProblem> problems
    ) {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            problems.add(problem(
                    "CONSTRAINT_CONFLICT", pointer, "min and exclusiveMin are mutually exclusive"
            ));
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            problems.add(problem(
                    "CONSTRAINT_CONFLICT", pointer, "max and exclusiveMax are mutually exclusive"
            ));
        }

        var lower = min.isPresent() ? min : exclusiveMin;
        var upper = max.isPresent() ? max : exclusiveMax;
        if (lower.isEmpty() || upper.isEmpty()) {
            return;
        }
        var comparison = comparator.compare(lower.orElseThrow(), upper.orElseThrow());
        if (comparison > 0 || (comparison == 0 && (exclusiveMin.isPresent() || exclusiveMax.isPresent()))) {
            problems.add(problem(
                    "CONSTRAINT_RANGE_INVALID",
                    pointer,
                    "Constraint range must be non-empty; equal bounds require both bounds to be inclusive"
            ));
        }
    }

    private static void rejectEnumConstConflict(
            JsonNode constraints,
            String pointer,
            List<SchemaProblem> problems
    ) {
        if (constraints.get("enum") != null && constraints.get("const") != null) {
            problems.add(problem(
                    "CONSTRAINT_CONFLICT", pointer, "enum and const are mutually exclusive"
            ));
        }
    }

    private static <T> List<T> optionalEnum(
            JsonNode constraints,
            String pointer,
            List<SchemaProblem> problems,
            LiteralParser<T> parser,
            BiPredicate<T, T> equal
    ) {
        var node = constraints.get("enum");
        if (node == null) {
            return List.of();
        }
        var enumPointer = pointer + "/enum";
        if (!node.isArray()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", enumPointer, "enum must be an array"));
            return List.of();
        }
        if (node.isEmpty() || node.size() > MAX_ENUM_VALUES) {
            problems.add(problem(
                    "CONSTRAINT_ENUM_INVALID", enumPointer, "enum must contain between 1 and 256 values"
            ));
        }
        var values = new ArrayList<T>();
        for (int index = 0; index < node.size(); index++) {
            var value = parser.parse(node.get(index), enumPointer + "/" + index, problems);
            if (value == null) {
                continue;
            }
            var duplicate = values.stream().anyMatch(existing -> equal.test(existing, value));
            if (duplicate) {
                problems.add(problem(
                        "CONSTRAINT_ENUM_DUPLICATE",
                        enumPointer + "/" + index,
                        "enum values must be unique by typed equality"
                ));
                continue;
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static <T> Optional<T> optionalLiteral(
            JsonNode object,
            String member,
            String base,
            List<SchemaProblem> problems,
            LiteralParser<T> parser
    ) {
        var node = object.get(member);
        if (node == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(parser.parse(node, base + "/" + member, problems));
    }

    private static OptionalInt optionalBoundedInteger(
            JsonNode object,
            String member,
            String base,
            int maximum,
            List<SchemaProblem> problems
    ) {
        var node = object.get(member);
        if (node == null) {
            return OptionalInt.empty();
        }
        var pointer = base + "/" + member;
        if (!node.isIntegralNumber()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", pointer, member + " must be an integer"));
            return OptionalInt.empty();
        }
        var value = node.longValue();
        if (value < 0 || value > maximum) {
            problems.add(problem(
                    "CONSTRAINT_VALUE_INVALID",
                    pointer,
                    member + " must be between 0 and " + maximum
            ));
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) value);
    }

    private static String requiredText(
            JsonNode object,
            String member,
            String base,
            List<SchemaProblem> problems
    ) {
        var node = object.get(member);
        var pointer = base + "/" + member;
        if (node == null) {
            problems.add(problem(
                    "DSL_REQUIRED_MEMBER_MISSING", pointer, "Required member '" + member + "' is missing"
            ));
            return null;
        }
        if (!node.isString()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", pointer, member + " must be a string"));
            return null;
        }
        return node.stringValue();
    }

    private static Boolean requiredBoolean(
            JsonNode object,
            String member,
            String base,
            List<SchemaProblem> problems
    ) {
        var node = object.get(member);
        var pointer = base + "/" + member;
        if (node == null) {
            problems.add(problem(
                    "DSL_REQUIRED_MEMBER_MISSING", pointer, "Required member '" + member + "' is missing"
            ));
            return null;
        }
        if (!node.isBoolean()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", pointer, member + " must be a boolean"));
            return null;
        }
        return node.booleanValue();
    }

    private static String normalizedRequiredText(
            JsonNode object,
            String member,
            String base,
            int maxCodePoints,
            List<SchemaProblem> problems
    ) {
        var value = requiredText(object, member, base, problems);
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        var count = normalized.codePointCount(0, normalized.length());
        if (count < 1 || count > maxCodePoints) {
            problems.add(problem(
                    "METADATA_VALUE_INVALID",
                    base + "/" + member,
                    member + " must contain 1 to " + maxCodePoints + " Unicode code points after trimming"
            ));
            return null;
        }
        return normalized;
    }

    private static Optional<String> normalizedOptionalDisplayName(
            JsonNode object,
            String member,
            String base,
            List<SchemaProblem> problems
    ) {
        if (object.get(member) == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(normalizedRequiredText(object, member, base, 128, problems));
    }

    private static Optional<String> normalizedDescription(
            JsonNode object,
            String member,
            String base,
            List<SchemaProblem> problems
    ) {
        var node = object.get(member);
        if (node == null) {
            return Optional.empty();
        }
        var pointer = base + "/" + member;
        if (!node.isString()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", pointer, member + " must be a string"));
            return Optional.empty();
        }
        var normalized = node.stringValue().strip();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (normalized.codePointCount(0, normalized.length()) > 2_048) {
            problems.add(problem(
                    "METADATA_VALUE_INVALID",
                    pointer,
                    member + " must be at most 2048 Unicode code points after trimming"
            ));
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    private static Optional<String> optionalRawText(
            JsonNode object,
            String member,
            String base,
            List<SchemaProblem> problems
    ) {
        var node = object.get(member);
        if (node == null) {
            return Optional.empty();
        }
        var pointer = base + "/" + member;
        if (!node.isString()) {
            problems.add(problem("DSL_MEMBER_TYPE_INVALID", pointer, member + " must be a string"));
            return Optional.empty();
        }
        return Optional.of(node.stringValue());
    }

    private static void rejectUnknownMembers(
            JsonNode object,
            Set<String> allowed,
            String base,
            List<SchemaProblem> problems
    ) {
        for (var property : object.properties()) {
            if (!allowed.contains(property.getKey())) {
                problems.add(problem(
                        "DSL_UNKNOWN_MEMBER",
                        base + "/" + escapePointerSegment(property.getKey()),
                        "Unknown DSL member '" + property.getKey() + "'"
                ));
            }
        }
    }

    private static String escapePointerSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static InvalidSchemaDefinitionException invalid(String code, String pointer, String message) {
        return new InvalidSchemaDefinitionException(List.of(problem(code, pointer, message)));
    }

    private static SchemaProblem problem(String code, String pointer, String message) {
        return new SchemaProblem(code, pointer, message);
    }

    @FunctionalInterface
    private interface LiteralParser<T> {
        T parse(JsonNode node, String pointer, List<SchemaProblem> problems);
    }

    private record ParsedPattern(Optional<String> source, Pattern compiled) {
        private static final ParsedPattern NONE = new ParsedPattern(Optional.empty(), null);
    }
}
