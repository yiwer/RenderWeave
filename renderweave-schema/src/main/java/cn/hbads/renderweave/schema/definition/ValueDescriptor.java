package cn.hbads.renderweave.schema.definition;

/** Closed value grammar for RenderWeave DSL 1.0. */
public sealed interface ValueDescriptor permits
        TextValue,
        DecimalValue,
        DateValue,
        TimeValue,
        BooleanValue,
        ReferenceValue,
        ArrayValue {

    String type();
}
