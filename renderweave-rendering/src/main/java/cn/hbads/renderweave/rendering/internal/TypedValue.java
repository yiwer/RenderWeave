package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * AdmittedRenderInput 的 closed typed context 值家族（冻结票据 06）：只包含 Schema 声明且
 * PRESENT 的语义值；ABSENT 以 {@link Optional#empty()} 表达；未知字段不进入任何可观察作用域。
 */
sealed interface TypedValue permits
        TypedValue.Text,
        TypedValue.Decimal,
        TypedValue.Bool,
        TypedValue.Date,
        TypedValue.Time,
        TypedValue.Nested,
        TypedValue.Array,
        TypedObject {

    record Text(String value) implements TypedValue {
        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    record Decimal(BigDecimal value) implements TypedValue {
        public Decimal {
            Objects.requireNonNull(value, "value");
        }
    }

    record Bool(boolean value) implements TypedValue {
    }

    record Date(String value) implements TypedValue {
        public Date {
            Objects.requireNonNull(value, "value");
        }
    }

    record Time(String value) implements TypedValue {
        public Time {
            Objects.requireNonNull(value, "value");
        }
    }

    /** 引用字段的 typed subview：携带 exact StaticSchema 证明并保持不可变。 */
    record Nested(StaticSchemaRef reference, TypedObject object) implements TypedValue {
        public Nested {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(object, "object");
        }
    }

    record Array(List<TypedValue> items) implements TypedValue {
        public Array {
            items = List.copyOf(items);
        }
    }
}
