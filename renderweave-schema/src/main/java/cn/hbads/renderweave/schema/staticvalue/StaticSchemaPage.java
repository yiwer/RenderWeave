package cn.hbads.renderweave.schema.staticvalue;

import java.util.List;

public record StaticSchemaPage(
        List<StaticSchemaSummary> items,
        int page,
        int size,
        long total
) {

    public StaticSchemaPage {
        items = List.copyOf(items);
        if (page < 1 || size < 1 || total < 0) {
            throw new IllegalArgumentException("invalid StaticSchema pagination");
        }
    }
}
