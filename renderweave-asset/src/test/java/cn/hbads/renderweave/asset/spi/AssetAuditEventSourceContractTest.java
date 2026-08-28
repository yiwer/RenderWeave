package cn.hbads.renderweave.asset.spi;

import cn.hbads.renderweave.asset.api.AssetApplication;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetAuditEventSourceContractTest {

    private static final AssetApplication.AssetId ASSET_ID = AssetApplication.AssetId.of(
            "123e4567-e89b-42d3-a456-426614174000");

    @Test
    void page_is_bounded_and_strictly_ordered() {
        var first = new AssetAuditEventSource.MutationEvent(
                41, ASSET_ID, AssetAuditEventSource.MutationOperation.CONTENT_REPLACE);
        var second = new AssetAuditEventSource.MutationEvent(
                44, ASSET_ID, AssetAuditEventSource.MutationOperation.DELETE);

        var page = new AssetAuditEventSource.EventPage(List.of(first, second), 2);

        assertEquals(List.of(first, second), page.events());
        assertThrows(IllegalArgumentException.class,
                () -> new AssetAuditEventSource.EventPage(List.of(second, first), 2));
        assertThrows(IllegalArgumentException.class,
                () -> new AssetAuditEventSource.EventPage(List.of(first, second), 1));
    }

    @Test
    void event_identity_and_read_limit_are_closed() {
        assertThrows(IllegalArgumentException.class, () -> new AssetAuditEventSource.MutationEvent(
                0, ASSET_ID, AssetAuditEventSource.MutationOperation.RESTORE));
        assertThrows(IllegalArgumentException.class,
                () -> AssetAuditEventSource.requireReadLimit(0));
        assertThrows(IllegalArgumentException.class,
                () -> AssetAuditEventSource.requireReadLimit(201));
        assertEquals(200, AssetAuditEventSource.requireReadLimit(200));
    }
}
