package cn.hbads.renderweave.validation;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.Objects;

public sealed interface ResolvedSchemaIdentity permits
        ResolvedSchemaIdentity.DraftIdentity,
        ResolvedSchemaIdentity.StaticIdentity {

    String kind();

    SchemaKey schemaKey();

    record DraftIdentity(SchemaKey schemaKey, long revision) implements ResolvedSchemaIdentity {
        public DraftIdentity {
            Objects.requireNonNull(schemaKey, "schemaKey");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
        }

        @Override
        public String kind() {
            return "draft";
        }
    }

    record StaticIdentity(StaticSchemaRef reference) implements ResolvedSchemaIdentity {
        public StaticIdentity {
            Objects.requireNonNull(reference, "reference");
        }

        @Override
        public String kind() {
            return "static";
        }

        @Override
        public SchemaKey schemaKey() {
            return reference.schemaKey();
        }
    }
}
