package cn.hbads.renderweave.inference.input;

public record NormalizedImage(byte[] pngBytes, int width, int height) {
    public NormalizedImage {
        pngBytes = pngBytes.clone();
        if (width < 1 || height < 1) throw new IllegalArgumentException("Image dimensions must be positive");
    }

    @Override
    public byte[] pngBytes() {
        return pngBytes.clone();
    }
}
