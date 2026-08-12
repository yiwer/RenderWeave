package cn.hbads.renderweave.inference.eval.visual;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Repository-local diagnostic renderer for the immutable layered corpus.
 *
 * <p>This is deliberately not an evaluation result or product surface. It cannot accept image bytes,
 * artifact references, live-run identifiers, URLs, or an arbitrary output directory. The only image
 * source is the exact allowlisted corpus case, and output is confined below the repository's scratch
 * directory.</p>
 */
public final class LayeredLocalVisualDiff {
    public static final String VERSION = "renderweave-layered-local-visual-diff/1.0";
    public static final String MANIFEST_VERSION = "renderweave-layered-local-visual-diff-manifest/1.0";

    private static final String GENERATED = "LOCAL_VISUAL_DIFF_GENERATED";
    private static final Color GOLD_REGION = new Color(21, 148, 71);
    private static final Color PREDICTED_REGION = new Color(192, 38, 211);
    private static final Color GOLD_ORDER = new Color(2, 132, 199);
    private static final Color PREDICTED_ORDER = new Color(220, 38, 38);
    private static final Color GOLD_EVIDENCE = new Color(245, 158, 11);
    private static final Color PREDICTED_EVIDENCE = new Color(6, 182, 212);
    private static final Color LABEL_BACKGROUND = new Color(255, 255, 255, 218);
    private static final BasicStroke SOLID = new BasicStroke(3f, BasicStroke.CAP_SQUARE,
            BasicStroke.JOIN_MITER);
    private static final BasicStroke DASHED = new BasicStroke(3f, BasicStroke.CAP_SQUARE,
            BasicStroke.JOIN_MITER, 10f, new float[]{9f, 6f}, 0f);

    private final Path workspaceRoot;

    public LayeredLocalVisualDiff(Path workspaceRoot) {
        this.workspaceRoot = requireWorkspace(workspaceRoot);
    }

    public Result generate(
            LayeredVisualCorpus corpus,
            Request request,
            LayeredVisualPrediction prediction
    ) {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(prediction, "prediction");
        if (!corpus.corpusIdentity().equals(request.corpusIdentity())) {
            throw invalid("LOCAL_DIFF_CORPUS_NOT_ALLOWLISTED");
        }
        var evaluationCase = corpus.cases().stream()
                .filter(item -> item.caseId().equals(request.caseId()))
                .findFirst()
                .orElseThrow(() -> invalid("LOCAL_DIFF_CASE_NOT_ALLOWLISTED"));
        requireAllowedLicense(evaluationCase.annotation().sourceLicense());
        if (!prediction.caseId().equals(evaluationCase.caseId())) {
            throw invalid("LOCAL_DIFF_PREDICTION_CASE_MISMATCH");
        }
        requireZeroProviderUsage(prediction.runtime());

        var rendered = new VisualStageRasterizer().render(evaluationCase.renderCase());
        if (!("render-sha256:" + rendered.sha256()).equals(evaluationCase.renderIdentity())) {
            throw invalid("LOCAL_DIFF_RENDER_IDENTITY_DRIFT");
        }
        var diagnosticIdentity = diagnosticIdentity(evaluationCase, prediction);
        var overlay = renderOverlay(rendered, evaluationCase.annotation(), prediction);
        var imageSha256 = sha256(overlay);
        var manifest = new Manifest(
                MANIFEST_VERSION,
                "LOCAL_DIAGNOSTIC_ONLY",
                GENERATED,
                "A1",
                "human_review_pending",
                "J0",
                1,
                evaluationCase.caseId(),
                corpus.corpusIdentity(),
                evaluationCase.caseIdentity(),
                evaluationCase.annotationIdentity(),
                evaluationCase.renderIdentity(),
                diagnosticIdentity,
                "overlay.png",
                imageSha256,
                0,
                0,
                0);
        var manifestBytes = new LayeredEvaluationJsonCodec().canonicalBytes(manifest);
        var localDirectory = diagnosticDirectory(corpus.corpusIdentity(), evaluationCase.caseId(),
                diagnosticIdentity);
        writeSameOrNew(localDirectory.resolve("overlay.png"), overlay);
        writeSameOrNew(localDirectory.resolve("manifest.json"), manifestBytes);

        var receipt = new Receipt(
                GENERATED,
                1,
                evaluationCase.caseId(),
                corpus.corpusIdentity(),
                evaluationCase.caseIdentity(),
                evaluationCase.annotationIdentity(),
                evaluationCase.renderIdentity(),
                diagnosticIdentity,
                0,
                0,
                0);
        return new Result(receipt, localDirectory);
    }

