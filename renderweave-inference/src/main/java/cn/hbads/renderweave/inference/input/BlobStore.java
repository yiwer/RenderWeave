package cn.hbads.renderweave.inference.input;

public interface BlobStore {
    WriteReceipt write(String artifactId, byte[] bytes);

    byte[] read(String locator);

    void delete(String locator);

    record WriteReceipt(String locator, boolean created) {
        public WriteReceipt {
            if (locator == null || locator.isBlank()) throw new IllegalArgumentException("locator is required");
        }
    }
}
