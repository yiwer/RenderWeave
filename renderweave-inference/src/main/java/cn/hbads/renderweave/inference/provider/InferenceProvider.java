package cn.hbads.renderweave.inference.provider;

/** Provider capability is deliberately one bounded completion call and exposes no tools. */
@FunctionalInterface
public interface InferenceProvider {
    ProviderInferenceResponse complete(ProviderInferenceRequest request);
}
