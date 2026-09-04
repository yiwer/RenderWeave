package cn.hbads.renderweave.inference;

interface EncryptedCiphertextStore {
    boolean write(String locator, byte[] ciphertext, String expectedSha256);

    byte[] read(String locator);

    void delete(String locator);

    boolean exists(String locator);
}
