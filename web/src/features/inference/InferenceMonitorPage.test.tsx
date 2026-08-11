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
    expect(screen.getAllByText('区域树').length).toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_GROUNDING_PARENT_KIND_INVALID').length).toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_SEMANTIC_SLOT_EVIDENCE_CONTAINS_ELEMENT').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('字段证据包住了其他元素，应恢复为叶子字段或 GROUP 容器').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_SEMANTIC_REPEATED_GROUP_CARDINALITY_INVALID').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('MANY GROUP 与重复区域的双向归属不一致').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_GROUNDING_ELEMENT_REGION_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按唯一最具体证据区域归一化元素归属').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_GROUNDING_REPEATED_ITEM_SLOT_OWNER_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按唯一可见 ITEM 证据归一化重复字段归属').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_GROUNDING_REGION_PARENT_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按唯一最具体既有容器归一化区域父级').length)
      .toBeGreaterThan(0);
    expect(screen.getByText('证据区域')).toBeTruthy();
    expect(screen.getAllByText('最早返回 盘点图片元素 修复').length).toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('层级关系支撑 ID 列表不能为空').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按唯一容器区域 GROUP 归属归一化层级关系支撑').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_HIERARCHY_RELATIONSHIP_ENCLOSING_SUPPORT_OWNER_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按唯一包围且连通的 GROUP 证据归一化层级关系支撑').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_HIERARCHY_RELATIONSHIP_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按关系源区域唯一且连通的祖先 GROUP 证据归一化层级关系支撑').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SUPPORT_OWNER_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按关系区域唯一且连通的 GROUP 归属补全层级关系支撑').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_HIERARCHY_RELATIONSHIP_UNKNOWN_SUPPORT_OWNER_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已将未知层级关系支撑引用归一化为关系区域唯一且连通的 GROUP 归属').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_SEMANTIC_HIERARCHY_ENTITY_REGION_REDUNDANT').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('同一实体不能同时拥有祖先区域和后代区域').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_SEMANTIC_HIERARCHY_NON_ROOT_OWNS_ROOT_REGION').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('非根实体不能拥有图片根区域').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_SEMANTIC_HIERARCHY_BINDING_OWNER_AMBIGUOUS').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('字段存在多个同等最小的空间实体 owner').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('最早返回 构建层级关系 修复').length).toBeGreaterThan(0);
    expect(screen.getByText('已从持久检查点恢复')).toBeTruthy();
    expect(screen.getByText(/0\.007346/)).toBeTruthy();
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
        problemCodeCounts: {
          VISUAL_GROUNDING_PARENT_KIND_INVALID: 1,
          VISUAL_SEMANTIC_REPEATED_GROUP_CARDINALITY_INVALID: 1,
          VISUAL_SEMANTIC_SLOT_EVIDENCE_CONTAINS_ELEMENT: 1,
        },
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
        problemCodeCounts: {
          VISUAL_GROUNDING_ELEMENT_REGION_NORMALIZED: 1,
          VISUAL_GROUNDING_REPEATED_ITEM_SLOT_OWNER_NORMALIZED: 1,
          VISUAL_GROUNDING_REGION_PARENT_NORMALIZED: 1,
        },
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
        problemCodeCounts: {
          VISUAL_HIERARCHY_V2_RELATIONSHIP_SUPPORT_IDS_EMPTY: 1,
          VISUAL_SEMANTIC_HIERARCHY_ENTITY_REGION_REDUNDANT: 1,
          VISUAL_SEMANTIC_HIERARCHY_NON_ROOT_OWNS_ROOT_REGION: 1,
          VISUAL_SEMANTIC_HIERARCHY_BINDING_OWNER_AMBIGUOUS: 1,
        },
        completedAt: '2026-08-10T04:03:12Z',
      },
      {
        attemptOrdinal: 3,
        stage: 'HIERARCHY',
        status: 'SUCCEEDED',
        outcomeCode: 'LIVE_VISUAL_HIERARCHY_V2_ACCEPTED',
        providerModel: 'qwen3.7-flash-2026-07-15',
        inputTokens: 1_950,
        outputTokens: 2_400,
        costMicrosCny: 1_630,
        durationMillis: 15_200,
        problemCodeCounts: {
          VISUAL_HIERARCHY_RELATIONSHIP_SUPPORT_OWNER_NORMALIZED: 1,
          VISUAL_HIERARCHY_RELATIONSHIP_ENCLOSING_SUPPORT_OWNER_NORMALIZED: 1,
          VISUAL_HIERARCHY_RELATIONSHIP_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED: 1,
          VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SUPPORT_OWNER_NORMALIZED: 1,
          VISUAL_HIERARCHY_RELATIONSHIP_UNKNOWN_SUPPORT_OWNER_NORMALIZED: 1,
        },
        completedAt: '2026-08-10T04:03:14Z',
      },
    ],
    truncated: false,
  };
}
