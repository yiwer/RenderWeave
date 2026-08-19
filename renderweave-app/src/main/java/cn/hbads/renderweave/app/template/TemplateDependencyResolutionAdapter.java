package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.template.spi.DependencyResolution;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;
import java.util.Objects;

/**
 * System-level Template dependency probe over the Asset/Template aggregates. This is
 * dependency checking, not user-facing reads: no invocation authorization applies.
 * Instantiated by {@link TemplateApplicationConfiguration}.
 */
class TemplateDependencyResolutionAdapter implements DependencyResolution {

    private static final Map<String, String> DESIGN_KIND_TO_ASSET_KIND = Map.of(
            "imageRef", "IMAGE",
            "fontRef", "FONT"
    );

    private final JdbcClient jdbc;

    TemplateDependencyResolutionAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public AssetCheck checkAsset(String assetId, String kind) {
        String expected = DESIGN_KIND_TO_ASSET_KIND.get(kind);
        try {
            var row = jdbc.sql("""
                            select kind, lifecycle
                            from asset_aggregate
                            where asset_id = :assetId
                            """)
                    .param("assetId", assetId)
                    .query((resultSet, rowNumber) -> new String[]{
                            resultSet.getString("kind"),
                            resultSet.getString("lifecycle")
                    })
                    .optional();
            if (row.isEmpty()) {
                return AssetCheck.NOT_FOUND;
            }
            var stored = row.orElseThrow();
            if (!"ACTIVE".equals(stored[1])) {
                return AssetCheck.NOT_FOUND;
            }
            return expected != null && expected.equals(stored[0])
                    ? AssetCheck.MATCH
                    : AssetCheck.KIND_MISMATCH;
        } catch (DataAccessException unavailable) {
            return AssetCheck.UNAVAILABLE;
        }
    }

    @Override
    public TemplateCheck checkTemplateUse(String targetTemplateId) {
        try {
            var lifecycle = jdbc.sql("""
                            select lifecycle
                            from template_aggregate
                            where template_id = :templateId
                            """)
                    .param("templateId", targetTemplateId)
                    .query((resultSet, rowNumber) -> resultSet.getString("lifecycle"))
                    .optional();
            if (lifecycle.isEmpty()) {
                return TemplateCheck.NOT_FOUND;
            }
            return "ACTIVE".equals(lifecycle.orElseThrow())
                    ? TemplateCheck.ACTIVE
                    : TemplateCheck.NOT_ACTIVE;
        } catch (DataAccessException unavailable) {
            return TemplateCheck.UNAVAILABLE;
        }
    }
}
