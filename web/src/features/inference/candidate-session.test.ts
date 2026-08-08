import { describe, expect, it } from 'vitest';

import type { CandidateReviewResponse } from '../../api/generated';
import {
  candidateReviewReducer,
  candidateValue,
  createCandidateReviewState,
  newUserField,
} from './candidate-session';

describe('Candidate review session', () => {
  it('marks an edited AI item resolved without mutating its provenance', () => {
    const initial = createCandidateReviewState(snapshot());
    const field = initial.draft.schemas[0]!.fields[0]!;
    const edited = candidateReviewReducer(initial, {
      type: 'edit-field',
      schemaId: initial.selectedSchemaId,
      fieldId: field.candidateFieldId,
      patch: { proposedFieldKey: 'order-total' },
    });
    const result = edited.draft.schemas[0]!.fields[0]!;

    expect(result.proposedFieldKey).toBe('order-total');
    expect(result.assessment.resolution).toBe('RESOLVED_BY_EDIT');
    expect(result.assessment.confidenceBps).toBe(4200);
    expect(result.assessment.evidence).toEqual(field.assessment.evidence);
    expect(edited.dirty).toBe(true);
  });

  it('keeps edits made during an in-flight save and adopts the returned revision', () => {
    const initial = createCandidateReviewState(snapshot());
    const field = initial.draft.schemas[0]!.fields[0]!;
    const first = candidateReviewReducer(initial, {
      type: 'edit-field', schemaId: initial.selectedSchemaId, fieldId: field.candidateFieldId,
      patch: { displayName: '订单金额' },
    });
    const saving = candidateReviewReducer(first, { type: 'save-start' });
    const second = candidateReviewReducer(saving, {
      type: 'edit-field', schemaId: initial.selectedSchemaId, fieldId: field.candidateFieldId,
      patch: { displayName: '最终金额' },
    });
    const server = snapshot();
    server.candidateRevision = 1;
    server.current = first.draft;
    const completed = candidateReviewReducer(second, { type: 'save-success', snapshot: server, generation: first.generation });

    expect(completed.snapshot.candidateRevision).toBe(1);
    expect(completed.draft.schemas[0]!.fields[0]!.displayName).toBe('最终金额');
    expect(completed.dirty).toBe(true);
    expect(completed.saving).toBe(false);
  });

  it('creates user fields without AI provenance and never offers a nested array value helper', () => {
    const field = newUserField();
    expect(field.source).toBe('USER');
    expect(field.assessment).toEqual({ confidenceBps: null, inferred: false, resolution: 'NOT_REQUIRED', evidence: [] });
    expect(candidateValue('ARRAY').items?.kind).toBe('TEXT');
  });
});

export function snapshot(): CandidateReviewResponse {
  const schemaId = '11111111-1111-4111-8111-111111111111';
  const fieldId = '22222222-2222-4222-8222-222222222222';
  const current = {
    contractVersion: 'renderweave-candidate/1.0' as const,
    rootCandidateSchemaId: schemaId,
    schemas: [{
      candidateSchemaId: schemaId,
      proposedSchemaKey: 'order',
      displayName: '订单',
      source: 'AI' as const,
      assessment: {
        confidenceBps: 8800,
        inferred: true,
        resolution: 'NOT_REQUIRED' as const,
        evidence: [{ kind: 'JSON' as const, artifactId: null, boundingBox: null, sampleIndex: 0, jsonPointer: '' }],
      },
      fields: [{
        candidateFieldId: fieldId,
        proposedFieldKey: 'total',
        displayName: '金额',
        required: false,
        value: { kind: 'UNRESOLVED' as const, items: null, reference: null, observedKinds: ['DECIMAL'], constraints: {} },
        source: 'AI' as const,
        assessment: {
          confidenceBps: 4200,
          inferred: true,
          resolution: 'UNRESOLVED' as const,
          evidence: [
            { kind: 'JSON' as const, artifactId: null, boundingBox: null, sampleIndex: 0, jsonPointer: '/total' },
            { kind: 'IMAGE' as const, artifactId: '33333333-3333-4333-8333-333333333333', boundingBox: { left: 1200, top: 2300, right: 6000, bottom: 4100 }, sampleIndex: null, jsonPointer: null },
          ],
        },
      }],
    }],
  };
  return {
    run: {
      runId: '44444444-4444-4444-8444-444444444444',
      mode: 'COMBINED',
      state: 'REVIEW_REQUIRED',
      stage: 'USER_APPROVAL',
      sequence: 7,
      profileId: 'replay-v1',
      sourceReference: 'combined-01',
      cancellationRequested: false,
      retryOfRunId: null,
      failureCode: null,
      candidateRevision: 0,
      createdAt: '2026-08-08T00:00:00Z',
      updatedAt: '2026-08-08T00:00:01Z',
      finishedAt: null,
    },
    candidateRevision: 0,
    original: structuredClone(current),
    current,
    problems: [{ code: 'LOW_CONFIDENCE_UNRESOLVED', severity: 'BLOCKER', itemId: fieldId, pointer: '/schemas/0/fields/0/assessment/resolution', args: {} }],
    finalCandidate: null,
    appliedAt: null,
    images: [{ artifactId: '33333333-3333-4333-8333-333333333333', ordinal: 0, width: 1200, height: 800, contentUrl: '/api/v1/inference-runs/run/artifacts/image' }],
    jsonSampleCount: 1,
  };
}
