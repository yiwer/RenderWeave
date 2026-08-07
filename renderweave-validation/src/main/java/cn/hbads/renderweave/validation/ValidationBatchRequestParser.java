package cn.hbads.renderweave.validation;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.InvalidSchemaKeyException;
import cn.hbads.renderweave.schema.identity.InvalidVersionTagException;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parses the raw HTTP JSON before databinding can discard duplicate names or decimal lexemes. */
public final class ValidationBatchRequestParser {

    public static final int MAX_DOCUMENTS = 20;
    public static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_BATCH_DOCUMENT_BYTES = 10 * 1024 * 1024;
    public static final int MAX_DOCUMENT_DEPTH = 32;
    public static final int MAX_ARRAY_ITEMS = 10_000;
    private static final int MAX_REQUEST_OVERHEAD_BYTES = 64 * 1024;

    private static final Set<String> REQUEST_MEMBERS = Set.of("target", "documents");
    private static final Set<String> DOCUMENT_MEMBERS = Set.of("document");
    private static final Set<String> DRAFT_TARGET_MEMBERS = Set.of("kind", "schemaKey");
    private static final Set<String> STATIC_TARGET_MEMBERS = Set.of("kind", "schemaKey", "versionTag");

    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_DOCUMENT_DEPTH + 16)
                    .maxStringLength(MAX_BATCH_DOCUMENT_BYTES)
                    .maxNameLength(MAX_DOCUMENT_BYTES)
                    .maxNumberLength(MAX_DOCUMENT_BYTES)
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public ParsedValidationBatch parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw malformed("VALIDATION_INVALID_JSON", "", "A non-empty strict JSON request body is required", null);
        }
        if (body.length > MAX_BATCH_DOCUMENT_BYTES + MAX_REQUEST_OVERHEAD_BYTES) {
            throw limit(
                    "VALIDATION_REQUEST_TOO_LARGE",
                    "",
                    args("actualBytes", body.length, "maximumBytes", MAX_BATCH_DOCUMENT_BYTES + MAX_REQUEST_OVERHEAD_BYTES),
                    "The validation request exceeds the transport safety limit"
            );
        }

        var root = parseStrict(body);
        var request = requireObject(root, "", "Validation request must be an object");
        rejectUnknownMembers(request, REQUEST_MEMBERS, "");

        var target = parseTarget(required(request, "target", ""));
        var documentsNode = required(request, "documents", "");
        if (!(documentsNode instanceof StrictJsonValue.ArrayValue documentsArray)) {
            throw envelope("VALIDATION_REQUEST_INVALID", "/documents", "documents must be an array");
        }
        if (documentsArray.items().isEmpty() || documentsArray.items().size() > MAX_DOCUMENTS) {
            throw new InvalidValidationRequestException(
                    InvalidValidationRequestException.Kind.INVALID_ENVELOPE,
                    "VALIDATION_DOCUMENT_COUNT_INVALID",
                    "/documents",
                    args("minimum", 1, "maximum", MAX_DOCUMENTS, "actual", documentsArray.items().size()),
                    "documents must contain between 1 and 20 entries"
            );
        }

        var documents = new ArrayList<StrictJsonValue>(documentsArray.items().size());
        long totalBytes = 0;
        for (int index = 0; index < documentsArray.items().size(); index++) {
            var entryPointer = "/documents/" + index;
            var entry = requireObject(
                    documentsArray.items().get(index),
                    entryPointer,
                    "Each documents entry must be an object"
            );
            rejectUnknownMembers(entry, DOCUMENT_MEMBERS, entryPointer);
            var document = required(entry, "document", entryPointer);
            var documentBytes = document.span().length();
            if (documentBytes > MAX_DOCUMENT_BYTES) {
                throw limit(
                        "VALIDATION_DOCUMENT_TOO_LARGE",
                        entryPointer + "/document",
                        args("documentIndex", index, "actualBytes", documentBytes,
                                "maximumBytes", MAX_DOCUMENT_BYTES),
                        "A RootDocument exceeds the 2 MiB limit"
                );
            }
            totalBytes += documentBytes;
            if (totalBytes > MAX_BATCH_DOCUMENT_BYTES) {
                throw limit(
                        "VALIDATION_BATCH_TOO_LARGE",
                        "/documents",
                        args("actualBytes", totalBytes, "maximumBytes", MAX_BATCH_DOCUMENT_BYTES),
                        "The aggregate RootDocument bytes exceed the 10 MiB limit"
                );
            }
            var depth = containerDepth(document);
            if (depth > MAX_DOCUMENT_DEPTH) {
                throw limit(
                        "VALIDATION_NESTING_DEPTH_EXCEEDED",
                        entryPointer + "/document",
                        args("documentIndex", index, "actualDepth", depth,
                                "maximumDepth", MAX_DOCUMENT_DEPTH),
                        "A RootDocument exceeds nesting depth 32"
                );
            }
            documents.add(document);
        }
        return new ParsedValidationBatch(target, documents);
    }

    private static StrictJsonValue parseStrict(byte[] body) {
        try (var parser = JSON.createParser(body)) {
            var token = parser.nextToken();
            if (token == null) {
                throw malformed("VALIDATION_INVALID_JSON", "", "A strict JSON value is required", null);
            }
            var value = readValue(parser, token, 0);
            if (parser.nextToken() != null) {
                throw malformed("VALIDATION_INVALID_JSON", "", "Trailing JSON values are not allowed", null);
            }
            return value;
        } catch (InvalidValidationRequestException exception) {
            throw exception;
        } catch (JacksonException exception) {
            var message = exception.getMessage() == null ? "" : exception.getMessage();
            var normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("duplicate")) {
                throw malformed(
                        "VALIDATION_DUPLICATE_MEMBER",
                        "",
                        "JSON object member names must be unique at every level",
                        exception
                );
            }
            if (normalized.contains("nesting depth")) {
                throw limit(
                        "VALIDATION_NESTING_DEPTH_EXCEEDED",
                        "",
                        args("maximumDepth", MAX_DOCUMENT_DEPTH),
                        "A RootDocument exceeds nesting depth 32",
                        exception
                );
            }
            throw malformed(
                    "VALIDATION_INVALID_JSON",
                    "",
                    "The request body must use strict JSON syntax",
                    exception
            );
        }
    }

    private static StrictJsonValue readValue(JsonParser parser, JsonToken token, int containerDepth)
            throws JacksonException {
        var start = parser.currentTokenLocation().getByteOffset();
        return switch (token) {
            case START_OBJECT -> readObject(parser, start, containerDepth + 1);
            case START_ARRAY -> readArray(parser, start, containerDepth + 1);
            case VALUE_STRING -> new StrictJsonValue.StringValue(
                    parser.getText(), span(start, parser.currentLocation().getByteOffset())
            );
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> new StrictJsonValue.NumberValue(
                    parser.getText(), span(start, parser.currentLocation().getByteOffset())
            );
            case VALUE_TRUE -> new StrictJsonValue.BooleanValue(
                    true, span(start, parser.currentLocation().getByteOffset())
            );
            case VALUE_FALSE -> new StrictJsonValue.BooleanValue(
                    false, span(start, parser.currentLocation().getByteOffset())
            );
            case VALUE_NULL -> new StrictJsonValue.NullValue(
                    span(start, parser.currentLocation().getByteOffset())
            );
            default -> throw malformed(
                    "VALIDATION_INVALID_JSON", "", "Expected a JSON value", null
            );
        };
    }

    private static StrictJsonValue.ObjectValue readObject(JsonParser parser, long start, int depth)
            throws JacksonException {
        if (depth > MAX_DOCUMENT_DEPTH + 4) {
            throw limit(
                    "VALIDATION_NESTING_DEPTH_EXCEEDED", "",
                    args("maximumDepth", MAX_DOCUMENT_DEPTH),
                    "A RootDocument exceeds nesting depth 32"
            );
        }
        var members = new LinkedHashMap<String, StrictJsonValue>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
                throw malformed("VALIDATION_INVALID_JSON", "", "Expected an object member name", null);
            }
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if (valueToken == null) {
                throw malformed("VALIDATION_INVALID_JSON", "", "Object member value is missing", null);
            }
            if (members.put(name, readValue(parser, valueToken, depth)) != null) {
                throw malformed(
                        "VALIDATION_DUPLICATE_MEMBER", "",
                        "JSON object member names must be unique at every level", null
                );
            }
        }
        return new StrictJsonValue.ObjectValue(
                members,
                span(start, parser.currentLocation().getByteOffset())
        );
    }

    private static StrictJsonValue.ArrayValue readArray(JsonParser parser, long start, int depth)
            throws JacksonException {
        if (depth > MAX_DOCUMENT_DEPTH + 4) {
            throw limit(
                    "VALIDATION_NESTING_DEPTH_EXCEEDED", "",
                    args("maximumDepth", MAX_DOCUMENT_DEPTH),
                    "A RootDocument exceeds nesting depth 32"
            );
        }
        var items = new ArrayList<StrictJsonValue>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null) {
                throw malformed("VALIDATION_INVALID_JSON", "", "JSON array is not closed", null);
            }
            if (items.size() == MAX_ARRAY_ITEMS) {
                throw limit(
                        "VALIDATION_ARRAY_LIMIT_EXCEEDED",
                        "",
                        args("maximumItems", MAX_ARRAY_ITEMS),
                        "A JSON array exceeds 10000 items"
                );
            }
            items.add(readValue(parser, token, depth));
        }
        return new StrictJsonValue.ArrayValue(
                items,
                span(start, parser.currentLocation().getByteOffset())
        );
    }

    private static ValidationTarget parseTarget(StrictJsonValue node) {
        var target = requireObject(node, "/target", "target must be an object");
        var kind = requiredString(target, "kind", "/target");
        return switch (kind) {
            case "draft" -> {
                rejectUnknownMembers(target, DRAFT_TARGET_MEMBERS, "/target");
                var rawSchemaKey = requiredString(target, "schemaKey", "/target");
                try {
                    yield new ValidationTarget.DraftTarget(SchemaKey.userProvided(rawSchemaKey));
                } catch (InvalidSchemaKeyException exception) {
                    throw envelope("VALIDATION_TARGET_INVALID", "/target/schemaKey", exception.getMessage());
                }
            }
            case "static" -> {
                rejectUnknownMembers(target, STATIC_TARGET_MEMBERS, "/target");
                var rawSchemaKey = requiredString(target, "schemaKey", "/target");
                var rawVersionTag = requiredString(target, "versionTag", "/target");
                try {
                    var key = rawSchemaKey.startsWith("system-")
                            ? SchemaKey.systemProvided(rawSchemaKey)
                            : SchemaKey.userProvided(rawSchemaKey);
                    yield new ValidationTarget.StaticTarget(
                            new StaticSchemaRef(key, VersionTag.of(rawVersionTag))
                    );
                } catch (InvalidSchemaKeyException | InvalidVersionTagException exception) {
                    var pointer = exception instanceof InvalidVersionTagException
                            ? "/target/versionTag"
                            : "/target/schemaKey";
                    throw envelope("VALIDATION_TARGET_INVALID", pointer, exception.getMessage());
                }
            }
            default -> throw envelope(
                    "VALIDATION_TARGET_INVALID", "/target/kind",
                    "target.kind must be draft or static"
            );
        };
    }

    private static StrictJsonValue.ObjectValue requireObject(
            StrictJsonValue value,
            String pointer,
            String message
    ) {
        if (!(value instanceof StrictJsonValue.ObjectValue object)) {
            throw envelope("VALIDATION_REQUEST_INVALID", pointer, message);
        }
        return object;
    }

    private static StrictJsonValue required(
            StrictJsonValue.ObjectValue object,
            String member,
            String base
    ) {
        var value = object.members().get(member);
        if (value == null) {
            throw envelope(
                    "VALIDATION_REQUEST_INVALID",
                    base + "/" + escapePointerSegment(member),
                    "Required member '" + member + "' is missing"
            );
        }
        return value;
    }

    private static String requiredString(
            StrictJsonValue.ObjectValue object,
            String member,
            String base
    ) {
        var value = required(object, member, base);
        if (!(value instanceof StrictJsonValue.StringValue string)) {
            throw envelope(
                    "VALIDATION_REQUEST_INVALID",
                    base + "/" + escapePointerSegment(member),
                    member + " must be a string"
            );
        }
        return string.value();
    }

    private static void rejectUnknownMembers(
            StrictJsonValue.ObjectValue object,
            Set<String> allowed,
            String base
    ) {
        for (var member : object.members().keySet()) {
            if (!allowed.contains(member)) {
                throw envelope(
                        "VALIDATION_REQUEST_MEMBER_UNKNOWN",
                        base + "/" + escapePointerSegment(member),
                        "The validation request contains an unknown member"
                );
            }
        }
    }

    private static int containerDepth(StrictJsonValue value) {
        if (value instanceof StrictJsonValue.ObjectValue object) {
            var nested = object.members().values().stream()
                    .mapToInt(ValidationBatchRequestParser::containerDepth)
                    .max()
                    .orElse(0);
            return 1 + nested;
        }
        if (value instanceof StrictJsonValue.ArrayValue array) {
            var nested = array.items().stream()
                    .mapToInt(ValidationBatchRequestParser::containerDepth)
                    .max()
                    .orElse(0);
            return 1 + nested;
        }
        return 0;
    }

    private static StrictJsonValue.SourceSpan span(long start, long end) {
        return new StrictJsonValue.SourceSpan(start, end);
    }

    private static String escapePointerSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static InvalidValidationRequestException malformed(
            String code,
            String pointer,
            String message,
            Throwable cause
    ) {
        return new InvalidValidationRequestException(
                InvalidValidationRequestException.Kind.MALFORMED,
                code,
                pointer,
                Map.of(),
                message,
                cause
        );
    }

    private static InvalidValidationRequestException envelope(
            String code,
            String pointer,
            String message
    ) {
        return new InvalidValidationRequestException(
                InvalidValidationRequestException.Kind.INVALID_ENVELOPE,
                code,
                pointer,
                Map.of(),
                message
        );
    }

    private static InvalidValidationRequestException limit(
            String code,
            String pointer,
            Map<String, Object> args,
            String message
    ) {
        return limit(code, pointer, args, message, null);
    }

    private static InvalidValidationRequestException limit(
            String code,
            String pointer,
            Map<String, Object> args,
            String message,
            Throwable cause
    ) {
        return new InvalidValidationRequestException(
                InvalidValidationRequestException.Kind.LIMIT_EXCEEDED,
                code,
                pointer,
                args,
                message,
                cause
        );
    }

    private static Map<String, Object> args(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }
}
