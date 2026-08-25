package cn.hbads.renderweave.rendering.api;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RenderOutputContractTest {

    @Test
    void pngCarriesTheCompleteVerifiedPublicResultMetadata() {
        var source = new byte[] { 1, 2, 3 };

        var output = png(source);

        source[0] = 9;
        assertArrayEquals(new byte[] { 1, 2, 3 }, output.sealedImageBytes());
        assertEquals("renderweave-render-result/1.0", output.contractVersion());
        assertEquals("renderweave-renderer/1.0", output.rendererProfile());
        assertEquals("renderweave-render/1.0", output.dslVersion());
        assertEquals("renderweave-layout/1.0", output.layoutProfile());
        assertEquals("renderweave-output-png/1.0", output.outputProfile());
        assertEquals("PNG", output.format());
        assertEquals("image/png", output.mediaType());
        assertEquals(96, output.dpi());
        assertEquals(OptionalInt.empty(), output.quality());
        assertEquals(
                new Evaluator.OutputSelection.Png(96),
                output.outputSelection());
    }

    @Test
    void digestLengthAndClosedPngJpegShapesAreConstructorInvariants() {
        var bytes = new byte[] { 1, 2, 3 };

        assertThrows(IllegalArgumentException.class, () -> new RenderOutput(
                bytes,
                "renderweave-render-result/1.0",
                "renderweave-renderer/1.0",
                "renderweave-render/1.0",
                "renderweave-layout/1.0",
                "renderweave-output-png/1.0",
                "PNG",
                "image/png",
                10,
                20,
                96,
                OptionalInt.of(90),
                bytes.length,
                sha256(bytes)));
        assertThrows(IllegalArgumentException.class, () -> new RenderOutput(
                bytes,
                "renderweave-render-result/1.0",
                "renderweave-renderer/1.0",
                "renderweave-render/1.0",
                "renderweave-layout/1.0",
                "renderweave-output-jpeg/1.0",
                "JPEG",
                "image/jpeg",
                10,
                20,
                96,
                OptionalInt.empty(),
                bytes.length,
                sha256(bytes)));
        assertThrows(IllegalArgumentException.class, () -> new RenderOutput(
                bytes,
                "renderweave-render-result/1.0",
                "renderweave-renderer/1.0",
                "renderweave-render/1.0",
                "renderweave-layout/1.0",
                "renderweave-output-png/1.0",
                "PNG",
                "image/png",
                10,
                20,
                96,
                OptionalInt.empty(),
                bytes.length,
                "0".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new Evaluator.OutputSelection.Png(601));
        assertThrows(IllegalArgumentException.class,
                () -> new Evaluator.OutputSelection.Jpeg(601, 90));
    }

    private static RenderOutput png(byte[] bytes) {
        return new RenderOutput(
                bytes,
                "renderweave-render-result/1.0",
                "renderweave-renderer/1.0",
                "renderweave-render/1.0",
                "renderweave-layout/1.0",
                "renderweave-output-png/1.0",
                "PNG",
                "image/png",
                10,
                20,
                96,
                OptionalInt.empty(),
                bytes.length,
                sha256(bytes));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
