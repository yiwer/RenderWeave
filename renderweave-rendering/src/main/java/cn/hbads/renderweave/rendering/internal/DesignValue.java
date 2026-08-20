package cn.hbads.renderweave.rendering.internal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Typed DesignDSL semantic values（冻结票据 07 §types）：BaseValueType =
 * text | decimal | boolean | date | time | color | imageRef | fontRef，list&lt;T&gt; 为派生。
 * decimal 是任意精度值；{@code 1.0 == 1.00}、{@code -0 == 0}，scale/尾零不可观察。
 */
sealed interface DesignValue permits
        DesignValue.Text,
        DesignValue.Decimal,
        DesignValue.Bool,
        DesignValue.Date,
        DesignValue.Time,
        DesignValue.Color,
        DesignValue.ImageRef,
        DesignValue.FontRef,
        DesignValue.ListValue {

    String baseType();

    record Text(String value) implements DesignValue {
        public Text {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String baseType() {
            return "text";
        }
    }

    record Decimal(BigDecimal value) implements DesignValue {
        public Decimal {
            Objects.requireNonNull(value, "value");
            value = value.stripTrailingZeros();
            if (value.signum() == 0) {
                value = BigDecimal.ZERO;
            }
        }

        @Override
        public String baseType() {
            return "decimal";
        }
    }

    record Bool(boolean value) implements DesignValue {
        @Override
        public String baseType() {
            return "boolean";
        }
    }

    record Date(String value) implements DesignValue {
        public Date {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String baseType() {
            return "date";
        }
    }

    record Time(String value) implements DesignValue {
        public Time {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String baseType() {
            return "time";
        }
    }

    record Color(String value) implements DesignValue {
        public Color {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String baseType() {
            return "color";
        }
    }

    record ImageRef(String assetId) implements DesignValue {
        public ImageRef {
            Objects.requireNonNull(assetId, "assetId");
        }

        @Override
        public String baseType() {
            return "imageRef";
        }
    }

    record FontRef(String assetId) implements DesignValue {
        public FontRef {
            Objects.requireNonNull(assetId, "assetId");
        }

        @Override
        public String baseType() {
            return "fontRef";
        }
    }

    record ListValue(String itemType, List<DesignValue> items) implements DesignValue {
        public ListValue {
            Objects.requireNonNull(itemType, "itemType");
            items = List.copyOf(items);
        }

        @Override
        public String baseType() {
            return "list<" + itemType + ">";
        }
    }
}
