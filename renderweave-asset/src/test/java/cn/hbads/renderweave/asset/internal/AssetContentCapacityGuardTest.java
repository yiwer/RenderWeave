package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetContentCapacityGuardTest {

    @Test
    void freezesTheFourTicket19AxesAndMaxInclusiveBoundaries() {
        assertArrayEquals(
                new String[]{
                        "assetsAndFetch.acceptedImageBytesPerContent",
                        "assetsAndFetch.acceptedImageEdgePixelsPerContent",
                        "assetsAndFetch.acceptedImagePixelsPerContent",
                        "assetsAndFetch.acceptedFontBytesPerContent"
                },
                List.of(AssetContentCapacityGuard.Axis.values()).stream()
                        .map(AssetContentCapacityGuard.Axis::externalId)
                        .toArray(String[]::new)
        );
        assertArrayEquals(
                new long[]{67_108_864L, 20_000L, 100_000_000L, 33_554_432L},
                List.of(AssetContentCapacityGuard.Axis.values()).stream()
                        .mapToLong(AssetContentCapacityGuard.Axis::maximumInclusive)
                        .toArray()
        );

        for (var axis : AssetContentCapacityGuard.Axis.values()) {
            assertTrue(AssetContentCapacityGuard.evaluate(
                    axis, axis.maximumInclusive() - 1).accepted());
            assertTrue(AssetContentCapacityGuard.evaluate(
                    axis, axis.maximumInclusive()).accepted());
            assertFalse(AssetContentCapacityGuard.evaluate(
                    axis, axis.maximumInclusive() + 1).accepted());
            assertEquals(axis, AssetContentCapacityGuard.Axis.fromExternalId(axis.externalId()));
        }
    }

    @Test
    void productionMappingsUseTheSameGuardAndPreserveFirstErrorOrder() {
        assertTrue(AssetContentCapacityGuard.rawBytesExceeded(
                AssetAcceptanceAuthority.AssetKind.IMAGE, 67_108_864L).isEmpty());
        assertEquals(
                AssetAcceptanceAuthority.Limit.RAW_BYTES,
                AssetContentCapacityGuard.rawBytesExceeded(
                        AssetAcceptanceAuthority.AssetKind.IMAGE, 67_108_865L).orElseThrow()
        );
        assertTrue(AssetContentCapacityGuard.rawBytesExceeded(
                AssetAcceptanceAuthority.AssetKind.FONT, 33_554_432L).isEmpty());
        assertEquals(
                AssetAcceptanceAuthority.Limit.RAW_BYTES,
                AssetContentCapacityGuard.rawBytesExceeded(
                        AssetAcceptanceAuthority.AssetKind.FONT, 33_554_433L).orElseThrow()
        );

        assertTrue(AssetContentCapacityGuard.imageDimensionsExceeded(10_000L, 10_000L).isEmpty());
        assertEquals(
                AssetAcceptanceAuthority.Limit.IMAGE_TOTAL_PIXELS,
                AssetContentCapacityGuard.imageDimensionsExceeded(10_001L, 10_000L).orElseThrow()
        );
        assertEquals(
                AssetAcceptanceAuthority.Limit.IMAGE_EDGE_PIXELS,
                AssetContentCapacityGuard.imageDimensionsExceeded(20_001L, 20_001L).orElseThrow()
        );
    }

    @Test
    void rejectsUnknownAxesAndInvalidObservedScalars() {
        assertThrows(IllegalArgumentException.class,
                () -> AssetContentCapacityGuard.Axis.fromExternalId("latest"));
        assertThrows(IllegalArgumentException.class,
                () -> AssetContentCapacityGuard.evaluate(
                        AssetContentCapacityGuard.Axis.IMAGE_RAW_BYTES, -1));
        assertThrows(IllegalArgumentException.class,
                () -> AssetContentCapacityGuard.imageDimensionsExceeded(0, 1));
    }
}
