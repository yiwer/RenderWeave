package cn.hbads.renderweave.schema.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deterministic reference occurrences in persisted field order. */
public final class DefinitionReferences {

    private DefinitionReferences() {
    }

    public static List<Occurrence> find(SchemaDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        var occurrences = new ArrayList<Occurrence>();
        for (int index = 0; index < definition.fields().size(); index++) {
            var value = definition.fields().get(index).value();
            var valuePointer = "/fields/" + index + "/value";
            if (value instanceof ReferenceValue reference) {
                occurrences.add(new Occurrence(valuePointer + "/ref", reference.ref()));
            } else if (value instanceof ArrayValue array && array.items() instanceof ReferenceValue reference) {
                occurrences.add(new Occurrence(valuePointer + "/items/ref", reference.ref()));
            }
        }
        return List.copyOf(occurrences);
    }

    public record Occurrence(String pointer, SchemaReference reference) {
        public Occurrence {
            Objects.requireNonNull(pointer, "pointer");
            Objects.requireNonNull(reference, "reference");
        }
    }
}
