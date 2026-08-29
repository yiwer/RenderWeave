package cn.hbads.renderweave.app.coordination;

import cn.hbads.renderweave.template.spi.DependencyResolution;

/** App assembly seam exposing Asset-owned dependency facts to Template resolution. */
public interface AssetDependencyFacts {
    DependencyResolution.AssetResolution resolve(String assetId);
}
