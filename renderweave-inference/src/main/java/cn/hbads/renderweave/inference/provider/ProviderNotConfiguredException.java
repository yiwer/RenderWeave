package cn.hbads.renderweave.inference.provider;

public final class ProviderNotConfiguredException extends RuntimeException {
    private final String code;

    public ProviderNotConfiguredException(String code) {
        super(code);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("code must be a stable uppercase identifier");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
