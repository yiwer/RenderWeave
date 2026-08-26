package cn.hbads.renderweave.asset.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;
import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.Limit;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact Ticket 19 scalar capacity guard shared by every Asset content parser.
 *
 * <p>The guard owns limits and comparison semantics only. Media parsers remain responsible for
 * deriving the authoritative byte length or decoded descriptor scalar before calling it.</p>
 */
final class AssetContentCapacityGuard {

    private AssetContentCapacityGuard() {
    }

    static Decision evaluate(Axis axis, long observedValue) {
        Objects.requireNonNull(axis, "axis");
        if (observedValue < 0) {
            throw new IllegalArgumentException("observedValue must be nonnegative");
        }
        return new Decision(axis, observedValue, observedValue <= axis.maximumInclusive());
    }

    static Optional<Limit> rawBytesExceeded(AssetKind kind, long observedBytes) {
        Objects.requireNonNull(kind, "kind");
        Axis axis = kind == AssetKind.IMAGE ? Axis.IMAGE_RAW_BYTES : Axis.FONT_RAW_BYTES;
        return evaluate(axis, observedBytes).accepted()
                ? Optional.empty()
                : Optional.of(Limit.RAW_BYTES);
    }

    static Optional<Limit> imageDimensionsExceeded(long width, long height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        if (!evaluate(Axis.IMAGE_EDGE_PIXELS, Math.max(width, height)).accepted()) {
            return Optional.of(Limit.IMAGE_EDGE_PIXELS);
        }
        long pixels = Math.multiplyExact(width, height);
        if (!evaluate(Axis.IMAGE_TOTAL_PIXELS, pixels).accepted()) {
            return Optional.of(Limit.IMAGE_TOTAL_PIXELS);
        }
        return Optional.empty();
    }

    enum Axis {
        IMAGE_RAW_BYTES("assetsAndFetch.acceptedImageBytesPerContent", 67_108_864L),
        IMAGE_EDGE_PIXELS("assetsAndFetch.acceptedImageEdgePixelsPerContent", 20_000L),
        IMAGE_TOTAL_PIXELS("assetsAndFetch.acceptedImagePixelsPerContent", 100_000_000L),
        FONT_RAW_BYTES("assetsAndFetch.acceptedFontBytesPerContent", 33_554_432L);

        private final String externalId;
        private final long maximumInclusive;

        Axis(String externalId, long maximumInclusive) {
            this.externalId = externalId;
            this.maximumInclusive = maximumInclusive;
        }

        String externalId() {
            return externalId;
        }

        long maximumInclusive() {
            return maximumInclusive;
        }

        static Axis fromExternalId(String externalId) {
            Objects.requireNonNull(externalId, "externalId");
            return Arrays.stream(values())
                    .filter(axis -> axis.externalId.equals(externalId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown capacity axis"));
        }
    }

    record Decision(Axis axis, long observedValue, boolean accepted) {
        Decision {
            Objects.requireNonNull(axis, "axis");
            if (observedValue < 0) {
                throw new IllegalArgumentException("observedValue must be nonnegative");
            }
        }
    }
}
