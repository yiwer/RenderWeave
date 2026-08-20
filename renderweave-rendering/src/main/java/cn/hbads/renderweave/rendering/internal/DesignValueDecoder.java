package cn.hbads.renderweave.rendering.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict-JSON value → typed {@link DesignValue} decoding against a declared DesignDSL
 * valueType（冻结票据 07/15 wire）。strict typing：无隐式转换；enum 目录缺失时 fail-closed。
 */
final class DesignValueDecoder {

    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern TIME = Pattern.compile("^\\d{2}:\\d{2}:\\d{2}$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9A-F]{8}$");
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );

    static final Set<String> BASE_VALUE_TYPES = Set.of(
            "text", "decimal", "boolean", "date", "time", "color", "imageRef", "fontRef"
    );
    static final Set<String> LIST_ITEM_TYPES = Set.of(
            "text", "decimal", "boolean", "date", "time", "imageRef", "fontRef"
    );

    /** Declared DesignDSL valueType：base、{@code list<T>} 或 {@code enum<catalogId>}。 */
    sealed interface DesignValueType permits BaseType, ListOf, EnumType {
    }

    record BaseType(String name) implements DesignValueType {
        BaseType {
            Objects.requireNonNull(name, "name");
            if (!BASE_VALUE_TYPES.contains(name)) {
                throw new IllegalArgumentException("unknown base valueType: " + name);
            }
        }
    }

    record ListOf(String itemType) implements DesignValueType {
        ListOf {
            Objects.requireNonNull(itemType, "itemType");
            if (!LIST_ITEM_TYPES.contains(itemType)) {
                throw new IllegalArgumentException("unknown list item type: " + itemType);
            }
        }
    }

    record EnumType(String catalogId) implements DesignValueType {
        EnumType {
            Objects.requireNonNull(catalogId, "catalogId");
        }
    }

    enum DecodeRejection {
        TYPE_MISMATCH,
        LEXICAL_INVALID,
        ENUM_CATALOG_UNAVAILABLE
    }

    sealed interface DecodeResult permits Decoded, DecodeRejected {
    }

    record Decoded(DesignValue value) implements DecodeResult {
    }

    record DecodeRejected(DecodeRejection reason, String pointer) implements DecodeResult {
    }

    private DesignValueDecoder() {
    }

    /** Parses a DesignDSL valueType node: base string or {@code {type,items,catalogId}} object. */
    static DesignValueType parseValueType(RenderJson node) {
        if (node instanceof RenderJson.StringValue string) {
            return new BaseType(string.value());
        }
        if (node instanceof RenderJson.ObjectValue object) {
            var type = object.members().get("type");
            if (type instanceof RenderJson.StringValue typeName) {
                if ("list".equals(typeName.value())) {
                    var items = object.members().get("items");
                    if (items instanceof RenderJson.StringValue itemType
                            && LIST_ITEM_TYPES.contains(itemType.value())) {
                        return new ListOf(itemType.value());
                    }
                }
                if ("enum".equals(typeName.value())) {
                    var catalogId = object.members().get("catalogId");
                    if (catalogId instanceof RenderJson.StringValue catalog
                            && !catalog.value().isBlank()) {
                        return new EnumType(catalog.value());
                    }
                }
            }
        }
        throw new IllegalArgumentException("unsupported valueType wire");
    }

    static DecodeResult decode(RenderJson value, DesignValueType type, String pointer) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(type, "type");
        if (type instanceof EnumType) {
            // 枚举目录尚未物化：fail-closed，不猜测取值域。
            return new DecodeRejected(DecodeRejection.ENUM_CATALOG_UNAVAILABLE, pointer);
        }
        if (type instanceof ListOf list) {
            if (!(value instanceof RenderJson.ArrayValue array)) {
                return new DecodeRejected(DecodeRejection.TYPE_MISMATCH, pointer);
            }
            var items = new ArrayList<DesignValue>(array.items().size());
            for (int index = 0; index < array.items().size(); index++) {
                var itemResult = decodeScalar(array.items().get(index),
                        list.itemType(), pointer + "/" + index);
                if (itemResult instanceof DecodeRejected rejected) {
                    return rejected;
                }
                items.add(((Decoded) itemResult).value());
            }
            return new Decoded(new DesignValue.ListValue(list.itemType(), items));
        }
        return decodeScalar(value, ((BaseType) type).name(), pointer);
    }

    private static DecodeResult decodeScalar(RenderJson value, String baseType, String pointer) {
        return switch (baseType) {
            case "text" -> value instanceof RenderJson.StringValue string
                    ? new Decoded(new DesignValue.Text(string.value()))
                    : new DecodeRejected(DecodeRejection.TYPE_MISMATCH, pointer);
            case "decimal" -> value instanceof RenderJson.NumberValue number
                    ? new Decoded(new DesignValue.Decimal(new BigDecimal(number.rawToken())))
                    : new DecodeRejected(DecodeRejection.TYPE_MISMATCH, pointer);
            case "boolean" -> value instanceof RenderJson.BooleanValue bool
                    ? new Decoded(new DesignValue.Bool(bool.value()))
                    : new DecodeRejected(DecodeRejection.TYPE_MISMATCH, pointer);
            case "date" -> lexicalString(value, DATE, pointer,
                    decoded -> new DesignValue.Date(decoded));
            case "time" -> lexicalString(value, TIME, pointer,
                    decoded -> new DesignValue.Time(decoded));
            case "color" -> lexicalString(value, COLOR, pointer,
                    decoded -> new DesignValue.Color(decoded));
            case "imageRef" -> assetRef(value, pointer, true);
            case "fontRef" -> assetRef(value, pointer, false);
            default -> new DecodeRejected(DecodeRejection.TYPE_MISMATCH, pointer);
        };
    }

    private interface ScalarFactory {
        DesignValue create(String decoded);
    }

    private static DecodeResult lexicalString(
            RenderJson value,
            Pattern lexical,
            String pointer,
            ScalarFactory factory
    ) {
        if (!(value instanceof RenderJson.StringValue string)) {
            return new DecodeRejected(DecodeRejection.TYPE_MISMATCH, pointer);
        }
        if (!lexical.matcher(string.value()).matches()) {
            return new DecodeRejected(DecodeRejection.LEXICAL_INVALID, pointer);
        }
        return new Decoded(factory.create(string.value()));
    }

    private static DecodeResult assetRef(RenderJson value, String pointer, boolean image) {
        if (!(value instanceof RenderJson.ObjectValue object)) {
            return new DecodeRejected(DecodeRejection.TYPE_MISMATCH, pointer);
        }
        if (object.members().size() != 1) {
            return new DecodeRejected(DecodeRejection.TYPE_MISMATCH, pointer);
        }
        var assetId = object.members().get("assetId");
        if (!(assetId instanceof RenderJson.StringValue id)
                || !UUID_V4.matcher(id.value()).matches()) {
            return new DecodeRejected(DecodeRejection.LEXICAL_INVALID, pointer + "/assetId");
        }
        return new Decoded(image
                ? new DesignValue.ImageRef(id.value())
                : new DesignValue.FontRef(id.value()));
    }
}
