package cn.hbads.renderweave.schema.draft;

import java.util.List;

public record DraftPage(List<DraftSummary> items, int page, int size, long total) {

    public DraftPage {
        items = List.copyOf(items);
        if (page < 1 || size < 1 || total < 0) {
            throw new IllegalArgumentException("invalid Draft pagination");
        }
    }
}
