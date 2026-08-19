package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.AssetReferenceAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;

import java.util.Objects;

final class CanonicalAssetReferenceAuthority implements AssetReferenceAuthority {
    private final TemplatePersistence persistence;

    CanonicalAssetReferenceAuthority(TemplatePersistence persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    @Override
    public ReferencesOutcome references(String assetId) {
        Objects.requireNonNull(assetId, "assetId");
        var outcome = persistence.findAssetReferences(assetId);
        if (outcome instanceof TemplatePersistence.AssetReferencesLoaded loaded) {
            return new ReferencesReadable(new AssetReferences(loaded.templateIds()));
        }
        return new ReferencesUnavailable();
    }
}
