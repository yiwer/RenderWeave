import { describe, expect, it } from 'vitest';

import type { CandidateReviewResponse } from '../../api/generated';
import {
  candidateReviewReducer,
  candidateValue,
  createCandidateReviewState,
  newUserField,
  newUserSchema,
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

  it('does not downgrade an edited AI item to CONFIRMED', () => {
    const initial = createCandidateReviewState(snapshot());
    const field = initial.draft.schemas[0]!.fields[0]!;
    const edited = candidateReviewReducer(initial, {
      type: 'edit-field', schemaId: initial.selectedSchemaId, fieldId: field.candidateFieldId,
      patch: { displayName: '订单金额' },
    });

    const confirmed = candidateReviewReducer(edited, {
      type: 'resolve-field', schemaId: initial.selectedSchemaId,
      fieldId: field.candidateFieldId, resolution: 'CONFIRMED',
    });

    expect(confirmed.draft.schemas[0]!.fields[0]!.assessment.resolution).toBe('RESOLVED_BY_EDIT');
  });

  it('restores an edited and removed AI item as RESOLVED_BY_EDIT', () => {
    const initial = createCandidateReviewState(snapshot());
    const field = initial.draft.schemas[0]!.fields[0]!;
    const edited = candidateReviewReducer(initial, {
      type: 'edit-field', schemaId: initial.selectedSchemaId, fieldId: field.candidateFieldId,
      patch: { displayName: '订单金额' },
    });
    const removed = candidateReviewReducer(edited, {
      type: 'resolve-field', schemaId: initial.selectedSchemaId,
      fieldId: field.candidateFieldId, resolution: 'REMOVED',
    });

    const restored = candidateReviewReducer(removed, {
      type: 'resolve-field', schemaId: initial.selectedSchemaId,
      fieldId: field.candidateFieldId, resolution: field.assessment.resolution,
    });

    expect(restored.draft.schemas[0]!.fields[0]!.assessment.resolution).toBe('RESOLVED_BY_EDIT');
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

  it('adds and reorders user schemas without changing the immutable root identity', () => {
    const initial = createCandidateReviewState(snapshot());
    const child = newUserSchema('customer', '客户');
    const added = candidateReviewReducer(initial, { type: 'add-schema', schema: child });
    const moved = candidateReviewReducer(added, {
      type: 'move-schema', schemaId: child.candidateSchemaId, direction: -1,
    });

    expect(moved.draft.rootCandidateSchemaId).toBe(initial.draft.rootCandidateSchemaId);
    expect(moved.draft.schemas.map((schema) => schema.candidateSchemaId)).toEqual([
      child.candidateSchemaId,
      initial.draft.rootCandidateSchemaId,
    ]);
    expect(moved.draft.schemas[0]).toMatchObject({
      proposedSchemaKey: 'customer', displayName: '客户', source: 'USER',
      assessment: { confidenceBps: null, inferred: false, resolution: 'NOT_REQUIRED', evidence: [] },
    });
    expect(moved.selectedSchemaId).toBe(child.candidateSchemaId);
  });

  it('reorders schemas to an absolute index with clamping for drag-and-drop', () => {
    const initial = createCandidateReviewState(snapshot());
    const first = newUserSchema('customer', '客户');
    const second = newUserSchema('parcel', '包裹');
    const added = [first, second].reduce(
      (state, schema) => candidateReviewReducer(state, { type: 'add-schema', schema }),
      initial,
    );

    const reordered = candidateReviewReducer(added, {
      type: 'reorder-schema', schemaId: second.candidateSchemaId, targetIndex: 0,
    });
    expect(reordered.draft.schemas.map((schema) => schema.candidateSchemaId)).toEqual([
      second.candidateSchemaId,
      initial.draft.rootCandidateSchemaId,
      first.candidateSchemaId,
    ]);

    const clamped = candidateReviewReducer(added, {
      type: 'reorder-schema', schemaId: initial.draft.rootCandidateSchemaId, targetIndex: 99,
    });
    expect(clamped.draft.schemas.map((schema) => schema.candidateSchemaId)).toEqual([
      first.candidateSchemaId,
      second.candidateSchemaId,
      initial.draft.rootCandidateSchemaId,
    ]);
  });

  it('rejects removing the immutable root Schema in the client reducer', () => {
    const initial = createCandidateReviewState(snapshot());
    const result = candidateReviewReducer(initial, {
      type: 'resolve-schema',
      schemaId: initial.draft.rootCandidateSchemaId,
      resolution: 'REMOVED',
    });

    expect(result).toBe(initial);
    expect(result.dirty).toBe(false);
    expect(result.draft.schemas.find((schema) => schema.candidateSchemaId === result.draft.rootCandidateSchemaId)?.assessment.resolution).not.toBe('REMOVED');
  });

  it('reorders fields while retaining AI evidence and constraint literals', () => {
    const initial = createCandidateReviewState(snapshot());
    const schemaId = initial.selectedSchemaId;
    const aiField = initial.draft.schemas[0]!.fields[0]!;
    const userField = { ...newUserField('currency', '币种'), value: candidateValue('TEXT') };
    const added = candidateReviewReducer(initial, { type: 'add-field', schemaId, field: userField });
    const constrained = candidateReviewReducer(added, {
      type: 'edit-field', schemaId, fieldId: userField.candidateFieldId,
      patch: { value: { ...userField.value, constraints: { minLength: '3', enum: '["CNY","USD"]' } } },
    });
    const moved = candidateReviewReducer(constrained, {
      type: 'move-field', schemaId, fieldId: userField.candidateFieldId, direction: -1,
    });

    expect(moved.draft.schemas[0]!.fields.map((field) => field.candidateFieldId)).toEqual([
      userField.candidateFieldId,
      aiField.candidateFieldId,
    ]);
    expect(moved.draft.schemas[0]!.fields[0]!.value.constraints).toEqual({
      minLength: '3', enum: '["CNY","USD"]',
    });
    expect(moved.draft.schemas[0]!.fields[1]!.assessment.evidence).toEqual(aiField.assessment.evidence);
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
            { kind: 'IMAGE' as const, artifactId: '55555555-5555-4555-8555-555555555555', boundingBox: { left: 1800, top: 1900, right: 7200, bottom: 4700 }, sampleIndex: null, jsonPointer: null },
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
      costLimitMicrosCny: null,
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
    images: [
      { artifactId: '33333333-3333-4333-8333-333333333333', ordinal: 0, width: 1200, height: 800, contentUrl: '/api/v1/inference-runs/run/artifacts/image-1' },
      { artifactId: '55555555-5555-4555-8555-555555555555', ordinal: 1, width: 960, height: 720, contentUrl: '/api/v1/inference-runs/run/artifacts/image-2' },
    ],
    jsonSampleCount: 1,
  };
}
