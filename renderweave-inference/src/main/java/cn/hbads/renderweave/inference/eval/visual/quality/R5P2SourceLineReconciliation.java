package cn.hbads.renderweave.inference.eval.visual.quality;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Pure evaluation-memory source-line reconciliation for R5P2. */
public final class R5P2SourceLineReconciliation {
    public static final String VERSION = "FrozenSourceLineReconciliationPolicy/1.0";
    public static final String PROJECTION_IDENTITY = "renderweave-r5p-source-projection/1.0";
    public static final int AREA_OVERLAP_BPS = 5_000;
    public static final int VERTICAL_OVERLAP_BPS = 8_000;
    private static final List<String> POLICY_FRAMES = List.of(
            VERSION,
            "projection=" + PROJECTION_IDENTITY,
            "scope=same-source-cross-view-only/1.0",
            "geometry=intersection-over-smaller-area-5000-bps/1.0",
            "vertical=intersection-over-smaller-height-8000-bps/1.0",
            "center=smaller-center-in-larger-closed-open/1.0",
            "cluster=complete-link-source-order/1.0",
            "representative=pixel-density-confidence-smaller-area-view-line/1.0",
            "representative-payload=original-observation-only/1.0",
            "canonical-text-order=unicode-scalar-lexicographic/1.0",
            "sort=top-left-bottom-right-canonical-text-view-line/1.0");
    public static final String POLICY_IDENTITY = VERSION + ":" + sha256(
            String.join("\n", POLICY_FRAMES).getBytes(StandardCharsets.UTF_8));

    private static final Comparator<ProjectedLine> SOURCE_ORDER = Comparator
            .comparing((ProjectedLine value) -> value.sourceBox().top())
            .thenComparing(value -> value.sourceBox().left())
            .thenComparing(value -> value.sourceBox().bottom())
            .thenComparing(value -> value.sourceBox().right())
            .thenComparing(ProjectedLine::text, R5P2SourceLineReconciliation::compareUnicodeScalars)
            .thenComparingInt(ProjectedLine::viewOrdinal)
            .thenComparingInt(ProjectedLine::lineOrdinal);

    private R5P2SourceLineReconciliation() { }

    public static ProjectedLine project(
            String observationId,
            String sourceArtifactId,
            int viewOrdinal,
            int lineOrdinal,
            int viewWidth,
            int viewHeight,
            int sourceWidth,
            int sourceHeight,
            PixelBox sourceCrop,
            PixelBox viewLine,
            int confidenceBps,
            String text
    ) {
        Objects.requireNonNull(sourceCrop, "sourceCrop").requireWithin(sourceWidth, sourceHeight);
        Objects.requireNonNull(viewLine, "viewLine").requireWithin(viewWidth, viewHeight);
        var viewCanonical = new SourceBox(
                floorRatio(viewLine.left(), 10_000, viewWidth),
                floorRatio(viewLine.top(), 10_000, viewHeight),
                ceilRatio(viewLine.right(), 10_000, viewWidth),
                ceilRatio(viewLine.bottom(), 10_000, viewHeight));
        var cropWidth = sourceCrop.right() - sourceCrop.left();
        var cropHeight = sourceCrop.bottom() - sourceCrop.top();
        var sourceBox = new SourceBox(
                projectFloor(sourceCrop.left(), cropWidth, viewCanonical.left(), sourceWidth),
                projectFloor(sourceCrop.top(), cropHeight, viewCanonical.top(), sourceHeight),
                projectCeil(sourceCrop.left(), cropWidth, viewCanonical.right(), sourceWidth),
                projectCeil(sourceCrop.top(), cropHeight, viewCanonical.bottom(), sourceHeight));
        return new ProjectedLine(observationId, sourceArtifactId, sourceBox, confidenceBps,
                text, viewOrdinal, lineOrdinal,
                new PixelDensity(Math.multiplyExact((long) viewWidth, viewHeight),
                        Math.multiplyExact((long) cropWidth, cropHeight)));
    }

    public static Outcome reconcile(List<ProjectedLine> input) {
        input = List.copyOf(Objects.requireNonNull(input, "input"));
        if (input.isEmpty() || input.size() > 4_096 || input.stream().anyMatch(Objects::isNull)) {
            throw invalid("R5P2_RECONCILIATION_INPUT_INVALID");
        }
        var observationIds = new HashSet<String>();
        for (var line : input) {
            if (!observationIds.add(line.observationId())) {
                throw invalid("R5P2_RECONCILIATION_OBSERVATION_DUPLICATED");
            }
        }
        var ordered = new ArrayList<>(input);
        ordered.sort(SOURCE_ORDER);
        var clusters = new ArrayList<List<ProjectedLine>>();
        for (var candidate : ordered) {
            var assigned = false;
            for (var cluster : clusters) {
                if (cluster.stream().allMatch(existing -> sameSourceLineCandidate(candidate, existing))) {
                    cluster.add(candidate);
                    assigned = true;
                    break;
                }
            }
            if (!assigned) {
                var cluster = new ArrayList<ProjectedLine>();
                cluster.add(candidate);
                clusters.add(cluster);
            }
        }
        var representatives = new ArrayList<ProjectedLine>();
        for (var cluster : clusters) {
            var representative = cluster.getFirst();
            for (var candidate : cluster) {
                if (prefersRepresentative(candidate, representative)) representative = candidate;
            }
            representatives.add(representative);
        }
        representatives.sort(SOURCE_ORDER);
        return new Outcome(List.copyOf(representatives), input.size(), clusters.size(), POLICY_IDENTITY);
    }

