import {
  applyInferenceCandidate,
  cancelInferenceRun,
  createReplayInferenceRun,
  getInferenceCandidate,
  getInferenceRun,
  getLiveInferenceAvailability,
  listInferenceRuns,
  listReplayFixtures,
  retryInferenceRun,
  saveInferenceCandidate,
  type CandidateBundle,
  type CandidateApplyResponse,
  type CandidateReviewResponse,
  type CreateLiveRunRequest,
  type InferenceEvent,
  type InferenceRunResponse,
  type InferenceRunPageResponse,
  type InferenceMode,
  type LiveAvailabilityResponse,
  type Problem,
  type ReplayFixtureListResponse,
} from '../../api/generated';
import { StudioRequestError } from '../schema-studio/lossless-api';
import { filesForLiveMode } from './live-input';

export async function listReplayFixturesRequest(): Promise<ReplayFixtureListResponse> {
  const result = await listReplayFixtures();
  return unwrap(result.data, result.error, '读取 replay 样本');
}

export async function createReplayRunRequest(
  fixtureId: string,
  idempotencyKey: string,
): Promise<InferenceRunResponse> {
  const result = await createReplayInferenceRun({
    headers: { 'Idempotency-Key': idempotencyKey },
    body: { fixtureId, externalTransferConfirmed: true },
  });
  return unwrap(result.data, result.error, '创建 replay 推断任务');
}

export async function getLiveAvailabilityRequest(): Promise<LiveAvailabilityResponse> {
  const result = await getLiveInferenceAvailability();
  return unwrap(result.data, result.error, '读取 live 推断配置');
}

export async function createLiveRunRequest(
  profileId: CreateLiveRunRequest['profileId'],
  mode: InferenceMode,
  images: File[],
  jsonSamples: File[],
  idempotencyKey: string,
  costLimitMicrosCny: number | null,
): Promise<InferenceRunResponse> {
  const body = new FormData();
  body.append('metadata', new Blob([JSON.stringify({
    profileId,
    mode,
    inputClassification: 'USER_PROVIDED',
    externalTransferConfirmed: true,
    experimentalProfileConfirmed: true,
    costLimitMicrosCny,
  })], { type: 'application/json' }));
  const active = filesForLiveMode(mode, images, jsonSamples);
  active.images.forEach((image) => body.append('images', image));
  active.jsonSamples.forEach((sample) => body.append('jsonSamples', sample));
  const response = await fetch('/api/v1/inference-runs/live', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body,
  });
  const value = await response.json() as InferenceRunResponse | Problem;
  if (!response.ok) throw new StudioRequestError(value as Problem);
  return value as InferenceRunResponse;
}

export async function getInferenceRunRequest(runId: string): Promise<InferenceRunResponse> {
  const result = await getInferenceRun({ path: { runId } });
  return unwrap(result.data, result.error, '读取推断任务');
}

export async function listInferenceRunsRequest(
  page = 1,
  size = 6,
): Promise<InferenceRunPageResponse> {
  const result = await listInferenceRuns({ query: { page, size } });
  return unwrap(result.data, result.error, '读取最近识别任务');
}

export async function cancelInferenceRunRequest(runId: string): Promise<InferenceRunResponse> {
  const result = await cancelInferenceRun({ path: { runId } });
  return unwrap(result.data, result.error, '取消推断任务');
}

export async function retryInferenceRunRequest(runId: string): Promise<InferenceRunResponse> {
  const result = await retryInferenceRun({
    path: { runId },
    headers: { 'Idempotency-Key': crypto.randomUUID() },
  });
  return unwrap(result.data, result.error, '重试推断任务');
}

export async function getCandidateReviewRequest(runId: string): Promise<CandidateReviewResponse> {
  const result = await getInferenceCandidate({ path: { runId } });
  return unwrap(result.data, result.error, '读取 Candidate');
}

export async function saveCandidateReviewRequest(
  runId: string,
  expectedCandidateRevision: number,
  candidate: CandidateBundle,
): Promise<CandidateReviewResponse> {
  const result = await saveInferenceCandidate({
    path: { runId },
    body: { expectedCandidateRevision, candidate },
  });
  return unwrap(result.data, result.error, '自动保存 Candidate');
}

export async function applyCandidateRequest(
  runId: string,
  expectedCandidateRevision: number,
): Promise<CandidateApplyResponse> {
  const result = await applyInferenceCandidate({
    path: { runId },
    body: { expectedCandidateRevision },
  });
  return unwrap(result.data, result.error, '原子创建 Draft Bundle');
}

const inferenceEventTypes = [
  'QUEUED',
  'LEASE_ACQUIRED',
  'LEASE_RECLAIMED',
  'CHECKPOINT_ADVANCED',
  'REVIEW_REQUIRED',
  'CANDIDATE_UPDATED',
  'CANCELLATION_REQUESTED',
  'CANCELLED',
  'FAILED',
  'RETRIED',
  'APPLYING',
  'CANDIDATE_APPLIED',
] as const;

export function subscribeInferenceRunEvents(
  runId: string,
  afterSequence: number,
  onEvent: (event: InferenceEvent) => void,
  onError?: () => void,
): () => void {
  const source = new EventSource(
    `/api/v1/inference-runs/${encodeURIComponent(runId)}/events?afterSequence=${afterSequence}`,
  );
  let lastSequence = afterSequence;
  const handle = (message: Event) => {
    if (!(message instanceof MessageEvent) || typeof message.data !== 'string') return;
    try {
      const event = JSON.parse(message.data) as InferenceEvent;
      if (event.sequence <= lastSequence) return;
      lastSequence = event.sequence;
      onEvent(event);
    } catch {
      onError?.();
    }
  };
  inferenceEventTypes.forEach((type) => source.addEventListener(type, handle));
  source.onerror = () => onError?.();
  return () => {
    inferenceEventTypes.forEach((type) => source.removeEventListener(type, handle));
    source.close();
  };
}

function unwrap<T>(data: T | undefined, error: Problem | undefined, operation: string): T {
  if (error) throw new StudioRequestError(error);
  if (data === undefined) throw new Error(`${operation}时服务端未返回数据。`);
  return data;
}
