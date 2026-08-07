package cn.hbads.renderweave.inference.input;

public enum InferenceMode {
    IMAGE_ONLY("image-only"),
    JSON_ONLY("json-only"),
    COMBINED("combined");

    private final String wireName;

    InferenceMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static InferenceMode fromWireName(String value) {
        for (var mode : values()) {
            if (mode.wireName.equals(value)) return mode;
        }
        throw new IllegalArgumentException("Unsupported inference mode: " + value);
    }
}
