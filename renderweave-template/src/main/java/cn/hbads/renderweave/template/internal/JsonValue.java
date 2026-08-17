package cn.hbads.renderweave.template.internal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

sealed interface JsonValue permits JsonValue.ObjectValue, JsonValue.ArrayValue,
        JsonValue.StringValue, JsonValue.NumberValue, JsonValue.BooleanValue,
        JsonValue.NullValue {

    record ObjectValue(Map<String, JsonValue> members) implements JsonValue {
        public ObjectValue {
            members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        }
    }

    record ArrayValue(List<JsonValue> items) implements JsonValue {
        public ArrayValue {
            items = List.copyOf(items);
        }
    }

    record StringValue(String value) implements JsonValue {
    }

    record NumberValue(String token) implements JsonValue {
    }

    record BooleanValue(boolean value) implements JsonValue {
    }

    enum NullValue implements JsonValue {
        INSTANCE
    }
}
