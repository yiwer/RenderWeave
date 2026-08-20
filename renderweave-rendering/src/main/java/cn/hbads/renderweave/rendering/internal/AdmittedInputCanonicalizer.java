package cn.hbads.renderweave.rendering.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.TreeMap;

/**
 * canonicalTypedInput 与 admittedInputDigest（冻结票据 15 §151）：closed
 * {staticSchemaRef,rootDocument,customValues}——rootDocument 只含 Schema 声明且 PRESENT 的
 * typed semantic 字段；customValues 每项 {definitionId,value} 按 definitionId 排序；普通数组
 * 保持输入顺序。decimal/date/time/color/AssetRef 使用各自 canonical value wire，不重复自报
 * type；optional ABSENT 省略。
 */
final class AdmittedInputCanonicalizer {

    private AdmittedInputCanonicalizer() {
    }

    static String digest(AdmittedRenderInput input) {
        return RenderingDigests.admittedInputDigest(canonical(input));
    }

    static byte[] canonical(AdmittedRenderInput input) {
        var members = new TreeMap<String, String>();

        var customValues = new ArrayList<String>();
        input.customs().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    var item = new TreeMap<String, String>();
                    item.put("definitionId", CanonicalJson.string(entry.getKey()));
                    item.put("value", designValueWire(entry.getValue()));
                    customValues.add(CanonicalJson.object(item));
                });
        members.put("customValues", CanonicalJson.array(customValues));

        members.put("rootDocument", typedObjectWire(input.rootDocument()));

        var schemaMembers = new TreeMap<String, String>();
        schemaMembers.put("schemaKey",
                CanonicalJson.string(input.staticSchemaRef().schemaKey().value()));
        schemaMembers.put("versionTag",
                CanonicalJson.string(input.staticSchemaRef().versionTag().value()));
        members.put("staticSchemaRef", CanonicalJson.object(schemaMembers));

        return CanonicalJson.object(members).getBytes(StandardCharsets.UTF_8);
    }

    private static String typedObjectWire(TypedObject object) {
        var members = new TreeMap<String, String>();
        for (var field : object.fields().entrySet()) {
            if (field.getValue().isEmpty()) {
                continue;
            }
            members.put(field.getKey(), typedValueWire(field.getValue().get()));
        }
        return CanonicalJson.object(members);
    }

    private static String typedValueWire(TypedValue value) {
        if (value instanceof TypedValue.Text text) {
            return CanonicalJson.string(text.value());
        }
        if (value instanceof TypedValue.Decimal decimal) {
            return CanonicalJson.decimal(decimal.value());
        }
        if (value instanceof TypedValue.Bool bool) {
            return CanonicalJson.bool(bool.value());
        }
        if (value instanceof TypedValue.Date date) {
            return CanonicalJson.string(date.value());
        }
        if (value instanceof TypedValue.Time time) {
            return CanonicalJson.string(time.value());
        }
        if (value instanceof TypedValue.Nested nested) {
            return typedObjectWire(nested.object());
        }
        if (value instanceof TypedValue.Array array) {
            var items = new ArrayList<String>(array.items().size());
            for (var item : array.items()) {
                items.add(typedValueWire(item));
            }
            return CanonicalJson.array(items);
        }
        if (value instanceof TypedObject object) {
            return typedObjectWire(object);
        }
        throw new IllegalStateException("unknown TypedValue variant");
    }

    private static String designValueWire(DesignValue value) {
        if (value instanceof DesignValue.Text text) {
            return CanonicalJson.string(text.value());
        }
        if (value instanceof DesignValue.Decimal decimal) {
            return CanonicalJson.decimal(decimal.value());
        }
        if (value instanceof DesignValue.Bool bool) {
            return CanonicalJson.bool(bool.value());
        }
        if (value instanceof DesignValue.Date date) {
            return CanonicalJson.string(date.value());
        }
        if (value instanceof DesignValue.Time time) {
            return CanonicalJson.string(time.value());
        }
        if (value instanceof DesignValue.Color color) {
            return CanonicalJson.string(color.value());
        }
        if (value instanceof DesignValue.ImageRef ref) {
            var members = new TreeMap<String, String>();
            members.put("assetId", CanonicalJson.string(ref.assetId()));
            return CanonicalJson.object(members);
        }
        if (value instanceof DesignValue.FontRef ref) {
            var members = new TreeMap<String, String>();
            members.put("assetId", CanonicalJson.string(ref.assetId()));
            return CanonicalJson.object(members);
        }
        if (value instanceof DesignValue.ListValue list) {
            var items = new ArrayList<String>(list.items().size());
            for (var item : list.items()) {
                items.add(designValueWire(item));
            }
            return CanonicalJson.array(items);
        }
        throw new IllegalStateException("unknown DesignValue variant");
    }
}
