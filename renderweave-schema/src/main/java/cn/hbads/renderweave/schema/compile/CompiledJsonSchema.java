package cn.hbads.renderweave.schema.compile;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record CompiledJsonSchema(
        String json,
        String compilerVersion,
        int utf8Bytes
) {

    public CompiledJsonSchema {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(compilerVersion, "compilerVersion");
        if (utf8Bytes != json.getBytes(StandardCharsets.UTF_8).length) {
            throw new IllegalArgumentException("utf8Bytes does not match artifact content");
        }
    }
}
