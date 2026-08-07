import {
  createReplayInferenceRun,
  getInferenceCandidate,
  listReplayFixtures,
  saveInferenceCandidate,
  type CandidateBundle,
  type CandidateReviewResponse,
  type InferenceRunResponse,
  type Problem,
  type ReplayFixtureListResponse,
} from '../../api/generated';
import { StudioRequestError } from '../schema-studio/lossless-api';

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

function unwrap<T>(data: T | undefined, error: Problem | undefined, operation: string): T {
  if (error) throw new StudioRequestError(error);
  if (data === undefined) throw new Error(`${operation}时服务端未返回数据。`);
  return data;
}
