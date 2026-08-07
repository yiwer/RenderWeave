package cn.hbads.renderweave.schema.compile;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

@FunctionalInterface
public interface StaticArtifactResolver {

    CompiledStaticArtifact resolve(StaticSchemaRef reference);
}
