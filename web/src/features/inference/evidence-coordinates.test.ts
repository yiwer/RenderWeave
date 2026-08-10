import { describe, expect, it } from 'vitest';

import type { CandidateBundle, InferenceImage } from '../../api/generated';
import { evidenceBoxPresentation, inferEvidenceCoordinateSpace } from './evidence-coordinates';

describe('Candidate image evidence coordinates', () => {
  it('recognizes the real 1510x4096 pixel-coordinate family and renders the selected box proportionally', () => {
    const image: InferenceImage = {
      artifactId: 'artifact', ordinal: 0, width: 1510, height: 4096, contentUrl: '/image',
    };
    const candidate = candidateWithBoxes([
      [200, 200, 1300, 500],
      [200, 500, 1300, 700],
      [200, 700, 800, 800],
      [800, 700, 1300, 800],
      [200, 800, 1300, 900],
      [200, 900, 1300, 3800],
      [200, 3900, 1300, 4096],
    ]);

    const coordinateSpace = inferEvidenceCoordinateSpace(candidate, image);
    const presentation = evidenceBoxPresentation(
      candidate.schemas[0]!.fields[5]!.assessment.evidence[0]!.boundingBox!,
      image,
      coordinateSpace,
    );

    expect(coordinateSpace).toBe('PIXEL');
    expect(presentation.corrected).toBe(true);
    expect(Number.parseFloat(presentation.style.left)).toBeCloseTo(13.245, 3);
    expect(Number.parseFloat(presentation.style.top)).toBeCloseTo(21.973, 3);
    expect(Number.parseFloat(presentation.style.width)).toBeCloseTo(72.848, 3);
    expect(Number.parseFloat(presentation.style.height)).toBeCloseTo(70.801, 3);
  });

  it('keeps an isolated ambiguous box in the canonical normalized coordinate space', () => {
    const image: InferenceImage = {
      artifactId: 'artifact', ordinal: 0, width: 1510, height: 4096, contentUrl: '/image',
    };
    const candidate = candidateWithBoxes([[200, 900, 1300, 3800]]);

    expect(inferEvidenceCoordinateSpace(candidate, image)).toBe('NORMALIZED_10000');
  });

  it('does not guess from a broad family that only touches the normalized origin', () => {
    const image: InferenceImage = {
      artifactId: 'artifact', ordinal: 0, width: 1510, height: 4096, contentUrl: '/image',
    };
    const candidate = candidateWithBoxes([
      [0, 0, 1300, 2000],
      [100, 1800, 1400, 3800],
    ]);

    expect(inferEvidenceCoordinateSpace(candidate, image)).toBe('NORMALIZED_10000');
  });
});

function candidateWithBoxes(boxes: Array<[number, number, number, number]>): CandidateBundle {
  return {
    contractVersion: 'renderweave-candidate/1.0',
    rootCandidateSchemaId: '11111111-1111-4111-8111-111111111111',
    schemas: [{
      candidateSchemaId: '11111111-1111-4111-8111-111111111111',
      proposedSchemaKey: 'route-card',
      displayName: '线路卡',
      source: 'AI',
      assessment: { confidenceBps: 9000, inferred: true, resolution: 'NOT_REQUIRED', evidence: [] },
      fields: boxes.map((box, index) => ({
        candidateFieldId: `22222222-2222-4222-8222-${String(index).padStart(12, '0')}`,
        proposedFieldKey: `field${index}`,
        displayName: `字段 ${index}`,
        required: false,
        value: { kind: 'TEXT', items: null, reference: null, observedKinds: [], constraints: {} },
        source: 'AI',
        assessment: {
          confidenceBps: 9000,
          inferred: true,
          resolution: 'NOT_REQUIRED',
          evidence: [{
            kind: 'IMAGE', artifactId: 'artifact',
            boundingBox: { left: box[0], top: box[1], right: box[2], bottom: box[3] },
            sampleIndex: null, jsonPointer: null,
          }],
        },
      })),
    }],
  };
}
