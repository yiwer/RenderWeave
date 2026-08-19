package cn.hbads.renderweave.schema.api;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.util.Objects;

/** Provider-owned exact-reference existence authority for downstream contexts. */
public interface StaticSchemaAuthority {

    Resolution resolve(StaticSchemaRef reference);

    sealed interface Resolution permits Resolved, NotFound, Unavailable {
    }

    record Resolved(StaticSchemaRef reference) implements Resolution {
        public Resolved {
            Objects.requireNonNull(reference, "reference");
        }
    }

    record NotFound() implements Resolution {
    }

    record Unavailable() implements Resolution {
    }
}
