import type {
  CandidateBoundingBox,
  CandidateBundle,
  CandidateEvidence,
  InferenceImage,
} from '../../api/generated';

export type EvidenceCoordinateSpace = 'NORMALIZED_10000' | 'PIXEL';

export function inferEvidenceCoordinateSpace(
  candidate: CandidateBundle,
  image: InferenceImage,
): EvidenceCoordinateSpace {
  const boxes = candidate.schemas.flatMap((schema) => [
    ...imageBoxes(schema.assessment.evidence, image.artifactId),
    ...schema.fields.flatMap((field) => imageBoxes(field.assessment.evidence, image.artifactId)),
  ]);
  return looksLikePixelCoordinateFamily(boxes, image) ? 'PIXEL' : 'NORMALIZED_10000';
}

export function evidenceBoxPresentation(
  box: CandidateBoundingBox,
  image: InferenceImage,
  coordinateSpace: EvidenceCoordinateSpace,
) {
  const horizontalScale = coordinateSpace === 'PIXEL' ? image.width : 10_000;
  const verticalScale = coordinateSpace === 'PIXEL' ? image.height : 10_000;
  return {
    corrected: coordinateSpace === 'PIXEL',
    style: {
      left: `${box.left / horizontalScale * 100}%`,
      top: `${box.top / verticalScale * 100}%`,
      width: `${(box.right - box.left) / horizontalScale * 100}%`,
      height: `${(box.bottom - box.top) / verticalScale * 100}%`,
    },
  };
}

function imageBoxes(evidence: CandidateEvidence[], artifactId: string) {
  return evidence
    .filter((item) => item.kind === 'IMAGE'
      && item.artifactId === artifactId
      && item.boundingBox !== null)
    .map((item) => item.boundingBox!);
}

function looksLikePixelCoordinateFamily(
  boxes: CandidateBoundingBox[],
  image: InferenceImage,
) {
  if (boxes.length < 2 || image.width < 1 || image.height < 1
    || image.width > 10_000 || image.height > 10_000) return false;
  if (boxes.some((box) => box.left < 0 || box.top < 0
    || box.right > image.width || box.bottom > image.height
    || box.left >= box.right || box.top >= box.bottom)) return false;
  const minLeft = Math.min(...boxes.map((box) => box.left));
  const minTop = Math.min(...boxes.map((box) => box.top));
  const maxRight = Math.max(...boxes.map((box) => box.right));
  const maxBottom = Math.max(...boxes.map((box) => box.bottom));
  const spansCanvas = (maxRight - minLeft) * 2 >= image.width
    && (maxBottom - minTop) * 2 >= image.height
    && minLeft * 10 <= image.width * 3
    && minTop * 10 <= image.height * 3
    && maxRight * 10 >= image.width * 7
    && maxBottom * 10 >= image.height * 7;
  const reachesPixelBoundary = maxRight * 100 >= image.width * 98
    || maxBottom * 100 >= image.height * 98;
  return spansCanvas && reachesPixelBoundary;
}
