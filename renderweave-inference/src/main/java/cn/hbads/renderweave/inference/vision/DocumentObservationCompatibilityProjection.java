package cn.hbads.renderweave.inference.vision;

import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;

import java.util.ArrayList;
import java.util.Objects;

/** Pure compatibility view from source-pixel observations to the product-v45 stage context. */
public final class DocumentObservationCompatibilityProjection {
    public static final String VERSION = "v45-source-to-candidate/1.0";

    private static final String READING_ORDER_IDENTITY = "top-left-canonical/1.0";
    private static final String CANONICALIZATION_IDENTITY = "unicode-nfc-whitespace-collapse/1.0";
    private static final String CONFIDENCE_SCALE_IDENTITY = "basis-points/1.0";
    private static final String CONFIDENCE_BUCKET_IDENTITY = "v45-confidence-buckets/1.0";

    public DocumentVisionObservation project(DocumentObservationIR source) {
        Objects.requireNonNull(source, "source");
        requireCompatible(source);
        try {
            var projectedArtifacts = new ArrayList<DocumentVisionObservation.ArtifactObservation>();
            for (var artifact : source.artifacts()) {
                var projectedLines = new ArrayList<DocumentVisionObservation.TextLine>();
                for (var line : artifact.observations()) {
                    projectedLines.add(new DocumentVisionObservation.TextLine(
                            line.observationId(),
                            line.canonicalOrder(),
                            projectBox(line.sourcePixelBox(), artifact.width(), artifact.height()),
                            DocumentVisionObservation.ConfidenceBucket.valueOf(
                                    line.confidence().derivedBucket().name()
                            ),
                            line.text()
                    ));
                }
                projectedArtifacts.add(new DocumentVisionObservation.ArtifactObservation(
                        artifact.artifactId(), artifact.sourceOrdinal(), projectedLines
                ));
            }
            var projected = DocumentVisionObservation.canonical(source.capabilityIdentity(), projectedArtifacts);
            if (projected.lineCount() != source.observationCount()) {
                throw new DocumentVisionException("DOCUMENT_VISION_PROJECTION_LOSSY");
            }
            return projected;
        } catch (DocumentVisionException known) {
            throw known;
        } catch (RuntimeException invalid) {
            throw new DocumentVisionException("DOCUMENT_VISION_PROJECTION_INVALID");
        }
    }

    private static CandidateBoundingBox projectBox(
            DocumentObservationIR.SourcePixelBox box,
            int width,
            int height
    ) {
        box.requireWithin(width, height);
        return new CandidateBoundingBox(
                (int) Math.floorDiv((long) box.left() * 10_000L, width),
                (int) Math.floorDiv((long) box.top() * 10_000L, height),
                (int) Math.ceilDiv((long) box.right() * 10_000L, width),
                (int) Math.ceilDiv((long) box.bottom() * 10_000L, height)
        );
    }

    private static void requireCompatible(DocumentObservationIR source) {
        var provenance = source.provenance();
        if (!DocumentObservationIR.VERSION.equals(source.contractVersion())
                || !VERSION.equals(provenance.projectionIdentity())
                || !READING_ORDER_IDENTITY.equals(provenance.readingOrderDerivationIdentity())
                || !CANONICALIZATION_IDENTITY.equals(provenance.canonicalizationIdentity())
                || !CONFIDENCE_SCALE_IDENTITY.equals(provenance.confidenceScaleIdentity())
                || !CONFIDENCE_BUCKET_IDENTITY.equals(provenance.confidenceBucketProjectionIdentity())) {
            throw new DocumentVisionException("DOCUMENT_VISION_PROJECTION_IDENTITY_MISMATCH");
        }
    }
}
