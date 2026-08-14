package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.provider.ProviderImage;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic, in-memory overview/tile/crop planner with reversible evidence transforms. */
final class MultiScaleVisualViewPlanner {
    static final String VERSION = "renderweave-visual-view-plan/1.0";
    static final int MAX_VIEWS = 10;
    static final int MAX_TOTAL_VIEW_BYTES = 30 * 1024 * 1024;
    static final int OVERVIEW_LONG_EDGE = 768;
    static final int DETAIL_LONG_EDGE = 1_400;

    VisualViewPlan plan(List<VisualSourceImage> sources, List<VisualTargetCrop> targets) {
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        if (sources.isEmpty() || sources.size() > MAX_VIEWS) {
            throw new IllegalArgumentException("Visual view planning requires 1..10 source images");
        }
        for (var target : targets) {
            if (target.sourceOrdinal() < 0 || target.sourceOrdinal() >= sources.size()) {
                throw new IllegalArgumentException("Targeted crop references an unknown source ordinal");
            }
        }

        var candidates = new ArrayList<List<VisualView>>();
        var overviews = new ArrayList<VisualView>();
        var targeted = new ArrayList<VisualView>();
        for (var sourceOrdinal = 0; sourceOrdinal < sources.size(); sourceOrdinal++) {
            var source = sources.get(sourceOrdinal);
            var decoded = decode(source);
            overviews.add(render(
                    sourceOrdinal, source, decoded, VisualViewKind.OVERVIEW, 0,
                    new PixelCrop(0, 0, source.width(), source.height()), OVERVIEW_LONG_EDGE
            ));
            var expectedSourceOrdinal = sourceOrdinal;
            var sourceTargets = targets.stream().filter(item -> item.sourceOrdinal() == expectedSourceOrdinal)
                    .sorted(Comparator.comparing(VisualTargetCrop::boundingBox,
                            Comparator.comparingInt(CandidateBoundingBox::top)
                                    .thenComparingInt(CandidateBoundingBox::left)
                                    .thenComparingInt(CandidateBoundingBox::bottom)
                                    .thenComparingInt(CandidateBoundingBox::right)))
                    .toList();
            for (var index = 0; index < sourceTargets.size(); index++) {
                targeted.add(render(
                        sourceOrdinal, source, decoded, VisualViewKind.TARGETED_CROP, index,
                        pixels(sourceTargets.get(index).boundingBox(), source.width(), source.height()),
                        DETAIL_LONG_EDGE
                ));
            }
            candidates.add(tileViews(sourceOrdinal, source, decoded));
        }

        var selected = new ArrayList<VisualView>();
        var totalBytes = 0;
        for (var overview : overviews) {
            totalBytes = addRequired(selected, overview, totalBytes);
        }
        for (var target : targeted) {
            if (selected.size() == MAX_VIEWS) break;
            totalBytes = addIfWithinBudget(selected, target, totalBytes);
        }
        for (var round = 0; selected.size() < MAX_VIEWS; round++) {
            var added = false;
            var hasMore = false;
            for (var sourceTiles : candidates) {
                hasMore |= round + 1 < sourceTiles.size();
                if (round >= sourceTiles.size() || selected.size() == MAX_VIEWS) continue;
                var before = selected.size();
                totalBytes = addIfWithinBudget(selected, sourceTiles.get(round), totalBytes);
                added |= selected.size() > before;
            }
            if (!added && !hasMore) break;
        }
        return new VisualViewPlan(VERSION, selected);
    }

    private static int addRequired(List<VisualView> selected, VisualView view, int totalBytes) {
        var next = Math.addExact(totalBytes, view.providerImage().bytes().length);
        if (next > MAX_TOTAL_VIEW_BYTES) {
            throw new IllegalArgumentException("Required visual overviews exceed the aggregate byte boundary");
        }
        selected.add(view);
        return next;
    }

    private static int addIfWithinBudget(List<VisualView> selected, VisualView view, int totalBytes) {
        var next = Math.addExact(totalBytes, view.providerImage().bytes().length);
        if (next <= MAX_TOTAL_VIEW_BYTES) {
            selected.add(view);
            return next;
        }
        return totalBytes;
    }