    public static boolean sameSourceLineCandidate(ProjectedLine left, ProjectedLine right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (!left.sourceArtifactId().equals(right.sourceArtifactId())
                || left.viewOrdinal() == right.viewOrdinal()) {
            return false;
        }
        var leftBox = left.sourceBox();
        var rightBox = right.sourceBox();
        var intersectionWidth = Math.max(0,
                Math.min(leftBox.right(), rightBox.right()) - Math.max(leftBox.left(), rightBox.left()));
        var intersectionHeight = Math.max(0,
                Math.min(leftBox.bottom(), rightBox.bottom()) - Math.max(leftBox.top(), rightBox.top()));
        var intersection = Math.multiplyExact((long) intersectionWidth, intersectionHeight);
        var smallerArea = Math.min(leftBox.area(), rightBox.area());
        var smallerHeight = Math.min(leftBox.height(), rightBox.height());
        if (intersection == 0
                || !areaThresholdAllows(intersection, smallerArea)
                || !verticalThresholdAllows(intersectionHeight, smallerHeight)) {
            return false;
        }
        if (leftBox.area() < rightBox.area()) return centerInside(leftBox, rightBox);
        if (rightBox.area() < leftBox.area()) return centerInside(rightBox, leftBox);
        return centerInside(leftBox, rightBox) && centerInside(rightBox, leftBox);
    }

    public static boolean areaThresholdAllows(long intersectionArea, long smallerArea) {
        return atLeastBps(intersectionArea, smallerArea, AREA_OVERLAP_BPS);
    }

    public static boolean verticalThresholdAllows(long intersectionHeight, long smallerHeight) {
        return atLeastBps(intersectionHeight, smallerHeight, VERTICAL_OVERLAP_BPS);
    }

