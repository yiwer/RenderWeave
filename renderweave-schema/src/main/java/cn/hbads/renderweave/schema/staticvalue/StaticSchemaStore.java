package cn.hbads.renderweave.schema.staticvalue;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.util.List;
import java.util.Optional;

public interface StaticSchemaStore {

    StoredStaticSchema publish(PublishStaticSchema command);

    Optional<StoredStaticSchema> find(StaticSchemaRef reference);

    List<StoredStaticSchema> findPage(int offset, int limit);

    long count();
}
