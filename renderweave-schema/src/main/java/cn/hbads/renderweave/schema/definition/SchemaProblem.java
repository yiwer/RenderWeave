package cn.hbads.renderweave.schema.definition;

import java.util.Objects;

/** Stable machine-readable diagnostic for invalid RenderWeave DSL. */
public record SchemaProblem(String code, String pointer, String message) {

    public SchemaProblem {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(message, "message");
    }
}
