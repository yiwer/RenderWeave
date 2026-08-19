package cn.hbads.renderweave.template.spi;

/**
 * Outbound seam for Template dependency resolution: system-level checks of the exact
 * dependency facts a Template current needs (authored AssetRef atoms and TemplateUse
 * logical refs). Implemented by the app adapter against the Asset/Template aggregates;
 * never backed by user-invocation authorization.
 */
public interface DependencyResolution {

    /** Asset current existence and kind match for one authored AssetRef atom. */
    AssetCheck checkAsset(String assetId, String kind);

    /** TemplateUse target existence and ACTIVE lifecycle. */
    TemplateCheck checkTemplateUse(String targetTemplateId);

    enum AssetCheck {
        MATCH,
        KIND_MISMATCH,
        NOT_FOUND,
        UNAVAILABLE
    }

    enum TemplateCheck {
        ACTIVE,
        NOT_FOUND,
        NOT_ACTIVE,
        UNAVAILABLE
    }
}
