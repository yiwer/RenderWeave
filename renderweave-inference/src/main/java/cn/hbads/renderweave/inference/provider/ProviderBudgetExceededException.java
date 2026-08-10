package cn.hbads.renderweave.inference.provider;

public final class ProviderBudgetExceededException extends RuntimeException {
    private final String code;

    public ProviderBudgetExceededException(String code) {
        super(code);
        if (!"PROVIDER_ATTEMPT_BUDGET_EXHAUSTED".equals(code)
                && !"PROVIDER_COST_BUDGET_EXHAUSTED".equals(code)
                && !"PROVIDER_RUN_COST_LIMIT_EXCEEDED".equals(code)
                && !"PROVIDER_ATTEMPT_ALREADY_RESERVED".equals(code)) {
            throw new IllegalArgumentException("Unsupported provider budget code");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
