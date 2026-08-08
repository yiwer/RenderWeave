package cn.hbads.renderweave.inference.provider;

public record ProviderUsage(long inputTokens, long outputTokens) {
    public ProviderUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("Provider token usage must not be negative");
        }
    }

    public long totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }
}