    private static List<VisualView> tileViews(
            int sourceOrdinal,
            VisualSourceImage source,
            BufferedImage decoded
    ) {
        var columns = Math.ceilDiv(source.width(), DETAIL_LONG_EDGE);
        var rows = Math.ceilDiv(source.height(), DETAIL_LONG_EDGE);
        if (columns == 1 && rows == 1) return List.of();
        var result = new ArrayList<VisualView>();
        var ordinal = 0;
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < columns; column++) {
                var crop = new PixelCrop(
                        column * source.width() / columns,
                        row * source.height() / rows,
                        (column + 1) * source.width() / columns,
                        (row + 1) * source.height() / rows
                );
                result.add(render(
                        sourceOrdinal, source, decoded, VisualViewKind.TILE, ordinal++,
                        crop, DETAIL_LONG_EDGE
                ));
            }
        }
        return List.copyOf(result);
    }

    private static VisualView render(
            int sourceOrdinal,
            VisualSourceImage source,
            BufferedImage decoded,
            VisualViewKind kind,
            int kindOrdinal,
            PixelCrop crop,
            int maximumLongEdge
    ) {
        var width = crop.right() - crop.left();
        var height = crop.bottom() - crop.top();
        var scale = Math.min(1.0, maximumLongEdge / (double) Math.max(width, height));
        var outputWidth = Math.max(1, (int) Math.floor(width * scale));
        var outputHeight = Math.max(1, (int) Math.floor(height * scale));
        var alpha = decoded.getColorModel().hasAlpha();
        var output = new BufferedImage(
                outputWidth, outputHeight,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB
        );
        var graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(
                    decoded, 0, 0, outputWidth, outputHeight,
                    crop.left(), crop.top(), crop.right(), crop.bottom(), null
            );
        } finally {
            graphics.dispose();
        }
        var bytes = png(output);
        var providerId = sha256(bytes);
        var viewId = "view-%02d-%s-%02d".formatted(
                sourceOrdinal, kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), kindOrdinal
        );
        var descriptor = new VisualViewDescriptor(
                viewId, source.artifactId(), sourceOrdinal, kind,
                canonical(crop, source.width(), source.height()), outputWidth, outputHeight
        );
        return new VisualView(
                descriptor,
                new ProviderImage(providerId, "image/png", bytes, outputWidth, outputHeight),
                source.width(), source.height(), crop
        );
    }

    private static BufferedImage decode(VisualSourceImage source) {
        try {
            var decoded = ImageIO.read(new ByteArrayInputStream(source.bytes()));
            if (decoded == null || decoded.getWidth() != source.width()
                    || decoded.getHeight() != source.height()) {
                throw new IllegalArgumentException("Normalized visual source dimensions are inconsistent");
            }
            return decoded;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Normalized visual source cannot be decoded", failure);
        }
    }

    private static byte[] png(BufferedImage value) {
        try {
            var output = new ByteArrayOutputStream();
            try (var stream = new MemoryCacheImageOutputStream(output)) {
                var writers = ImageIO.getImageWritersByFormatName("png");
                if (!writers.hasNext()) throw new IOException("PNG writer is unavailable");
                var writer = writers.next();
                try {
                    writer.setOutput(stream);
                    writer.write(value);
                    stream.flush();
                } finally {
                    writer.dispose();
                }
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Visual view cannot be encoded", failure);
        }
    }

    private static PixelCrop pixels(CandidateBoundingBox box, int width, int height) {
        var left = (int) Math.floorDiv((long) box.left() * width, 10_000L);
        var top = (int) Math.floorDiv((long) box.top() * height, 10_000L);
        var right = (int) Math.ceilDiv((long) box.right() * width, 10_000L);
        var bottom = (int) Math.ceilDiv((long) box.bottom() * height, 10_000L);
        return new PixelCrop(
                Math.clamp(left, 0, width - 1), Math.clamp(top, 0, height - 1),
                Math.clamp(right, 1, width), Math.clamp(bottom, 1, height)
        );
    }

    private static CandidateBoundingBox canonical(PixelCrop crop, int width, int height) {
        return new CandidateBoundingBox(
                (int) Math.floorDiv((long) crop.left() * 10_000L, width),
                (int) Math.floorDiv((long) crop.top() * 10_000L, height),
                (int) Math.ceilDiv((long) crop.right() * 10_000L, width),
                (int) Math.ceilDiv((long) crop.bottom() * 10_000L, height)
        );
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    record PixelCrop(int left, int top, int right, int bottom) {
        PixelCrop {
            if (left < 0 || top < 0 || left >= right || top >= bottom) {
                throw new IllegalArgumentException("Visual source crop is invalid");
            }
        }
    }
}

record VisualSourceImage(String artifactId, byte[] bytes, int width, int height) {
    VisualSourceImage {
        if (artifactId == null || !artifactId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Visual source artifact id is invalid");
        }
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
        if (bytes.length == 0 || width < 1 || height < 1) {
            throw new IllegalArgumentException("Visual source image is empty");
        }
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}

record VisualTargetCrop(int sourceOrdinal, CandidateBoundingBox boundingBox) {
    VisualTargetCrop {
        if (sourceOrdinal < 0) throw new IllegalArgumentException("sourceOrdinal must not be negative");
        Objects.requireNonNull(boundingBox, "boundingBox");
    }
}

enum VisualViewKind { OVERVIEW, TILE, TARGETED_CROP }

record VisualViewDescriptor(
        String viewId,
        String sourceArtifactId,
        int sourceOrdinal,
        VisualViewKind kind,
        CandidateBoundingBox sourceBoundingBox,
        int width,
        int height
) {
    VisualViewDescriptor {
        VisualAnalysisValidation.localId(viewId, "viewId");
        if (sourceArtifactId == null || !sourceArtifactId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Visual view source artifact id is invalid");
        }
        if (sourceOrdinal < 0 || sourceOrdinal >= MultiScaleVisualViewPlanner.MAX_VIEWS
                || width < 1 || height < 1) {
            throw new IllegalArgumentException("Visual view descriptor bounds are invalid");
        }
        Objects.requireNonNull(kind, "kind");
        sourceBoundingBox = VisualAnalysisValidation.canonicalBox(
                sourceBoundingBox, "sourceBoundingBox"
        );
    }
}

record VisualViewEvidence(String viewId, CandidateBoundingBox boundingBox) {
    VisualViewEvidence {
        VisualAnalysisValidation.localId(viewId, "viewId");
        boundingBox = VisualAnalysisValidation.canonicalBox(boundingBox, "view evidence");
    }
}

final class VisualViewPlan {
    private final String planVersion;
    private final List<VisualView> views;
    private final Map<String, VisualView> byId;

    VisualViewPlan(String planVersion, List<VisualView> views) {
        if (!MultiScaleVisualViewPlanner.VERSION.equals(planVersion)) {
            throw new IllegalArgumentException("Unsupported visual view plan");
        }
        this.planVersion = planVersion;
        this.views = List.copyOf(Objects.requireNonNull(views, "views"));
        if (this.views.isEmpty() || this.views.size() > MultiScaleVisualViewPlanner.MAX_VIEWS) {
            throw new IllegalArgumentException("Visual view plan size is invalid");
        }
        var index = new HashMap<String, VisualView>();
        var totalBytes = 0L;
        for (var view : this.views) {
            if (index.putIfAbsent(view.descriptor().viewId(), view) != null) {
                throw new IllegalArgumentException("Visual view ids must be unique");
            }
            totalBytes = Math.addExact(totalBytes, view.providerImage().bytes().length);
        }
        if (totalBytes > MultiScaleVisualViewPlanner.MAX_TOTAL_VIEW_BYTES) {
            throw new IllegalArgumentException("Visual view plan exceeds its aggregate byte boundary");
        }
        byId = Map.copyOf(index);
    }

    String planVersion() {
        return planVersion;
    }

    List<VisualViewDescriptor> descriptors() {
        return views.stream().map(VisualView::descriptor).toList();
    }

    List<ProviderImage> providerImages() {
        return views.stream().map(VisualView::providerImage).toList();
    }

    VisualView require(String viewId) {
        var view = byId.get(Objects.requireNonNull(viewId, "viewId"));
        if (view == null) throw new IllegalArgumentException("Visual view is not present in the current plan");
        return view;
    }

    CandidateEvidence toOriginalEvidence(VisualViewEvidence evidence) {
        var view = byId.get(Objects.requireNonNull(evidence, "evidence").viewId());
        if (view == null) throw new IllegalArgumentException("Visual evidence references an unknown view");
        return view.toOriginalEvidence(evidence.boundingBox());
    }
}

record VisualView(
        VisualViewDescriptor descriptor,
        ProviderImage providerImage,
        int sourceWidth,
        int sourceHeight,
        MultiScaleVisualViewPlanner.PixelCrop crop
) {
    VisualView {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(providerImage, "providerImage");
        if (sourceWidth < 1 || sourceHeight < 1) {
            throw new IllegalArgumentException("Visual view source dimensions are invalid");
        }
        Objects.requireNonNull(crop, "crop");
    }

    CandidateEvidence toOriginalEvidence(CandidateBoundingBox viewBox) {
        var cropWidth = crop.right() - crop.left();
        var cropHeight = crop.bottom() - crop.top();
        var left = floorCanonical(crop.left(), cropWidth, viewBox.left(), sourceWidth);
        var top = floorCanonical(crop.top(), cropHeight, viewBox.top(), sourceHeight);
        var right = ceilCanonical(crop.left(), cropWidth, viewBox.right(), sourceWidth);
        var bottom = ceilCanonical(crop.top(), cropHeight, viewBox.bottom(), sourceHeight);
        return CandidateEvidence.image(
                descriptor.sourceArtifactId(),
                new CandidateBoundingBox(
                        Math.clamp(left, 0, 9_999), Math.clamp(top, 0, 9_999),
                        Math.clamp(right, 1, 10_000), Math.clamp(bottom, 1, 10_000)
                )
        );
    }

    private static int floorCanonical(int cropStart, int cropSize, int coordinate, int sourceSize) {
        return (int) Math.floorDiv(cropStart * 10_000L + (long) coordinate * cropSize, sourceSize);
    }

    private static int ceilCanonical(int cropStart, int cropSize, int coordinate, int sourceSize) {
        return (int) Math.ceilDiv(cropStart * 10_000L + (long) coordinate * cropSize, sourceSize);
    }
}