    static void requireAllowedLicense(LayeredVisualAnnotation.SourceLicense license) {
        if (license != LayeredVisualAnnotation.SourceLicense.SYNTHETIC
                && license != LayeredVisualAnnotation.SourceLicense.CC0) {
            throw invalid("LOCAL_DIFF_LICENSE_NOT_ALLOWLISTED");
        }
    }

    private static void requireZeroProviderUsage(LayeredVisualPrediction.Runtime runtime) {
        if (runtime.inputTokens() != 0 || runtime.outputTokens() != 0
                || runtime.estimatedCostMicrosCny() != 0 || runtime.settledCostMicrosCny() != 0
                || runtime.providerAttempts() != 0 || runtime.providerReservations() != 0
                || runtime.externalProviderCostMicrosCny() != 0) {
            throw invalid("LOCAL_DIFF_EXTERNAL_PROVIDER_USAGE");
        }
    }

    private Path diagnosticDirectory(String corpusIdentity, String caseId, String diagnosticIdentity) {
        try {
            var scratch = ownedDirectory(workspaceRoot, ".scratch");
            var root = ownedDirectory(scratch, "layered-visual-diff");
            var corpus = ownedDirectory(root, identityDigest(corpusIdentity));
            var evaluationCase = ownedDirectory(corpus, caseId);
            return ownedDirectory(evaluationCase, identityDigest(diagnosticIdentity));
        } catch (IOException failure) {
            throw new IllegalArgumentException("LOCAL_DIFF_DESTINATION_INVALID", failure);
        }
    }

