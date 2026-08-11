package cn.hbads.renderweave.inference.vision;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Ephemeral OCR/layout observations. Text is intentionally redacted from every toString representation.
 */
public record DocumentVisionObservation(
        String observationVersion,
        String capabilityId,
        List<ArtifactObservation> artifacts
) {
    public static final String VERSION = "renderweave-document-vision-observation/1.0";
    public static final int MAX_ARTIFACTS = 10;
    public static final int MAX_LINES = 512;
    public static final int MAX_LINE_TEXT_BYTES = 256;
    public static final int MAX_TOTAL_TEXT_BYTES = 32 * 1024;

    public DocumentVisionObservation {
        if (!VERSION.equals(observationVersion)) {
            throw new IllegalArgumentException("Document vision observation version is unsupported");
        }
        if (capabilityId == null || !capabilityId.matches("[a-z0-9][a-z0-9._:-]{0,190}")) {
            throw new IllegalArgumentException("Document vision observation capability id is invalid");
        }
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (artifacts.isEmpty() || artifacts.size() > MAX_ARTIFACTS) {
            throw new IllegalArgumentException("Document vision artifact observation count is invalid");
        }
        var artifactIds = new HashSet<String>();
        var ordinals = new HashSet<Integer>();
        var lineIds = new HashSet<String>();
        var lineCount = 0;
        var textBytes = 0;
        for (var artifact : artifacts) {
            if (!artifactIds.add(artifact.artifactId()) || !ordinals.add(artifact.sourceOrdinal())) {
                throw new IllegalArgumentException("Document vision artifact observations must be unique");
            }
            for (var line : artifact.lines()) {
                if (!lineIds.add(line.lineId())) {
                    throw new IllegalArgumentException("Document vision line ids must be unique");
                }
                lineCount = Math.addExact(lineCount, 1);
                textBytes = Math.addExact(textBytes, line.text().getBytes(StandardCharsets.UTF_8).length);
            }
        }
        if (lineCount > MAX_LINES || textBytes > MAX_TOTAL_TEXT_BYTES) {
            throw new IllegalArgumentException("Document vision observation exceeds its aggregate boundary");
        }
    }

    public static DocumentVisionObservation canonical(
            String capabilityId,
            List<ArtifactObservation> artifacts
    ) {
        var ordered = new ArrayList<>(Objects.requireNonNull(artifacts, "artifacts"));
        ordered.sort(Comparator.comparingInt(ArtifactObservation::sourceOrdinal));
        return new DocumentVisionObservation(VERSION, capabilityId, ordered);
    }

    public int lineCount() {
        return artifacts.stream().mapToInt(item -> item.lines().size()).sum();
    }

    @Override
    public String toString() {
        return "DocumentVisionObservation[observationVersion=" + observationVersion
                + ", capabilityId=" + capabilityId + ", artifacts=" + artifacts.size()
                + ", lines=<redacted:" + lineCount() + ">]";
    }

    public record ArtifactObservation(
            String artifactId,
            int sourceOrdinal,
            List<TextLine> lines
    ) {
        public ArtifactObservation {
            if (artifactId == null || !artifactId.matches("[0-9a-f]{64}")
                    || sourceOrdinal < 0 || sourceOrdinal >= MAX_ARTIFACTS) {
                throw new IllegalArgumentException("Document vision artifact observation identity is invalid");
            }
            var ordered = new ArrayList<>(Objects.requireNonNull(lines, "lines"));
            ordered.sort(Comparator.comparingInt(TextLine::readingOrder));
            for (var index = 0; index < ordered.size(); index++) {
                if (ordered.get(index).readingOrder() != index) {
                    throw new IllegalArgumentException("Document vision line reading order must be contiguous");
                }
            }
            lines = List.copyOf(ordered);
        }

        @Override
        public String toString() {
            return "ArtifactObservation[artifactId=" + artifactId + ", sourceOrdinal=" + sourceOrdinal
                    + ", lines=<redacted:" + lines.size() + ">]";
        }
    }

    public record TextLine(
            String lineId,
            int readingOrder,
            CandidateBoundingBox boundingBox,
            ConfidenceBucket confidence,
            String text
    ) {
        public TextLine {
            if (lineId == null || !lineId.matches("ocr-[0-9]{2}-[0-9]{3}")) {
                throw new IllegalArgumentException("Document vision line id is invalid");
            }
            if (readingOrder < 0 || readingOrder >= MAX_LINES) {
                throw new IllegalArgumentException("Document vision line reading order is invalid");
            }
            Objects.requireNonNull(boundingBox, "boundingBox");
            Objects.requireNonNull(confidence, "confidence");
            text = normalizeText(text);
        }

        @Override
        public String toString() {
            return "TextLine[lineId=" + lineId + ", readingOrder=" + readingOrder
                    + ", boundingBox=" + boundingBox + ", confidence=" + confidence
                    + ", text=<redacted:" + text.getBytes(StandardCharsets.UTF_8).length + ">]";
        }

        private static String normalizeText(String value) {
            if (value == null) throw new IllegalArgumentException("Document vision text is required");
            var normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
                    .replaceAll("\\s+", " ").trim();
            if (normalized.isEmpty() || normalized.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_TEXT_BYTES
                    || normalized.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint))) {
                throw new IllegalArgumentException("Document vision text boundary is invalid");
            }
            return normalized;
        }
    }

    public enum ConfidenceBucket { LOW, MEDIUM, HIGH }
}
