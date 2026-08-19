package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.schema.api.StaticSchemaAuthority;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.staticvalue.StaticSchemaStore;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
final class PostgresStaticSchemaAuthority implements StaticSchemaAuthority {
    private final StaticSchemaStore statics;

    PostgresStaticSchemaAuthority(StaticSchemaStore statics) {
        this.statics = statics;
    }

    @Override
    public Resolution resolve(StaticSchemaRef reference) {
        try {
            return statics.find(reference)
                    .<Resolution>map(ignored -> new Resolved(reference))
                    .orElseGet(NotFound::new);
        } catch (DataAccessException unavailable) {
            return new Unavailable();
        }
    }
}
