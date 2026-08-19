package cn.hbads.renderweave.asset.spi;

import java.util.Objects;

/**
 * Scope-local, SHA-256 content-addressed immutable byte store. The S3-protocol
 * adapter (production OSS, local/Testcontainers MinIO) hides behind this seam.
 */
public interface AssetBlobPersistence {

    StoreOutcome store(String ownerScope, String sha256, byte[] bytes);

    LoadOutcome load(String ownerScope, String sha256);

    sealed interface StoreOutcome permits Stored, StoreUnavailable {
    }

    record Stored(boolean created) implements StoreOutcome {
    }

    record StoreUnavailable() implements StoreOutcome {
    }

    sealed interface LoadOutcome permits Loaded, LoadNotFound, LoadUnavailable {
    }

    final class Loaded implements LoadOutcome {
        private final byte[] bytes;

        public Loaded(byte[] bytes) {
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record LoadNotFound() implements LoadOutcome {
    }

    record LoadUnavailable() implements LoadOutcome {
    }
}