    private static Path requireWorkspace(Path value) {
        if (value == null || !value.isAbsolute()) throw invalid("LOCAL_DIFF_WORKSPACE_INVALID");
        try {
            var normalized = value.normalize();
            if (Files.isSymbolicLink(normalized)) throw invalid("LOCAL_DIFF_WORKSPACE_INVALID");
            var real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(real)
                    || !Files.isRegularFile(real.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(real.resolve("CONSTITUTION.md"), LinkOption.NOFOLLOW_LINKS)
                    || !Files.exists(real.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("LOCAL_DIFF_WORKSPACE_INVALID");
            }
            return real;
        } catch (IOException failure) {
            throw new IllegalArgumentException("LOCAL_DIFF_WORKSPACE_INVALID", failure);
        }
    }

    private static Path ownedDirectory(Path parent, String childName) throws IOException {
        if (childName == null || !childName.matches("[a-zA-Z0-9._-]{1,128}")
                || childName.equals(".") || childName.equals("..")) {
            throw invalid("LOCAL_DIFF_DESTINATION_INVALID");
        }
        var parentReal = parent.toRealPath(LinkOption.NOFOLLOW_LINKS);
        var child = parentReal.resolve(childName).normalize();
        if (!child.getParent().equals(parentReal)) throw invalid("LOCAL_DIFF_DESTINATION_INVALID");
        try {
            Files.createDirectory(child);
        } catch (FileAlreadyExistsException ignored) {
            // Idempotent local diagnostics reuse only an existing real directory at the exact path.
        }
        if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid("LOCAL_DIFF_DESTINATION_INVALID");
        }
        var real = child.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.getParent().equals(parentReal)) throw invalid("LOCAL_DIFF_DESTINATION_INVALID");
        return real;
    }

    private static void writeSameOrNew(Path path, byte[] bytes) {
        try {
            try {
                Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return;
            } catch (FileAlreadyExistsException ignored) {
                // Never overwrite a prior diagnostic. Exact deterministic replay is idempotent.
            }
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !Arrays.equals(bytes, Files.readAllBytes(path))) {
                throw invalid("LOCAL_DIFF_EXISTING_ARTIFACT_DRIFT");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("LOCAL_DIFF_WRITE_FAILED", failure);
        }
    }

    private static byte[] renderOverlay(
            VisualStageRasterizer.RenderedImage rendered,
            LayeredVisualAnnotation annotation,
            LayeredVisualPrediction prediction
    ) {
        try {
            var image = ImageIO.read(new ByteArrayInputStream(rendered.bytes()));
            if (image == null || image.getWidth() != rendered.width() || image.getHeight() != rendered.height()) {
                throw invalid("LOCAL_DIFF_SOURCE_IMAGE_INVALID");
            }
            var graphics = image.createGraphics();
            try {
                configure(graphics);
                drawLegend(graphics);
                drawRegions(graphics, annotation.regions(), rendered.width(), rendered.height(),
                        GOLD_REGION, SOLID, "G");
                drawPredictedRegions(graphics, prediction.regions(), rendered.width(), rendered.height());
                drawOrder(graphics, annotation.regions(), annotation.precedenceEdges(), rendered.width(),
                        rendered.height(), GOLD_ORDER, SOLID);
                drawPredictedOrder(graphics, prediction.regions(), prediction.precedenceEdges(), rendered.width(),
                        rendered.height());
                drawEvidence(graphics, annotation.evidence(), rendered.width(), rendered.height(),
                        GOLD_EVIDENCE, "G");
                drawPredictedEvidence(graphics, prediction.evidence(), rendered.width(), rendered.height());
            } finally {
                graphics.dispose();
            }
            try (var output = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, "png", output)) throw invalid("LOCAL_DIFF_PNG_ENCODER_UNAVAILABLE");
                return output.toByteArray();
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("LOCAL_DIFF_RENDER_FAILED", failure);
        }
    }

    private static void configure(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
    }

    private static void drawLegend(Graphics2D graphics) {
        graphics.setColor(LABEL_BACKGROUND);
        graphics.fillRect(4, 4, 370, 24);
        graphics.setColor(Color.BLACK);
        graphics.drawString("LOCAL DIAGNOSTIC  G=gold  P=prediction  human review pending", 9, 20);
    }

    private static void drawRegions(
            Graphics2D graphics,
            List<LayeredVisualAnnotation.Region> regions,
            int width,
            int height,
            Color color,
            BasicStroke stroke,
            String prefix
    ) {
        graphics.setColor(color);
        graphics.setStroke(stroke);
        for (var region : regions) {
            var shape = shape(region.geometry(), width, height);
            graphics.draw(shape);
            var bounds = region.geometry().bounds();
            drawLabel(graphics, prefix + " " + region.kind() + ":" + region.regionId(),
                    pixel(bounds.left(), width), pixel(bounds.top(), height), color);
        }
    }

    private static void drawPredictedRegions(
            Graphics2D graphics,
            List<LayeredVisualPrediction.Region> regions,
            int width,
            int height
    ) {
        graphics.setColor(PREDICTED_REGION);
        graphics.setStroke(DASHED);
        for (var region : regions) {
            graphics.draw(shape(region.geometry(), width, height));
            var bounds = region.geometry().bounds();
            drawLabel(graphics, "P " + region.kind() + ":" + region.regionId(),
                    pixel(bounds.left(), width), pixel(bounds.bottom(), height), PREDICTED_REGION);
        }
    }

    private static void drawOrder(
            Graphics2D graphics,
            List<LayeredVisualAnnotation.Region> regions,
            List<LayeredVisualAnnotation.PrecedenceEdge> edges,
            int width,
            int height,
            Color color,
            BasicStroke stroke
    ) {
        var centers = new LinkedHashMap<String, Point>();
        regions.forEach(region -> centers.put(region.regionId(), center(region.geometry(), width, height)));
        graphics.setColor(color);
        graphics.setStroke(stroke);
        for (var edge : edges) {
            drawArrow(graphics, centers.get(edge.beforeRegionId()), centers.get(edge.afterRegionId()), color);
        }
    }

    private static void drawPredictedOrder(
            Graphics2D graphics,
            List<LayeredVisualPrediction.Region> regions,
            List<LayeredVisualAnnotation.PrecedenceEdge> edges,
            int width,
            int height
    ) {
        var centers = new LinkedHashMap<String, Point>();
        regions.forEach(region -> centers.put(region.regionId(), center(region.geometry(), width, height)));
        graphics.setColor(PREDICTED_ORDER);
        graphics.setStroke(DASHED);
        for (var edge : edges) {
            drawArrow(graphics, centers.get(edge.beforeRegionId()), centers.get(edge.afterRegionId()),
                    PREDICTED_ORDER);
        }
    }

    private static void drawArrow(Graphics2D graphics, Point from, Point to, Color color) {
        if (from == null || to == null || from.equals(to)) return;
        graphics.setColor(color);
        graphics.drawLine(from.x(), from.y(), to.x(), to.y());
        var angle = Math.atan2(to.y() - from.y(), to.x() - from.x());
        var size = 8;
        var arrow = new Polygon();
        arrow.addPoint(to.x(), to.y());
        arrow.addPoint((int) Math.round(to.x() - size * Math.cos(angle - Math.PI / 6)),
                (int) Math.round(to.y() - size * Math.sin(angle - Math.PI / 6)));
        arrow.addPoint((int) Math.round(to.x() - size * Math.cos(angle + Math.PI / 6)),
                (int) Math.round(to.y() - size * Math.sin(angle + Math.PI / 6)));
        graphics.fillPolygon(arrow);
    }

    private static void drawEvidence(
            Graphics2D graphics,
            List<LayeredVisualAnnotation.Evidence> evidence,
            int width,
            int height,
            Color color,
            String prefix
    ) {
        for (var item : evidence) {
            drawEvidenceOwner(graphics, item.ownerKind(), item.ownerId(), item.geometry(), width, height,
                    color, prefix);
        }
    }

    private static void drawPredictedEvidence(
            Graphics2D graphics,
            List<LayeredVisualPrediction.Evidence> evidence,
            int width,
            int height
    ) {
        for (var item : evidence) {
            drawEvidenceOwner(graphics, item.ownerKind(), item.ownerId(), item.geometry(), width, height,
                    PREDICTED_EVIDENCE, "P");
        }
    }

    private static void drawEvidenceOwner(
            Graphics2D graphics,
            LayeredVisualAnnotation.OwnerKind ownerKind,
            String ownerId,
            LayeredVisualAnnotation.Geometry geometry,
            int width,
            int height,
            Color color,
            String prefix
    ) {
        var point = center(geometry, width, height);
        graphics.setColor(color);
        graphics.fill(new Ellipse2D.Double(point.x() - 4, point.y() - 4, 8, 8));
        drawLabel(graphics, prefix + " " + ownerKind + ":" + ownerId, point.x() + 5, point.y() - 5,
                color);
    }

    private static void drawLabel(Graphics2D graphics, String label, int x, int y, Color color) {
        var metrics = graphics.getFontMetrics();
        var left = Math.max(0, x);
        var baseline = Math.max(metrics.getAscent(), y);
        graphics.setColor(LABEL_BACKGROUND);
        graphics.fillRect(left, baseline - metrics.getAscent(), metrics.stringWidth(label) + 4,
                metrics.getHeight());
        graphics.setColor(color);
        graphics.drawString(label, left + 2, baseline);
    }

    private static Shape shape(LayeredVisualAnnotation.Geometry geometry, int width, int height) {
        if (geometry.box() != null) {
            var box = geometry.box();
            var left = pixel(box.left(), width);
            var top = pixel(box.top(), height);
            var right = pixel(box.right(), width);
            var bottom = pixel(box.bottom(), height);
            return new Rectangle2D.Double(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
        }
        var path = new Path2D.Double();
        for (var index = 0; index < geometry.polygon().size(); index++) {
            var point = geometry.polygon().get(index);
            var x = pixel(point.x(), width);
            var y = pixel(point.y(), height);
            if (index == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.closePath();
        return path;
    }

    private static Point center(LayeredVisualAnnotation.Geometry geometry, int width, int height) {
        var box = geometry.bounds();
        return new Point(pixel((box.left() + box.right()) / 2, width),
                pixel((box.top() + box.bottom()) / 2, height));
    }

    private static int pixel(int normalized, int extent) {
        return Math.min(extent - 1, Math.toIntExact((long) normalized * extent / 10_000));
    }

    private static String diagnosticIdentity(
            LayeredVisualCorpus.Case evaluationCase,
            LayeredVisualPrediction prediction
    ) {
        var parts = new ArrayList<String>();
        parts.add(VERSION);
        parts.add(evaluationCase.caseIdentity());
        parts.add(evaluationCase.annotationIdentity());
        prediction.regions().stream()
                .sorted(Comparator.comparing(LayeredVisualPrediction.Region::regionId))
                .forEach(item -> parts.add("region:" + item.regionId() + ":" + item.kind() + ":"
                        + geometryKey(item.geometry()) + ":" + item.confidenceBps()));
        prediction.precedenceEdges().stream()
                .map(item -> item.beforeRegionId() + ">" + item.afterRegionId())
                .sorted()
                .forEach(item -> parts.add("order:" + item));
        prediction.evidence().stream()
                .map(item -> item.ownerKind() + ":" + item.ownerId() + ":" + geometryKey(item.geometry()))
                .sorted()
                .forEach(item -> parts.add("evidence:" + item));
        return VERSION + ":" + sha256(parts);
    }

    private static String geometryKey(LayeredVisualAnnotation.Geometry geometry) {
        if (geometry.box() != null) {
            var box = geometry.box();
            return "box:" + box.left() + "," + box.top() + "," + box.right() + "," + box.bottom();
        }
        return "polygon:" + geometry.polygon().stream()
                .map(point -> point.x() + "," + point.y())
                .reduce((left, right) -> left + ";" + right)
                .orElseThrow();
    }

    private static String identityDigest(String identity) {
        var split = identity.lastIndexOf(':');
        if (split < 0 || split == identity.length() - 1) throw invalid("LOCAL_DIFF_IDENTITY_INVALID");
        var digest = identity.substring(split + 1);
        if (!digest.matches("[0-9a-f]{64}")) throw invalid("LOCAL_DIFF_IDENTITY_INVALID");
        return digest;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    private static String sha256(List<String> values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                var bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public record Request(String corpusIdentity, String caseId) {
        public Request {
            if (corpusIdentity == null
                    || !corpusIdentity.matches("renderweave-visual-stage-corpus/2\\.0:[0-9a-f]{64}")) {
                throw invalid("LOCAL_DIFF_CORPUS_IDENTITY_INVALID");
            }
            try {
                caseId = LayeredVisualAnnotation.requireId(caseId, "LOCAL_DIFF_CASE_ID_INVALID");
            } catch (IllegalArgumentException failure) {
                throw invalid("LOCAL_DIFF_CASE_ID_INVALID");
            }
        }
    }

    public record Receipt(
            String code,
            int generatedCount,
            String caseId,
            String corpusIdentity,
            String caseIdentity,
            String annotationIdentity,
            String renderIdentity,
            String diagnosticIdentity,
            int providerAttempts,
            int providerReservations,
            long externalProviderCostMicrosCny
    ) {
        public Receipt {
            if (!GENERATED.equals(code) || generatedCount != 1
                    || providerAttempts != 0 || providerReservations != 0
                    || externalProviderCostMicrosCny != 0) {
                throw invalid("LOCAL_DIFF_RECEIPT_INVALID");
            }
        }
    }

    public record Result(Receipt receipt, Path localDirectory) {
        public Result {
            Objects.requireNonNull(receipt, "receipt");
            Objects.requireNonNull(localDirectory, "localDirectory");
        }

        @Override
        public String toString() {
            return "Result[receipt=" + receipt + ", localDirectory=<local-diagnostic>]";
        }
    }

    private record Manifest(
            String manifestVersion,
            String classification,
            String code,
            String automatedEvidenceLevel,
            String humanReviewStatus,
            String judgement,
            int generatedCount,
            String caseId,
            String corpusIdentity,
            String caseIdentity,
            String annotationIdentity,
            String renderIdentity,
            String diagnosticIdentity,
            String imageFile,
            String imageSha256,
            int providerAttempts,
            int providerReservations,
            long externalProviderCostMicrosCny
    ) { }

    private record Point(int x, int y) { }
}
