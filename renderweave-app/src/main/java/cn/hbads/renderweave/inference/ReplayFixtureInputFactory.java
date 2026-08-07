package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.InferenceInput;
import cn.hbads.renderweave.inference.replay.ReplayCase;
import cn.hbads.renderweave.inference.replay.ReplayCorpus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Creates synthetic, self-contained inputs for the zero-network replay profile. */
@Component
final class ReplayFixtureInputFactory {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 800;

    private final ReplayCorpus corpus = new ReplayCorpus();

    InferenceInput create(String fixtureId, boolean externalTransferConfirmed) {
        var fixture = corpus.require(fixtureId);
        var images = new ArrayList<InferenceInput.BinaryInput>();
        for (var ordinal = 0; ordinal < fixture.imageCount(); ordinal++) {
            images.add(new InferenceInput.BinaryInput(
                    fixtureId + "-" + ordinal + ".png",
                    "image/png",
                    render(fixture, ordinal)
            ));
        }
        var samples = fixture.jsonSamples().stream()
                .map(sample -> new InferenceInput.BinaryInput(
                        fixtureId + ".json", "application/json",
                        sample.getBytes(StandardCharsets.UTF_8)
                ))
                .toList();
        return new InferenceInput(
                fixture.mode(), "replay-v1", fixture.fixtureId(), externalTransferConfirmed,
                images, samples
        );
    }

    List<ReplayCase> fixtures() {
        return corpus.cases();
    }

    private static byte[] render(ReplayCase fixture, int imageOrdinal) {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(0xf7f2e9));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            graphics.setColor(new Color(0x25211d));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            graphics.drawString("RenderWeave · synthetic replay", 54, 64);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            graphics.setColor(new Color(0x6f665d));
            graphics.drawString(fixture.fixtureId() + " · image " + (imageOrdinal + 1), 56, 94);

            for (var schema : fixture.visualSchemas()) {
                if (schema.imageOrdinal() != imageOrdinal) continue;
                drawBox(graphics, schema.boundingBox(), new Color(0xa9583e), schema.displayName(), 2.4f);
                for (var field : schema.fields()) {
                    if (field.imageOrdinal() != imageOrdinal) continue;
                    drawBox(graphics, field.boundingBox(), new Color(0xcc785c),
                            field.fieldKey() + " · " + field.type().name(), 1.5f);
                }
            }
        } finally {
            graphics.dispose();
        }
        return encodePng(image);
    }

    private static void drawBox(
            java.awt.Graphics2D graphics,
            List<Integer> box,
            Color color,
            String label,
            float stroke
    ) {
        if (box.size() != 4) return;
        var left = box.get(0) * WIDTH / 10_000;
        var top = box.get(1) * HEIGHT / 10_000;
        var right = box.get(2) * WIDTH / 10_000;
        var bottom = box.get(3) * HEIGHT / 10_000;
        graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 28));
        graphics.fillRect(left, top, right - left, bottom - top);
        graphics.setColor(color);
        graphics.setStroke(new BasicStroke(stroke));
        graphics.drawRect(left, top, right - left, bottom - top);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        graphics.drawString(label, left + 8, Math.max(18, top + 20));
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
        } catch (IOException exception) {
            throw new IllegalStateException("Synthetic replay image could not be encoded", exception);
        }
    }
}
