package cn.hbads.renderweave.asset.api;

import java.util.Objects;
import java.util.Optional;

/** Static IMAGE/FONT admission authority for the Asset deep module. */
public interface AssetAcceptanceAuthority {

    String ACCEPTANCE_PROFILE_ID = "renderweave-asset-acceptance/1.0";

    Acceptance admit(byte[] rawBytes, AssetKind kind);

    sealed interface Acceptance permits Admitted, Rejected {
    }

    final class Admitted implements Acceptance {
        private final AssetKind kind;
        private final long byteLength;
        private final String sha256;
        private final TechnicalDescriptor descriptor;
        private final String acceptanceProfileId;

        public Admitted(
                AssetKind kind,
                long byteLength,
                String sha256,
                TechnicalDescriptor descriptor,
                String acceptanceProfileId
        ) {
            this.kind = Objects.requireNonNull(kind, "kind");
            if (byteLength <= 0) {
                throw new IllegalArgumentException("byteLength must be positive");
            }
            this.byteLength = byteLength;
            this.sha256 = Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be 64 lowercase hex digits");
            }
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.acceptanceProfileId = Objects.requireNonNull(
                    acceptanceProfileId,
                    "acceptanceProfileId"
            );
        }

        public AssetKind kind() {
            return kind;
        }

        public long byteLength() {
            return byteLength;
        }

        public String sha256() {
            return sha256;
        }

        public TechnicalDescriptor descriptor() {
            return descriptor;
        }

        public String acceptanceProfileId() {
            return acceptanceProfileId;
        }
    }

    record Rejected(
            FailureCode code,
            FailureStage stage,
            String pointer,
            Optional<Limit> limit
    ) implements Acceptance {
        public Rejected {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(pointer, "pointer");
            limit = Objects.requireNonNull(limit, "limit");
        }
    }

    enum AssetKind {
        IMAGE,
        FONT
    }

    enum FailureCode {
        ASSET_CONTENT_INVALID,
        ASSET_CONTENT_UNSUPPORTED,
        ASSET_CONTENT_LIMIT_EXCEEDED
    }

    enum FailureStage {
        ASSET_STRUCTURE,
        ASSET_DECODE,
        ASSET_DESCRIPTOR
    }

    enum Limit {
        RAW_BYTES("assetAcceptance.rawBytes"),
        IMAGE_EDGE_PIXELS("assetAcceptance.imageEdgePixels"),
        IMAGE_TOTAL_PIXELS("assetAcceptance.imageTotalPixels");

        private final String id;

        Limit(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    sealed interface TechnicalDescriptor permits ImageDescriptor, FontDescriptor {
    }

    record ImageDescriptor(
            int encodedWidthPx,
            int encodedHeightPx,
            Orientation orientation,
            int logicalWidthPx,
            int logicalHeightPx,
            int frameCount,
            ColorEncoding colorEncoding
    ) implements TechnicalDescriptor {
        public ImageDescriptor {
            Objects.requireNonNull(orientation, "orientation");
            Objects.requireNonNull(colorEncoding, "colorEncoding");
            if (encodedWidthPx <= 0 || encodedHeightPx <= 0) {
                throw new IllegalArgumentException("encoded dimensions must be positive");
            }
            if (logicalWidthPx <= 0 || logicalHeightPx <= 0) {
                throw new IllegalArgumentException("logical dimensions must be positive");
            }
            if (frameCount != 1) {
                throw new IllegalArgumentException("frameCount must be 1 for static images");
            }
            if (colorEncoding != ColorEncoding.SRGB_8BIT) {
                throw new IllegalArgumentException("only SRGB_8BIT is admitted");
            }
        }
    }

    record FontDescriptor(
            int faceIndex,
            FontFlavor flavor,
            int unitsPerEm
    ) implements TechnicalDescriptor {
        public FontDescriptor {
            Objects.requireNonNull(flavor, "flavor");
            if (faceIndex != 0) {
                throw new IllegalArgumentException("faceIndex must be 0 for single-face fonts");
            }
            if (unitsPerEm < 16 || unitsPerEm > 16384) {
                throw new IllegalArgumentException("unitsPerEm out of range");
            }
        }
    }

    enum Orientation {
        IDENTITY,
        MIRROR_HORIZONTAL,
        ROTATE_180,
        MIRROR_VERTICAL,
        TRANSPOSE,
        ROTATE_90_CW,
        TRANSVERSE,
        ROTATE_270_CW
    }

    enum ColorEncoding {
        SRGB_8BIT
    }

    enum FontFlavor {
        TRUETYPE_GLYF,
        CFF
    }
}
