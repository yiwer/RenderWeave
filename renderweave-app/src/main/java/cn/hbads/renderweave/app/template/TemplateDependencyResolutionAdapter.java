package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.DependencyResolution;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** System-level exact dependency fact resolver over Asset and Template aggregates. */
class TemplateDependencyResolutionAdapter implements DependencyResolution {
    private final JdbcClient jdbc;

    TemplateDependencyResolutionAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public AssetResolution resolveAsset(String assetId) {
        try {
            return jdbc.sql("""
                            select owner_scope, kind, lifecycle, asset_revision,
                                   current_content_version
                            from asset_aggregate
                            where asset_id = :assetId
                            """)
                    .param("assetId", assetId)
                    .query((resultSet, rowNumber) -> new AssetResolved(new AssetState(
                            new OwnerScopeAuthority.OwnerScope(
                                    resultSet.getString("owner_scope")),
                            resultSet.getString("kind"),
                            Lifecycle.valueOf(resultSet.getString("lifecycle")),
                            resultSet.getLong("asset_revision"),
                            resultSet.getLong("current_content_version")
                    )))
                    .optional()
                    .<AssetResolution>map(value -> value)
                    .orElseGet(AssetMissing::new);
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return new AssetUnavailable();
        }
    }

    @Override
    public TemplateResolution resolveTemplate(String targetTemplateId) {
        try {
            var row = jdbc.sql("""
                            select a.template_id, a.owner_scope, a.current_revision,
                                   a.lifecycle, a.readiness, a.schema_key,
                                   a.schema_version_tag, r.content_hash,
                                   r.canonical_design_dsl
                            from template_aggregate a
                            join template_revision r
                              on r.template_id = a.template_id
                             and r.revision = a.current_revision
                            where a.template_id = :templateId
                            """)
                    .param("templateId", targetTemplateId)
                    .query((resultSet, rowNumber) -> new TemplateRow(
                            resultSet.getString("template_id"),
                            new OwnerScopeAuthority.OwnerScope(
                                    resultSet.getString("owner_scope")),
                            resultSet.getLong("current_revision"),
                            Lifecycle.valueOf(resultSet.getString("lifecycle")),
                            TemplateApplication.Readiness.valueOf(
                                    resultSet.getString("readiness")),
                            new StaticSchemaRef(
                                    schemaKey(resultSet.getString("schema_key")),
                                    VersionTag.of(resultSet.getString("schema_version_tag"))
                            ),
                            resultSet.getString("content_hash"),
                            new String(resultSet.getBytes("canonical_design_dsl"),
                                    StandardCharsets.UTF_8)
                    ))
                    .optional();
            if (row.isEmpty()) {
                return new TemplateMissing();
            }
            var stored = row.orElseThrow();
            var uses = jdbc.sql("""
                            select target_template_id, canonical_pointer
                            from template_use_reference
                            where template_id = :templateId
                            order by canonical_pointer, target_template_id
                            """)
                    .param("templateId", targetTemplateId)
                    .query((resultSet, rowNumber) -> new TemplateUseEdge(
                            resultSet.getString("target_template_id"),
                            resultSet.getString("canonical_pointer")
                    ))
                    .list();
            return new TemplateResolved(new TemplateState(
                    stored.templateId(),
                    stored.ownerScope(),
                    stored.currentRevision(),
                    stored.lifecycle(),
                    stored.readiness(),
                    stored.staticSchema(),
                    stored.contentHash(),
                    uses,
                    stored.canonicalDesignDsl()
            ));
        } catch (DataAccessException | IllegalArgumentException unavailable) {
            return new TemplateUnavailable();
        }
    }

    private record TemplateRow(
            String templateId,
            OwnerScopeAuthority.OwnerScope ownerScope,
            long currentRevision,
            Lifecycle lifecycle,
            TemplateApplication.Readiness readiness,
            StaticSchemaRef staticSchema,
            String contentHash,
            String canonicalDesignDsl
    ) {
    }

    private static SchemaKey schemaKey(String raw) {
        return raw.startsWith("system-")
                ? SchemaKey.systemProvided(raw)
                : SchemaKey.userProvided(raw);
    }
}
