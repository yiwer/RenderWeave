// @vitest-environment happy-dom

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { CandidateApplyResponse, CandidateReviewResponse } from '../../api/generated';
import { CandidateReviewPage } from './CandidateReviewPage';
import { snapshot } from './candidate-session.test';

const api = vi.hoisted(() => ({
  getInferenceRunRequest: vi.fn(),
  getCandidateReviewRequest: vi.fn(),
  saveCandidateReviewRequest: vi.fn(),
  applyCandidateRequest: vi.fn(),
  subscribeInferenceRunEvents: vi.fn(() => () => undefined),
}));

vi.mock('./candidate-api', () => api);

afterEach(cleanup);

describe('Candidate atomic apply workspace', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getInferenceRunRequest.mockResolvedValue(structuredClone(snapshot().run));
    api.subscribeInferenceRunEvents.mockReturnValue(() => undefined);
  });

  it('requires an explicit bundle confirmation and renders the created Draft handoff', async () => {
    let server = cleanReview();
    api.getInferenceRunRequest.mockImplementation(async () => structuredClone(server.run));
    api.getCandidateReviewRequest.mockImplementation(async () => structuredClone(server));
    api.applyCandidateRequest.mockImplementation(async () => {
      const result = applyResult(server);
      server = {
        ...server,
        run: result.run,
        finalCandidate: structuredClone(server.current),
        appliedAt: result.appliedAt,
      };
      return result;
    });
    renderPage();

    const trigger = await screen.findByRole('button', { name: '原子创建 1 个 Draft' });
    expect((trigger as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(trigger);
    expect(screen.getByText('任一 active key 或 tombstone 冲突：整包零写')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '确认原子创建' }));
    await waitFor(() => expect(api.applyCandidateRequest).toHaveBeenCalledWith(
      server.run.runId,
      0,
    ));
    expect(await screen.findByText('Draft Bundle 已原子创建')).toBeTruthy();
    expect(screen.getByRole('link', { name: /order/ }).getAttribute('href')).toBe('/schemas/order');
    expect(screen.getByText('final Candidate 已冻结；本次操作没有发布、更新或删除任何既有 Schema。')).toBeTruthy();
  });

  it('keeps atomic apply disabled while a deterministic blocker remains', async () => {
    api.getCandidateReviewRequest.mockResolvedValue(snapshot());
    renderPage();

    const trigger = await screen.findByRole('button', { name: '原子创建 1 个 Draft' });
    expect((trigger as HTMLButtonElement).disabled).toBe(true);
    expect(api.applyCandidateRequest).not.toHaveBeenCalled();
  });
});

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/inference-runs/44444444-4444-4444-8444-444444444444/review']}>
        <Routes>
          <Route path="/inference-runs/:runId/review" element={<CandidateReviewPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function cleanReview(): CandidateReviewResponse {
  const review = snapshot();
  review.current.schemas[0]!.fields[0]!.value = {
    kind: 'DECIMAL', items: null, reference: null, observedKinds: [], constraints: {},
  };
  review.current.schemas[0]!.fields[0]!.assessment.resolution = 'RESOLVED_BY_EDIT';
  review.original = structuredClone(review.current);
  review.problems = [];
  return review;
}

function applyResult(review: CandidateReviewResponse): CandidateApplyResponse {
  return {
    run: {
      ...review.run,
      state: 'COMPLETED',
      stage: 'ATOMIC_CREATE',
      sequence: review.run.sequence + 2,
      updatedAt: '2026-08-08T00:00:02Z',
      finishedAt: '2026-08-08T00:00:02Z',
    },
    candidateRevision: review.candidateRevision,
    rootSchemaKey: 'order',
    createdDrafts: [{ schemaKey: 'order', revision: 0, href: '/api/v1/schema-drafts/order' }],
    appliedAt: '2026-08-08T00:00:02Z',
  };
}
