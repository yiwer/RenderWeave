package cn.hbads.renderweave.schema.api;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.definition.SchemaDefinition;

import java.util.Objects;

/** Provider-owned exact immutable definition authority for downstream typed contexts. */
public interface StaticSchemaAuthority {

    Resolution resolve(StaticSchemaRef reference);

    sealed interface Resolution permits Resolved, NotFound, Unavailable {
    }

    record Resolved(
            StaticSchemaRef reference,
            SchemaDefinition definition
    ) implements Resolution {
        public Resolved {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(definition, "definition");
        }
    }

    record NotFound() implements Resolution {
    }

    record Unavailable() implements Resolution {
    }
}