    public static boolean prefersRepresentative(ProjectedLine candidate, ProjectedLine existing) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(existing, "existing");
        var density = candidate.pixelDensity().compareTo(existing.pixelDensity());
        if (density != 0) return density > 0;
        var confidence = Integer.compare(candidate.confidenceBps(), existing.confidenceBps());
        if (confidence != 0) return confidence > 0;
        var area = Long.compare(candidate.sourceBox().area(), existing.sourceBox().area());
        if (area != 0) return area < 0;
        if (candidate.viewOrdinal() != existing.viewOrdinal()) {
            return candidate.viewOrdinal() < existing.viewOrdinal();
        }
        return candidate.lineOrdinal() < existing.lineOrdinal();
    }

    private static int compareUnicodeScalars(String left, String right) {
        var leftOffset = 0;
        var rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            var leftCodePoint = left.codePointAt(leftOffset);
            var rightCodePoint = right.codePointAt(rightOffset);
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            leftOffset += Character.charCount(leftCodePoint);
            rightOffset += Character.charCount(rightCodePoint);
        }
        if (leftOffset == left.length()) return rightOffset == right.length() ? 0 : -1;
        return 1;
    }

    private static boolean centerInside(SourceBox smaller, SourceBox larger) {
        var centerX2 = Math.addExact(smaller.left(), smaller.right());
        var centerY2 = Math.addExact(smaller.top(), smaller.bottom());
        return Math.multiplyExact(larger.left(), 2) <= centerX2
                && centerX2 < Math.multiplyExact(larger.right(), 2)
                && Math.multiplyExact(larger.top(), 2) <= centerY2
                && centerY2 < Math.multiplyExact(larger.bottom(), 2);
    }

    private static boolean atLeastBps(long numerator, long denominator, int threshold) {
        return denominator > 0 && Math.multiplyExact(numerator, 10_000L)
                >= Math.multiplyExact(denominator, (long) threshold);
    }

    private static int floorRatio(int value, int multiplier, int divisor) {
        if (divisor < 1) throw invalid("R5P2_PROJECTION_DIMENSIONS_INVALID");
        return Math.toIntExact(Math.floorDiv(Math.multiplyExact((long) value, multiplier), divisor));
    }

    private static int ceilRatio(int value, int multiplier, int divisor) {
        if (divisor < 1) throw invalid("R5P2_PROJECTION_DIMENSIONS_INVALID");
        return Math.toIntExact(Math.ceilDiv(Math.multiplyExact((long) value, multiplier), divisor));
    }

    private static int projectFloor(int cropStart, int cropSize, int coordinate, int sourceSize) {
        if (sourceSize < 1) throw invalid("R5P2_PROJECTION_DIMENSIONS_INVALID");
        return Math.toIntExact(Math.floorDiv(Math.addExact(
                Math.multiplyExact((long) cropStart, 10_000L),
                Math.multiplyExact((long) coordinate, cropSize)), sourceSize));
    }

    private static int projectCeil(int cropStart, int cropSize, int coordinate, int sourceSize) {
        if (sourceSize < 1) throw invalid("R5P2_PROJECTION_DIMENSIONS_INVALID");
        return Math.toIntExact(Math.ceilDiv(Math.addExact(
                Math.multiplyExact((long) cropStart, 10_000L),
                Math.multiplyExact((long) coordinate, cropSize)), sourceSize));
    }

    private static String canonicalText(String value) {
        Objects.requireNonNull(value, "text");
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        var output = new StringBuilder();
        var pendingSpace = false;
        for (var offset = 0; offset < normalized.length();) {
            var codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) {
                throw invalid("R5P2_RECONCILIATION_TEXT_INVALID");
            }
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = output.length() > 0;
            } else {
                if (pendingSpace) output.append(' ');
                output.appendCodePoint(codePoint);
                pendingSpace = false;
            }
        }
        if (output.isEmpty() || !output.toString().equals(value)) {
            throw invalid("R5P2_RECONCILIATION_TEXT_INVALID");
        }
        return value;
    }

    private static String localId(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,127}")) {
            throw invalid("R5P2_RECONCILIATION_OBSERVATION_ID_INVALID");
        }
        return value;
    }

    private static String artifactId(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid("R5P2_RECONCILIATION_SOURCE_ID_INVALID");
        }
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    public record PixelBox(int left, int top, int right, int bottom) {
        public PixelBox {
            if (left < 0 || top < 0 || left >= right || top >= bottom) {
                throw invalid("R5P2_PROJECTION_PIXEL_BOX_INVALID");
            }
        }

        void requireWithin(int width, int height) {
            if (width < 1 || height < 1 || right > width || bottom > height) {
                throw invalid("R5P2_PROJECTION_PIXEL_BOX_INVALID");
            }
        }
    }

    public record SourceBox(int left, int top, int right, int bottom) {
        public SourceBox {
            if (left < 0 || top < 0 || left >= right || top >= bottom
                    || right > 10_000 || bottom > 10_000) {
                throw invalid("R5P2_RECONCILIATION_SOURCE_BOX_INVALID");
            }
        }

        long area() {
            return Math.multiplyExact((long) right - left, bottom - top);
        }

        int height() {
            return bottom - top;
        }
    }

    public record PixelDensity(long numerator, long denominator)
            implements Comparable<PixelDensity> {
        public PixelDensity {
            if (numerator < 1 || denominator < 1) {
                throw invalid("R5P2_RECONCILIATION_DENSITY_INVALID");
            }
        }

        @Override
        public int compareTo(PixelDensity other) {
            Objects.requireNonNull(other, "other");
            return BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(other.denominator))
                    .compareTo(BigInteger.valueOf(other.numerator)
                            .multiply(BigInteger.valueOf(denominator)));
        }
    }

    public record ProjectedLine(
            String observationId,
            String sourceArtifactId,
            SourceBox sourceBox,
            int confidenceBps,
            String text,
            int viewOrdinal,
            int lineOrdinal,
            PixelDensity pixelDensity
    ) {
        public ProjectedLine {
            observationId = localId(observationId);
            sourceArtifactId = artifactId(sourceArtifactId);
            Objects.requireNonNull(sourceBox, "sourceBox");
            if (confidenceBps < 0 || confidenceBps > 10_000
                    || viewOrdinal < 0 || viewOrdinal >= 10
                    || lineOrdinal < 0 || lineOrdinal >= 4_096) {
                throw invalid("R5P2_RECONCILIATION_LINE_BOUNDS_INVALID");
            }
            text = canonicalText(text);
            Objects.requireNonNull(pixelDensity, "pixelDensity");
        }
    }

    public record Outcome(
            List<ProjectedLine> representatives,
            int inputCount,
            int clusterCount,
            String policyIdentity
    ) {
        public Outcome {
            representatives = List.copyOf(Objects.requireNonNull(representatives, "representatives"));
            if (inputCount < 1 || clusterCount != representatives.size()
                    || clusterCount < 1 || !POLICY_IDENTITY.equals(policyIdentity)) {
                throw invalid("R5P2_RECONCILIATION_OUTCOME_INVALID");
            }
        }
    }
}
