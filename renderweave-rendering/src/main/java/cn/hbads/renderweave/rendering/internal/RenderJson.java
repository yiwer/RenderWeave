package cn.hbads.renderweave.rendering.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rendering-owned strict JSON value tree: numeric values retain their exact source token,
 * every value carries its raw byte span so admitted slices can be spliced verbatim.
 */
sealed interface RenderJson permits
        RenderJson.ObjectValue,
        RenderJson.ArrayValue,
        RenderJson.StringValue,
        RenderJson.NumberValue,
        RenderJson.BooleanValue,
        RenderJson.NullValue {

    long startByte();

    long endByte();

    record ObjectValue(Map<String, RenderJson> members, long startByte, long endByte) implements RenderJson {
        public ObjectValue {
            Objects.requireNonNull(members, "members");
        }
    }

    record ArrayValue(List<RenderJson> items, long startByte, long endByte) implements RenderJson {
        public ArrayValue {
            Objects.requireNonNull(items, "items");
        }
    }

    record StringValue(String value, long startByte, long endByte) implements RenderJson {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record NumberValue(String rawToken, long startByte, long endByte) implements RenderJson {
        public NumberValue {
            Objects.requireNonNull(rawToken, "rawToken");
        }
    }

    record BooleanValue(boolean value, long startByte, long endByte) implements RenderJson {
    }

    record NullValue(long startByte, long endByte) implements RenderJson {
    }
}
