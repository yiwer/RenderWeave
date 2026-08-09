// @vitest-environment happy-dom

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { CandidateApplyResponse, CandidateReviewResponse, InferenceEvent } from '../../api/generated';
import { CandidateReviewPage } from './CandidateReviewPage';
import { snapshot } from './candidate-session.test';

const api = vi.hoisted(() => ({
  getInferenceRunRequest: vi.fn(),
  getCandidateReviewRequest: vi.fn(),
  saveCandidateReviewRequest: vi.fn(),
  applyCandidateRequest: vi.fn(),
  cancelInferenceRunRequest: vi.fn(),
  retryInferenceRunRequest: vi.fn(),
  subscribeInferenceRunEvents: vi.fn<(
    runId: string,
    afterSequence: number,
    onEvent: (event: InferenceEvent) => void,
  ) => () => void>(() => () => undefined),
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
    const flow = screen.getByRole('navigation', { name: '数据结构识别进度' });
    expect(flow.querySelector('[aria-current="step"]')?.textContent).toContain('原子创建');
  });

  it('keeps atomic apply disabled while a deterministic blocker remains', async () => {
    api.getCandidateReviewRequest.mockResolvedValue(snapshot());
    renderPage();

    const trigger = await screen.findByRole('button', { name: '原子创建 1 个 Draft' });
    expect((trigger as HTMLButtonElement).disabled).toBe(true);
    expect(api.applyCandidateRequest).not.toHaveBeenCalled();
  });

  it('cancels a queued run only after explicit confirmation', async () => {
    const queued = { ...snapshot().run, state: 'QUEUED' as const, stage: 'NORMALIZE' as const };
    api.getInferenceRunRequest.mockResolvedValue(queued);
    api.cancelInferenceRunRequest.mockResolvedValue({ ...queued, state: 'CANCELLED' as const });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '取消任务' }));
    expect(api.cancelInferenceRunRequest).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '确认取消' }));
    await waitFor(() => expect(api.cancelInferenceRunRequest).toHaveBeenCalledWith(queued.runId));
  });

  it('creates a new auditable run when a failed run is retried', async () => {
    const failed = { ...snapshot().run, state: 'FAILED' as const, stage: 'REPAIR' as const, failureCode: 'LIVE_REPAIR_BUDGET_EXHAUSTED' };
    api.getInferenceRunRequest.mockResolvedValue(failed);
    api.retryInferenceRunRequest.mockResolvedValue({ ...failed, runId: '66666666-6666-4666-8666-666666666666', state: 'QUEUED' as const, retryOfRunId: failed.runId });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '重新运行' }));
    await waitFor(() => expect(api.retryInferenceRunRequest).toHaveBeenCalledWith(failed.runId));
  });

  it('synchronizes an externally completed Candidate snapshot into the run flow', async () => {
    let server = cleanReview();
    api.getCandidateReviewRequest.mockImplementation(async () => structuredClone(server));
    renderPage();

    await screen.findByRole('button', { name: '原子创建 1 个 Draft' });
    await waitFor(() => expect(api.subscribeInferenceRunEvents).toHaveBeenCalled());
    server = {
      ...server,
      run: { ...server.run, state: 'COMPLETED', stage: 'ATOMIC_CREATE', sequence: server.run.sequence + 1 },
      finalCandidate: structuredClone(server.current),
      appliedAt: '2026-08-08T00:00:02Z',
    };

    await act(async () => {
      api.subscribeInferenceRunEvents.mock.calls[0]![2](inferenceEvent(server));
    });

    expect(await screen.findByText('Draft Bundle 已原子创建')).toBeTruthy();
    const flow = screen.getByRole('navigation', { name: '数据结构识别进度' });
    expect(flow.querySelector('[aria-current="step"]')?.textContent).toContain('原子创建');
    expect(screen.queryByRole('button', { name: '原子创建 1 个 Draft' })).toBeNull();
  });

  it('leaves the editor when another tab cancels the run', async () => {
    let server = cleanReview();
    api.getCandidateReviewRequest.mockImplementation(async () => structuredClone(server));
    renderPage();

    await screen.findByLabelText('Candidate 编辑工作区');
    await waitFor(() => expect(api.subscribeInferenceRunEvents).toHaveBeenCalled());
    server = {
      ...server,
      run: { ...server.run, state: 'CANCELLED', stage: 'USER_APPROVAL', sequence: server.run.sequence + 1 },
    };

    await act(async () => {
      api.subscribeInferenceRunEvents.mock.calls[0]![2](inferenceEvent(server));
    });

    expect(await screen.findByText('推断任务未生成 Candidate')).toBeTruthy();
    expect(screen.queryByLabelText('Candidate 编辑工作区')).toBeNull();
    expect(screen.getByRole('button', { name: '重新运行' })).toBeTruthy();
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

function inferenceEvent(review: CandidateReviewResponse): InferenceEvent {
  return {
    sequence: review.run.sequence,
    type: review.run.state,
    state: review.run.state,
    stage: review.run.stage,
    data: {},
    occurredAt: '2026-08-08T00:00:02Z',
  };
}
