package cn.hbads.renderweave.validation;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.util.Objects;

public sealed interface ValidationTarget permits
        ValidationTarget.DraftTarget,
        ValidationTarget.StaticTarget {

    String kind();

    record DraftTarget(SchemaKey schemaKey) implements ValidationTarget {
        public DraftTarget {
            Objects.requireNonNull(schemaKey, "schemaKey");
        }

        @Override
        public String kind() {
            return "draft";
        }
    }

    record StaticTarget(StaticSchemaRef reference) implements ValidationTarget {
        public StaticTarget {
            Objects.requireNonNull(reference, "reference");
        }

        @Override
        public String kind() {
            return "static";
        }
    }
}
