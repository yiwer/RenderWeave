// @vitest-environment happy-dom

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { InferenceExecutionLogResponse, InferenceRunResponse } from '../../api/generated';
import { InferenceMonitorPage } from './InferenceMonitorPage';

const api = vi.hoisted(() => ({
  getInferenceRunRequest: vi.fn(),
  getInferenceExecutionLogRequest: vi.fn(),
  cancelInferenceRunRequest: vi.fn(),
  retryInferenceRunRequest: vi.fn(),
}));

vi.mock('./candidate-api', () => api);

afterEach(cleanup);

describe('Inference monitor workspace', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getInferenceExecutionLogRequest.mockImplementation(async (runId: string) => executionLog(run('RUNNING', runId)));
  });

  it('keeps cancellation behind explicit confirmation on the monitor page', async () => {
    const queued = run('QUEUED');
    api.getInferenceRunRequest.mockResolvedValue(queued);
    api.cancelInferenceRunRequest.mockResolvedValue({ ...queued, state: 'CANCELLED' as const });
    renderPage();

    expect(await screen.findByRole('heading', { name: '识别监控' })).toBeTruthy();
    fireEvent.click(await screen.findByRole('button', { name: '取消任务' }));
    expect(api.cancelInferenceRunRequest).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '确认取消' }));
    await waitFor(() => expect(api.cancelInferenceRunRequest).toHaveBeenCalledWith(queued.runId));
  });

  it('shows that a running provider call accepted cooperative cancellation', async () => {
    const running = run('RUNNING');
    api.getInferenceRunRequest.mockResolvedValue(running);
    api.cancelInferenceRunRequest.mockResolvedValue({
      ...running,
      cancellationRequested: true,
      sequence: running.sequence + 1,
    });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '取消任务' }));
    fireEvent.click(screen.getByRole('button', { name: '确认取消' }));

    expect(await screen.findByText('取消请求已受理')).toBeTruthy();
    expect(screen.getByText(/等待当前模型调用结束/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: '取消任务' })).toBeNull();
  });

  it('shows payload-free failure diagnostics and creates an auditable retry', async () => {
    const failed = {
      ...run('FAILED'),
      stage: 'CRITIQUE' as const,
      failureCode: 'LIVE_UNSAFE_BLOCKER_SET',
      sequence: 6,
    };
    api.getInferenceRunRequest.mockResolvedValue(failed);
    api.getInferenceExecutionLogRequest.mockResolvedValue(executionLog(failed, true));
    api.retryInferenceRunRequest.mockResolvedValue({
      ...failed,
      runId: '66666666-6666-4666-8666-666666666666',
      state: 'QUEUED' as const,
      retryOfRunId: failed.runId,
    });
    renderPage();

    expect(await screen.findByText('识别任务未生成 Candidate')).toBeTruthy();
    expect((await screen.findAllByText('CANDIDATE_SCHEMA_KEY_INVALID')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('CANDIDATE_SCALAR_SHAPE_INVALID').length).toBeGreaterThan(0);
    expect(screen.queryByText('req-private-provider-id')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '重新运行' }));
    await waitFor(() => expect(api.retryInferenceRunRequest).toHaveBeenCalledWith(failed.runId));
  });

  it('summarizes vNext stage, region, issue, cost and recovery telemetry without payloads', async () => {
    const running = {
      ...run('RUNNING'),
      stage: 'HIERARCHY' as const,
      sequence: 8,
    };
    api.getInferenceRunRequest.mockResolvedValue(running);
    api.getInferenceExecutionLogRequest.mockResolvedValue(visualExecutionLog(running));
    renderPage();

    expect(await screen.findByRole('heading', { name: '阶段与检查点' })).toBeTruthy();
    expect(screen.getByText('感知与区域')).toBeTruthy();
    expect(screen.getByText('层级语义')).toBeTruthy();
    expect(screen.getAllByText('检查点已验证').length).toBeGreaterThan(0);
    expect(screen.getAllByText('正在修复').length).toBeGreaterThan(0);
    expect(screen.getByRole('heading', { name: '有限问题定位' })).toBeTruthy();
    expect(screen.getByText('区域树')).toBeTruthy();
    expect(screen.getAllByText('VISUAL_GROUNDING_PARENT_KIND_INVALID').length).toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CONNECTION_INVALID').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('层级关系区域未连接父子实体区域').length)
      .toBeGreaterThan(0);
    expect(screen.getByText('已从持久检查点恢复')).toBeTruthy();
    expect(screen.getByText(/0\.005716/)).toBeTruthy();
    expect(screen.queryByText('raw-ocr-secret')).toBeNull();
    expect(screen.queryByText('provider-response-secret')).toBeNull();
  });

  it('opens the result workspace only after Candidate generation', async () => {
    const ready = run('REVIEW_REQUIRED');
    api.getInferenceRunRequest.mockResolvedValue(ready);
    api.getInferenceExecutionLogRequest.mockResolvedValue(executionLog(ready));
    renderPage();

    expect(await screen.findByText('Candidate 已生成')).toBeTruthy();
    expect(screen.getByRole('link', { name: /查看识别结果/ }).getAttribute('href'))
      .toBe(`/inference-runs/${ready.runId}/review`);
    expect(screen.queryByRole('navigation', { name: '智能识别版面' })).toBeNull();
  });

  it('explains a provider deadline separately from a generic network failure', async () => {
    const failed = {
      ...run('FAILED'),
      failureCode: 'DASHSCOPE_TIMEOUT',
    };
    api.getInferenceRunRequest.mockResolvedValue(failed);
    api.getInferenceExecutionLogRequest.mockResolvedValue(executionLog(failed));
    renderPage();

    expect(await screen.findByText('DASHSCOPE_TIMEOUT')).toBeTruthy();
    expect(screen.getByText(/模型响应超过当前步骤时限/)).toBeTruthy();
  });

  it('does not retry a historical immutable product profile with its old timeout', async () => {
    const failed = {
      ...run('FAILED'),
      profileId: 'dashscope-qwen38-max-product-v3' as const,
      failureCode: 'DASHSCOPE_NETWORK_ERROR',
    };
    api.getInferenceRunRequest.mockResolvedValue(failed);
    api.getInferenceExecutionLogRequest.mockResolvedValue(executionLog(failed));
    renderPage();

    expect(await screen.findByText(/直接重试仍会沿用旧时限/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: '重新运行' })).toBeNull();
    expect(screen.getByRole('link', { name: /用新配置重新识别/ }).getAttribute('href'))
      .toBe('/inference/new');
  });
});

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/inference-runs/44444444-4444-4444-8444-444444444444/monitor']}>
        <Routes>
          <Route path="/inference-runs/:runId/monitor" element={<InferenceMonitorPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function run(state: InferenceRunResponse['state'], runId = '44444444-4444-4444-8444-444444444444'): InferenceRunResponse {
  return {
    runId,
    mode: 'COMBINED',
    state,
    stage: state === 'REVIEW_REQUIRED' ? 'USER_APPROVAL' : 'STRUCTURE',
    sequence: 3,
    profileId: 'dashscope-qwen37-flash-product-v4',
    sourceReference: 'product-upload',
    costLimitMicrosCny: 2_000_000,
    cancellationRequested: false,
    retryOfRunId: null,
    failureCode: null,
    candidateRevision: state === 'REVIEW_REQUIRED' ? 0 : null,
    createdAt: '2026-08-10T04:02:49Z',
    updatedAt: '2026-08-10T04:03:12Z',
    finishedAt: state === 'FAILED' ? '2026-08-10T04:03:12Z' : null,
  };
}

function executionLog(runSnapshot: InferenceRunResponse, failed = false): InferenceExecutionLogResponse {
  return {
    run: runSnapshot,
    events: [{
      sequence: runSnapshot.sequence,
      type: runSnapshot.state,
      state: runSnapshot.state,
      stage: runSnapshot.stage,
      occurredAt: runSnapshot.updatedAt,
    }],
    attempts: failed ? [{
      attemptOrdinal: 0,
      stage: 'STRUCTURE',
      status: 'SUCCEEDED',
      outcomeCode: 'LIVE_OUTPUT_ACCEPTED',
      providerModel: 'qwen3.7-flash',
      inputTokens: 4_196,
      outputTokens: 2_343,
      costMicrosCny: 2_715,
      durationMillis: 22_083,
      problemCodeCounts: {
        CANDIDATE_SCHEMA_KEY_INVALID: 1,
        CANDIDATE_SCALAR_SHAPE_INVALID: 6,
      },
      completedAt: '2026-08-10T04:03:11Z',
    }] : [],
    truncated: false,
  };
}

function visualExecutionLog(runSnapshot: InferenceRunResponse): InferenceExecutionLogResponse {
  return {
    run: runSnapshot,
    events: [
      {
        sequence: 1,
        type: 'QUEUED',
        state: 'QUEUED',
        stage: 'OBSERVE',
        occurredAt: '2026-08-10T04:02:49Z',
      },
      {
        sequence: 6,
        type: 'CHECKPOINT_ADVANCED',
        state: 'RUNNING',
        stage: 'HIERARCHY',
        occurredAt: '2026-08-10T04:03:10Z',
      },
      {
        sequence: 7,
        type: 'LEASE_RECLAIMED',
        state: 'RUNNING',
        stage: 'HIERARCHY',
        occurredAt: '2026-08-10T04:03:11Z',
      },
    ],
    attempts: [
      {
        attemptOrdinal: 0,
        stage: 'OBSERVE',
        status: 'REJECTED',
        outcomeCode: 'LIVE_VISUAL_ANALYSIS_REJECTED',
        providerModel: 'qwen3.7-flash',
        inputTokens: 2_300,
        outputTokens: 4_100,
        costMicrosCny: 2_715,
        durationMillis: 22_083,
        problemCodeCounts: { VISUAL_GROUNDING_PARENT_KIND_INVALID: 1 },
        completedAt: '2026-08-10T04:03:08Z',
      },
      {
        attemptOrdinal: 1,
        stage: 'OBSERVE',
        status: 'SUCCEEDED',
        outcomeCode: 'LIVE_VISUAL_GROUNDING_ACCEPTED',
        providerModel: 'qwen3.7-flash',
        inputTokens: 2_340,
        outputTokens: 4_220,
        costMicrosCny: 3_001,
        durationMillis: 24_012,
        problemCodeCounts: {},
        completedAt: '2026-08-10T04:03:10Z',
      },
      {
        attemptOrdinal: 2,
        stage: 'HIERARCHY',
        status: 'REJECTED',
        outcomeCode: 'LIVE_VISUAL_ANALYSIS_REJECTED',
        providerModel: 'qwen3.7-flash',
        inputTokens: 0,
        outputTokens: 0,
        costMicrosCny: 0,
        durationMillis: 12,
        problemCodeCounts: { VISUAL_HIERARCHY_V2_RELATIONSHIP_REGION_CONNECTION_INVALID: 1 },
        completedAt: '2026-08-10T04:03:12Z',
      },
    ],
    truncated: false,
  };
}
