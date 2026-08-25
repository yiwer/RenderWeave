package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.spi.RendererProfileAuthority;

/** No partial/test-only Profile is available until a Certified deployment adapter replaces this. */
final class FailClosedRendererProfileAuthority implements RendererProfileAuthority {
    @Override
    public Selection select(OutputSelection outputSelection) {
        return new Unavailable();
    }
}
