package cn.hbads.renderweave.inference.provider;

public record ProviderBudgetSnapshot(
        String budgetKey,
        int maximumAttempts,
        int consumedAttempts,
        long maximumCostMicrosCny,
        long consumedCostMicrosCny
) {
    public ProviderBudgetSnapshot {
        if (budgetKey == null || budgetKey.isBlank()) throw new IllegalArgumentException("budgetKey is required");
        if (maximumAttempts < 1 || consumedAttempts < 0 || consumedAttempts > maximumAttempts
                || maximumCostMicrosCny < 1 || consumedCostMicrosCny < 0
                || consumedCostMicrosCny > maximumCostMicrosCny) {
            throw new IllegalArgumentException("Budget snapshot is inconsistent");
        }
    }

    public int remainingAttempts() {
        return maximumAttempts - consumedAttempts;
    }

    public long remainingCostMicrosCny() {
        return maximumCostMicrosCny - consumedCostMicrosCny;
    }
}
