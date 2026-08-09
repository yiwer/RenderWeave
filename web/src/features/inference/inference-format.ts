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
