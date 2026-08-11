// @vitest-environment happy-dom

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type {
  CandidateApplyResponse,
  CandidateReviewResponse,
  InferenceEvent,
  InferenceExecutionLogResponse,
  InferenceRunResponse,
} from '../../api/generated';
import { CandidateReviewPage } from './CandidateReviewPage';
import { snapshot } from './candidate-session.test';

const api = vi.hoisted(() => ({
  getInferenceRunRequest: vi.fn(),
  getInferenceExecutionLogRequest: vi.fn(),
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
    api.getInferenceExecutionLogRequest.mockImplementation(async () => executionLog(snapshot().run));
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

  it('keeps bounded visual telemetry visible beside Candidate review without exposing payloads', async () => {
    const review = cleanReview();
    api.getInferenceRunRequest.mockResolvedValue(review.run);
    api.getCandidateReviewRequest.mockResolvedValue(review);
    api.getInferenceExecutionLogRequest.mockResolvedValue(visualReviewLog(review.run));
    renderPage();

    expect(await screen.findByRole('heading', { name: '阶段与检查点' })).toBeTruthy();
    expect(screen.getByText('元素归属')).toBeTruthy();
    expect(screen.getAllByText('重复区域').length).toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_SEMANTIC_REPEATED_GROUP_ELEMENT_MISSING').length).toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_GROUNDING_ELEMENT_REGION_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按唯一最具体证据区域归一化元素归属').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_GROUNDING_REGION_KIND_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按受控别名、唯一结构事实或唯一绑定约束归一化区域类型').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_GROUNDING_REPEATED_ITEM_SLOT_OWNER_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按唯一可见 ITEM 证据归一化重复字段归属').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_GROUNDING_REGION_PARENT_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按唯一最具体既有容器或唯一包含根祖先归一化区域父级').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('VISUAL_GROUNDING_READING_ORDER_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按唯一既有顺序压紧区域阅读序号').length)
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
    expect(screen.getAllByText('VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED').length)
      .toBeGreaterThan(0);
    expect(screen.getAllByText('已按关系子区域唯一且连通的祖先 GROUP 归属补全层级关系支撑').length)
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
    expect(screen.getByText('阶段内定向修复已完成')).toBeTruthy();
    expect(screen.getByLabelText('Candidate 编辑工作区')).toBeTruthy();
    expect(screen.queryByText('raw-ocr-secret')).toBeNull();
    expect(screen.queryByText('provider-response-secret')).toBeNull();
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

  it('returns to the monitor workspace when another tab cancels the run', async () => {
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

    expect(await screen.findByText('已切换到识别监控')).toBeTruthy();
    expect(screen.queryByLabelText('Candidate 编辑工作区')).toBeNull();
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
          <Route path="/inference-runs/:runId/monitor" element={<div>已切换到识别监控</div>} />
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

function executionLog(run: InferenceRunResponse, failed = false): InferenceExecutionLogResponse {
  return {
    run,
    events: [
      {
        sequence: 1,
        type: 'QUEUED',
        state: 'QUEUED',
        stage: 'OBSERVE',
        occurredAt: '2026-08-10T04:02:49Z',
      },
      {
        sequence: run.sequence,
        type: failed ? 'FAILED' : 'REVIEW_REQUIRED',
        state: failed ? 'FAILED' : run.state,
        stage: failed ? 'CRITIQUE' : run.stage,
        occurredAt: '2026-08-10T04:03:12Z',
      },
    ],
    attempts: failed ? [
      {
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
          CANDIDATE_ITEM_UNRESOLVED: 1,
          CANDIDATE_SCHEMA_KEY_INVALID: 1,
          CANDIDATE_SCALAR_SHAPE_INVALID: 6,
        },
        completedAt: '2026-08-10T04:03:11Z',
      },
    ] : [],
    truncated: false,
  };
}

function visualReviewLog(run: InferenceRunResponse): InferenceExecutionLogResponse {
  return {
    run,
    events: [
      { sequence: 1, type: 'QUEUED', state: 'QUEUED', stage: 'OBSERVE', occurredAt: '2026-08-10T04:02:49Z' },
      { sequence: 2, type: 'CHECKPOINT_ADVANCED', state: 'RUNNING', stage: 'HIERARCHY', occurredAt: '2026-08-10T04:03:02Z' },
      { sequence: 3, type: 'CHECKPOINT_ADVANCED', state: 'RUNNING', stage: 'ELEMENT_BINDING', occurredAt: '2026-08-10T04:03:05Z' },
      { sequence: run.sequence, type: 'REVIEW_REQUIRED', state: run.state, stage: run.stage, occurredAt: run.updatedAt },
    ],
    attempts: [
      {
        attemptOrdinal: 0,
        stage: 'OBSERVE',
        status: 'REJECTED',
        outcomeCode: 'LIVE_VISUAL_ANALYSIS_REJECTED',
        providerModel: 'qwen3.7-flash',
        inputTokens: 2_100,
        outputTokens: 3_900,
        costMicrosCny: 2_400,
        durationMillis: 18_000,
        problemCodeCounts: { VISUAL_SEMANTIC_REPEATED_GROUP_ELEMENT_MISSING: 1 },
        completedAt: '2026-08-10T04:03:00Z',
      },
      {
        attemptOrdinal: 1,
        stage: 'OBSERVE',
        status: 'SUCCEEDED',
        outcomeCode: 'LIVE_VISUAL_GROUNDING_ACCEPTED',
        providerModel: 'qwen3.7-flash',
        inputTokens: 2_150,
        outputTokens: 4_000,
        costMicrosCny: 2_500,
        durationMillis: 19_000,
        problemCodeCounts: {
          VISUAL_GROUNDING_ELEMENT_REGION_NORMALIZED: 1,
          VISUAL_GROUNDING_REGION_KIND_NORMALIZED: 3,
          VISUAL_GROUNDING_REPEATED_ITEM_SLOT_OWNER_NORMALIZED: 1,
          VISUAL_GROUNDING_REGION_PARENT_NORMALIZED: 1,
          VISUAL_GROUNDING_READING_ORDER_NORMALIZED: 2,
        },
        completedAt: '2026-08-10T04:03:02Z',
      },
      {
        attemptOrdinal: 2,
        stage: 'HIERARCHY',
        status: 'SUCCEEDED',
        outcomeCode: 'LIVE_VISUAL_HIERARCHY_V2_ACCEPTED',
        providerModel: 'qwen3.7-flash',
        inputTokens: 1_900,
        outputTokens: 2_800,
        costMicrosCny: 1_900,
        durationMillis: 16_000,
        problemCodeCounts: {
          VISUAL_HIERARCHY_RELATIONSHIP_ENCLOSING_SUPPORT_OWNER_NORMALIZED: 1,
          VISUAL_HIERARCHY_RELATIONSHIP_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED: 1,
          VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SUPPORT_OWNER_NORMALIZED: 1,
          VISUAL_HIERARCHY_RELATIONSHIP_EMPTY_SOURCE_ANCESTOR_SUPPORT_OWNER_NORMALIZED: 1,
          VISUAL_HIERARCHY_RELATIONSHIP_UNKNOWN_SUPPORT_OWNER_NORMALIZED: 1,
          VISUAL_SEMANTIC_HIERARCHY_ENTITY_REGION_REDUNDANT: 1,
          VISUAL_SEMANTIC_HIERARCHY_NON_ROOT_OWNS_ROOT_REGION: 1,
          VISUAL_SEMANTIC_HIERARCHY_BINDING_OWNER_AMBIGUOUS: 1,
        },
        completedAt: '2026-08-10T04:03:05Z',
      },
      {
        attemptOrdinal: 3,
        stage: 'ELEMENT_BINDING',
        status: 'SUCCEEDED',
        outcomeCode: 'LIVE_VISUAL_BINDINGS_V2_ACCEPTED',
        providerModel: 'qwen3.7-flash',
        inputTokens: 1_700,
        outputTokens: 2_200,
        costMicrosCny: 1_600,
        durationMillis: 14_000,
        problemCodeCounts: {},
        completedAt: '2026-08-10T04:03:08Z',
      },
    ],
    truncated: false,
  };
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
