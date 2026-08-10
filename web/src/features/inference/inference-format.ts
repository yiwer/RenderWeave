import type { InferenceMode, InferenceRunResponse } from '../../api/generated';

export function inferenceStageLabel(stage: string) {
  const labels: Record<string, string> = {
    NORMALIZE: '整理输入',
    OBSERVE: '提取结构信号',
    STRUCTURE: '生成候选结构',
    DETERMINISTIC_VALIDATE: '执行确定性校验',
    CRITIQUE: '分析校验问题',
    REPAIR: '修复候选结构',
    USER_APPROVAL: '等待逐项校对',
    ATOMIC_CREATE: '原子创建 Draft',
  };
  return labels[stage] ?? stage;
}

export function inferenceStateLabel(state: string) {
  const labels: Record<string, string> = {
    QUEUED: '等待执行',
    RUNNING: '识别中',
    REVIEW_REQUIRED: '等待校对',
    APPLYING: '正在创建',
    COMPLETED: '已完成',
    FAILED: '识别失败',
    CANCELLED: '已取消',
  };
  return labels[state] ?? state;
}

export function inferenceModeLabel(mode: InferenceMode) {
  const labels: Record<InferenceMode, string> = {
    IMAGE_ONLY: '仅图片',
    JSON_ONLY: '仅 JSON',
    COMBINED: '图片 + JSON',
  };
  return labels[mode];
}

export function inferenceProfileLabel(profileId: string) {
  if (profileId === 'replay-v1') return '确定性回放';
  if (profileId.includes('qwen37-plus')) return 'Qwen3.7 Plus';
  if (profileId.includes('qwen37-flash')) return 'Qwen3.7 Flash';
  if (profileId.includes('qwen37-max')) return 'Qwen3.7 Max 2026-06-08';
  if (profileId.includes('qwen38-max')) return 'Qwen3.8 Max';
  return profileId;
}

export function inferenceRunWorkspacePath(run: Pick<InferenceRunResponse, 'runId' | 'state'>) {
  return inferenceRunHasResult(run.state)
    ? `/inference-runs/${run.runId}/review`
    : `/inference-runs/${run.runId}/monitor`;
}

export function inferenceRunHasResult(state: InferenceRunResponse['state']) {
  return state === 'REVIEW_REQUIRED' || state === 'APPLYING' || state === 'COMPLETED';
}

export function inferenceRunActionLabel(state: InferenceRunResponse['state']) {
  if (state === 'REVIEW_REQUIRED') return '校对结果';
  if (state === 'COMPLETED' || state === 'APPLYING') return '查看结果';
  if (state === 'FAILED' || state === 'CANCELLED') return '查看诊断';
  return '查看进度';
}

export function formatInferenceTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}
