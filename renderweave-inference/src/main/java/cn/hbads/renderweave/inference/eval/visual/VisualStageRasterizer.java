package cn.hbads.renderweave.inference.eval.visual;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Deterministic, payload-safe rasterizer for repository-owned visual evaluation scenes. */
public final class VisualStageRasterizer {
    private static final String FONT_RESOURCE = "visual-eval/v1/RenderWeaveVisualEval.ttf";
    private static final Font BASE_FONT = loadFont();

    public RenderedImage render(VisualStageCorpus.EvaluationCase evaluationCase) {
        Objects.requireNonNull(evaluationCase, "evaluationCase");
        var width = evaluationCase.width();
        var height = evaluationCase.height();
        var palette = Palette.forStyle(evaluationCase.style(), evaluationCase.contrastBps());
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            configure(graphics);
            graphics.setColor(palette.background());
            graphics.fillRect(0, 0, width, height);
            drawTexture(graphics, evaluationCase, palette);
            drawScene(graphics, evaluationCase, palette);
        } finally {
            graphics.dispose();
        }
        var bytes = encodePng(image);
        return new RenderedImage(bytes, sha256(bytes), width, height, "image/png");
    }

    private static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
    }

    private static void drawTexture(
            Graphics2D graphics,
            VisualStageCorpus.EvaluationCase evaluationCase,
            Palette palette
    ) {
        var random = new Random(evaluationCase.noiseSeed());
        graphics.setColor(palette.texture());
        for (var index = 0; index < evaluationCase.distractorCount() * 18; index++) {
            var x = random.nextInt(evaluationCase.width());
            var y = random.nextInt(evaluationCase.height());
            var size = 1 + random.nextInt(3);
            graphics.fillRect(x, y, size, size);
        }
        graphics.setStroke(new BasicStroke(1f));
        for (var index = 0; index < evaluationCase.distractorCount(); index++) {
            var x = random.nextInt(Math.max(1, evaluationCase.width() - 80));
            var y = random.nextInt(Math.max(1, evaluationCase.height() - 30));
            graphics.drawLine(x, y, Math.min(evaluationCase.width() - 1, x + 40 + random.nextInt(80)), y);
        }
    }

    private static void drawScene(
            Graphics2D graphics,
            VisualStageCorpus.EvaluationCase evaluationCase,
            Palette palette
    ) {
        var scene = evaluationCase.scene();
        var width = evaluationCase.width();
        var height = evaluationCase.height();
        var elements = scene.elements().stream()
                .sorted(Comparator.comparingInt(item -> item.kind() == VisualStageCorpus.ElementKind.GROUP ? 0 : 1))
                .toList();
        for (var element : elements) {
            var rectangle = pixels(element.box(), width, height);
            if (element.kind() == VisualStageCorpus.ElementKind.GROUP) {
                drawGroup(graphics, element, rectangle, palette);
            } else {
                drawSlot(graphics, element, rectangle, palette);
            }
        }
        var titleSize = Math.max(18, Math.min(width, height) / 36);
        drawText(graphics, scene.title(), width / 2, Math.max(titleSize + 8, height / 35),
                width - 40, titleSize, palette.title(), true, TextAnchor.CENTER);
    }

    private static void drawGroup(
            Graphics2D graphics,
            VisualStageCorpus.Element element,
            PixelBox box,
            Palette palette
    ) {
        graphics.setColor(palette.groupFill());
        graphics.fillRoundRect(box.left(), box.top(), box.width(), box.height(), 18, 18);
        graphics.setColor(palette.groupStroke());
        graphics.setStroke(new BasicStroke(3f));
        graphics.drawRoundRect(box.left(), box.top(), box.width(), box.height(), 18, 18);
        var labelSize = Math.max(12, Math.min(26, box.height() / 10));
        drawText(graphics, element.displayName(), box.left() + 12, box.top() + labelSize + 8,
                Math.max(20, box.width() - 24), labelSize, palette.groupLabel(), true, TextAnchor.LEFT);
        if (element.multiplicity() == VisualStageCorpus.Multiplicity.MANY) {
            graphics.setColor(palette.rowDivider());
            graphics.setStroke(new BasicStroke(1f));
            for (var row = 1; row <= 3; row++) {
                var y = box.top() + Math.floorDiv(box.height() * row, 4);
                graphics.drawLine(box.left() + 10, y, box.right() - 10, y);
            }
        }
    }

    private static void drawSlot(
            Graphics2D graphics,
            VisualStageCorpus.Element element,
            PixelBox box,
            Palette palette
    ) {
        graphics.setColor(palette.slotFill());
        graphics.fillRoundRect(box.left(), box.top(), box.width(), box.height(), 12, 12);
        graphics.setColor(palette.slotStroke());
        graphics.setStroke(new BasicStroke(1.5f));
        graphics.drawRoundRect(box.left(), box.top(), box.width(), box.height(), 12, 12);
        var labelSize = Math.max(10, Math.min(22, box.height() / 4));
        var valueSize = Math.max(11, Math.min(30, box.height() / 3));
        drawText(graphics, element.displayName(), box.left() + 10, box.top() + labelSize + 6,
                Math.max(20, box.width() - 20), labelSize, palette.slotLabel(), false, TextAnchor.LEFT);
        drawText(graphics, element.sampleValue(), box.left() + 10,
                Math.min(box.bottom() - 7, box.top() + labelSize + valueSize + 15),
                Math.max(20, box.width() - 20), valueSize, palette.slotValue(), true, TextAnchor.LEFT);
    }

    private static void drawText(
            Graphics2D graphics,
            String value,
            int anchorX,
            int baselineY,
            int maximumWidth,
            int requestedSize,
            Color color,
            boolean bold,
            TextAnchor anchor
    ) {
        var font = BASE_FONT.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) requestedSize);
        var size = requestedSize;
        var context = new FontRenderContext(new AffineTransform(), false, false);
        while (size > 8 && font.getStringBounds(value, context).getWidth() > maximumWidth) {
            size--;
            font = BASE_FONT.deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size);
        }
        var vector = font.createGlyphVector(context, value);
        var bounds = vector.getVisualBounds();
        var x = anchor == TextAnchor.CENTER
                ? anchorX - (int) Math.round(bounds.getWidth() / 2d)
                : anchorX;
        Shape outline = vector.getOutline(x, baselineY);
        graphics.setColor(color);
        graphics.fill(outline);
    }

    private static PixelBox pixels(VisualStageCorpus.Box box, int width, int height) {
        var left = scale(box.left(), width);
        var top = scale(box.top(), height);
        var right = Math.max(left + 1, scale(box.right(), width));
        var bottom = Math.max(top + 1, scale(box.bottom(), height));
        return new PixelBox(left, top, right, bottom);
    }

    private static int scale(int basisPoints, int dimension) {
        return Math.floorDiv(Math.multiplyExact(basisPoints, dimension), 10_000);
    }

    private static byte[] encodePng(BufferedImage image) {
        var writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) throw new IllegalStateException("PNG encoder is unavailable");
        var output = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(output)) {
            var writer = writers.next();
            try {
                writer.setOutput(stream);
                writer.write(image);
                stream.flush();
                return output.toByteArray();
            } finally {
                writer.dispose();
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Visual evaluation image could not be encoded", failure);
        }
    }

    private static Font loadFont() {
        try (var input = VisualStageRasterizer.class.getClassLoader().getResourceAsStream(FONT_RESOURCE)) {
            if (input == null) throw new IllegalStateException("Visual evaluation font is missing");
            return Font.createFont(Font.TRUETYPE_FONT, input);
        } catch (IOException | FontFormatException failure) {
            throw new IllegalStateException("Visual evaluation font cannot be loaded", failure);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record RenderedImage(byte[] bytes, String sha256, int width, int height, String mediaType) {
        public RenderedImage {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            if (bytes.length == 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")
                    || width < 1 || height < 1 || !"image/png".equals(mediaType)) {
                throw new IllegalArgumentException("Rendered visual evaluation image is invalid");
            }
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record PixelBox(int left, int top, int right, int bottom) {
        int width() { return right - left; }
        int height() { return bottom - top; }
    }

    private enum TextAnchor { LEFT, CENTER }

    private record Palette(
            Color background,
            Color texture,
            Color title,
            Color groupFill,
            Color groupStroke,
            Color groupLabel,
            Color rowDivider,
            Color slotFill,
            Color slotStroke,
            Color slotLabel,
            Color slotValue
    ) {
        static Palette forStyle(VisualStageCorpus.Style style, int contrastBps) {
            var base = switch (style) {
                case WIDE_LIGHT -> light(new Color(0xf4f7fb), new Color(0x164e78), new Color(0xe4f1fb));
                case PORTRAIT_DARK -> dark();
                case COMPACT_DENSE -> light(new Color(0xfff8ed), new Color(0x8d3f23), new Color(0xffeadb));
                case LOW_CONTRAST -> light(new Color(0xf1f0ed), new Color(0x696762), new Color(0xe8e6e1));
                case HOLDOUT_NOISY -> light(new Color(0xeaf4ef), new Color(0x145f46), new Color(0xd9eee5));
            };
            if (contrastBps >= 8_000) return base;
            var factor = Math.max(0.25, contrastBps / 10_000d);
            return new Palette(
                    base.background(), blend(base.texture(), base.background(), factor),
                    blend(base.title(), base.background(), factor), base.groupFill(),
                    blend(base.groupStroke(), base.groupFill(), factor),
                    blend(base.groupLabel(), base.groupFill(), factor),
                    blend(base.rowDivider(), base.groupFill(), factor), base.slotFill(),
                    blend(base.slotStroke(), base.slotFill(), factor),
                    blend(base.slotLabel(), base.slotFill(), factor),
                    blend(base.slotValue(), base.slotFill(), factor)
            );
        }

        private static Palette light(Color background, Color accent, Color groupFill) {
            return new Palette(
                    background, new Color(0xc9d2da), accent, groupFill, accent, accent,
                    new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 90),
                    new Color(0xffffff), new Color(0x9ba9b5), new Color(0x5e6b75), new Color(0x17212b)
            );
        }

        private static Palette dark() {
            return new Palette(
                    new Color(0x13202b), new Color(0x314352), new Color(0xd8f0ff),
                    new Color(0x1d3342), new Color(0x58b6d9), new Color(0x9cdcf2),
                    new Color(0x426274), new Color(0x233d4c), new Color(0x678a9a),
                    new Color(0xa9cbd8), new Color(0xf4fbff)
            );
        }

        private static Color blend(Color foreground, Color background, double factor) {
            return new Color(
                    channel(foreground.getRed(), background.getRed(), factor),
                    channel(foreground.getGreen(), background.getGreen(), factor),
                    channel(foreground.getBlue(), background.getBlue(), factor)
            );
        }

        private static int channel(int foreground, int background, double factor) {
            return Math.max(0, Math.min(255,
                    (int) Math.round(background + (foreground - background) * factor)));
        }
    }
}
