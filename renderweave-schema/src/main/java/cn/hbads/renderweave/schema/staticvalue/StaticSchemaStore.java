package cn.hbads.renderweave.schema.staticvalue;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.util.List;
import java.util.Optional;

public interface StaticSchemaStore {

    StoredStaticSchema publish(PublishStaticSchema command);

    Optional<StoredStaticSchema> find(StaticSchemaRef reference);

    List<StoredStaticSchemaSummary> findPage(int offset, int limit);

    long count();

    List<StoredStaticSchemaSummary> findPage(
            int offset,
            int limit,
            String search,
            StaticSchemaListSort sort,
            StaticSchemaOriginFilter origin
    );

    long count(String search, StaticSchemaOriginFilter origin);
}
