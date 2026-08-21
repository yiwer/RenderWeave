package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.schema.definition.InvalidSchemaDefinitionException;
import cn.hbads.renderweave.schema.definition.SchemaDefinitionJsonParser;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaStore;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
final class PostgresStaticSchemaAuthority implements StaticSchemaAuthority {
    private final StaticSchemaStore statics;
    private final SchemaDefinitionJsonParser parser = new SchemaDefinitionJsonParser();

    PostgresStaticSchemaAuthority(StaticSchemaStore statics) {
        this.statics = statics;
    }

    @Override
    public Resolution resolve(StaticSchemaRef reference) {
        try {
            return statics.find(reference)
                    .<Resolution>map(stored -> new Resolved(
                            reference,
                            parser.parse(stored.definitionJson())
                    ))
                    .orElseGet(NotFound::new);
        } catch (DataAccessException | InvalidSchemaDefinitionException unavailable) {
            return new Unavailable();
        }
    }
}
