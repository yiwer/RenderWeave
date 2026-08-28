package cn.hbads.renderweave.asset.spi;

import cn.hbads.renderweave.asset.api.AssetApplication;

import java.util.List;
import java.util.Objects;

/**
 * Asset-owned outbound Interface for replaying bounded aggregate mutation facts.
 * Callers own their cursor and side effects; this source never advances foreign state or
 * participates in a caller transaction.
 */
public interface AssetAuditEventSource {

    int MAXIMUM_READ_LIMIT = 200;

    EventPage readAfter(long exclusiveEventId, int limit);

    enum MutationOperation {
        CREATE,
        METADATA_UPDATE,
        CONTENT_REPLACE,
        CONTENT_RESTORE,
        DELETE,
        RESTORE
    }

    record MutationEvent(
            long eventId,
            AssetApplication.AssetId assetId,
            MutationOperation operation
    ) {
        public MutationEvent {
            if (eventId <= 0) {
                throw new IllegalArgumentException("eventId must be positive");
            }
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(operation, "operation");
        }
    }

    record EventPage(List<MutationEvent> events, int requestedLimit) {
        public EventPage {
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            requireReadLimit(requestedLimit);
            if (events.size() > requestedLimit) {
                throw new IllegalArgumentException("event page exceeds requested limit");
            }
            long previous = 0;
            for (var event : events) {
                if (event.eventId() <= previous) {
                    throw new IllegalArgumentException(
                            "event page must be strictly ordered by eventId");
                }
                previous = event.eventId();
            }
        }
    }

    static int requireReadLimit(int limit) {
        if (limit < 1 || limit > MAXIMUM_READ_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        return limit;
    }
}
