package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.schema.definition.SchemaDefinition;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.template.api.DesignDslAuthority;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

final class TemplateTestData {
    private static final DesignDslAuthority.Admitted EMPTY_DESIGN = admittedEmptyDesign();

    private TemplateTestData() {
    }

    static StaticSchemaAuthority.Resolved resolvedEmpty(StaticSchemaRef reference) {
        return new StaticSchemaAuthority.Resolved(reference, new SchemaDefinition(
                SchemaDefinition.DSL_VERSION,
                "Empty fixture",
                Optional.empty(),
                List.of()
        ));
    }

    static String emptyDesignCanonical() {
        return new String(EMPTY_DESIGN.canonicalUtf8(), StandardCharsets.UTF_8);
    }

    static String emptyDesignContentHash() {
        return EMPTY_DESIGN.contentHash();
    }

    private static DesignDslAuthority.Admitted admittedEmptyDesign() {
        var raw = """
                {"dslVersion":"renderweave-design/1.0",
                 "expressionProfile":"renderweave-expression/1.0",
                 "displayName":"Dependency child fixture","definitions":[],
                 "designRoot":{"nodeId":"00000000-0000-4000-8000-0000000000f0",
                 "kind":"canvas","widthMm":210,"heightMm":297,
                 "bindings":[],"children":[]}}
                """.getBytes(StandardCharsets.UTF_8);
        var result = new CanonicalDesignDslAuthority().admit(raw);
        if (result instanceof DesignDslAuthority.Admitted admitted) {
            return admitted;
        }
        throw new ExceptionInInitializerError("empty DesignDSL test fixture must admit");
    }
}
