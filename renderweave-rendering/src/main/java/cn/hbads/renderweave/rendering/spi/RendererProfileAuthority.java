package cn.hbads.renderweave.rendering.spi;

import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;

/**
 * 部署对 exact Layout/Renderer compatibility 的唯一可用性权威。
 *
 * <p>调用方不能选择 Profile、latest 或 fallback；没有 Certified mapping 时必须在 Evaluation
 * 与任何 payload work 前返回 {@link Unavailable}。
 */
public interface RendererProfileAuthority {

    Selection select(OutputSelection outputSelection);

    sealed interface Selection permits Available, Unavailable {
    }

    record Available(String rendererProfile, String layoutProfile) implements Selection {
        public Available {
            requireProfile(rendererProfile, "rendererProfile");
            requireProfile(layoutProfile, "layoutProfile");
        }

        private static void requireProfile(String value, String name) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(
                        name + " must be non-blank and at most 256 characters");
            }
        }
    }

    record Unavailable() implements Selection {
    }
}
