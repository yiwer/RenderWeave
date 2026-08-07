package cn.hbads.renderweave.validation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Lossless-enough strict JSON tree: numeric values retain their exact source token. */
public sealed interface StrictJsonValue permits
        StrictJsonValue.ObjectValue,
        StrictJsonValue.ArrayValue,
        StrictJsonValue.StringValue,
        StrictJsonValue.NumberValue,
        StrictJsonValue.BooleanValue,
        StrictJsonValue.NullValue {

    SourceSpan span();

    String kind();

    record SourceSpan(long startByte, long endByte) {
        public SourceSpan {
            if (startByte < 0 || endByte < startByte) {
                throw new IllegalArgumentException("Invalid JSON source span");
            }
        }

        public long length() {
            return endByte - startByte;
        }
    }

    record ObjectValue(Map<String, StrictJsonValue> members, SourceSpan span)
            implements StrictJsonValue {
        public ObjectValue {
            Objects.requireNonNull(members, "members");
            members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
            Objects.requireNonNull(span, "span");
        }

        @Override
        public String kind() {
            return "object";
        }
    }

    record ArrayValue(List<StrictJsonValue> items, SourceSpan span) implements StrictJsonValue {
        public ArrayValue {
            items = List.copyOf(items);
            Objects.requireNonNull(span, "span");
        }

        @Override
        public String kind() {
            return "array";
        }
    }

    record StringValue(String value, SourceSpan span) implements StrictJsonValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(span, "span");
        }

        @Override
        public String kind() {
            return "string";
        }
    }

    record NumberValue(String rawToken, SourceSpan span) implements StrictJsonValue {
        public NumberValue {
            Objects.requireNonNull(rawToken, "rawToken");
            Objects.requireNonNull(span, "span");
        }

        @Override
        public String kind() {
            return "number";
        }
    }

    record BooleanValue(boolean value, SourceSpan span) implements StrictJsonValue {
        public BooleanValue {
            Objects.requireNonNull(span, "span");
        }

        @Override
        public String kind() {
            return "boolean";
        }
    }

    record NullValue(SourceSpan span) implements StrictJsonValue {
        public NullValue {
            Objects.requireNonNull(span, "span");
        }

        @Override
        public String kind() {
            return "null";
        }
    }
}
