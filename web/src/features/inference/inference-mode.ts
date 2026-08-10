import type { InferenceMode } from '../../api/generated';

export const inferenceModeLabels: Record<InferenceMode, string> = {
  IMAGE_ONLY: '仅图片',
  JSON_ONLY: '仅 JSON',
  COMBINED: '图片 + JSON',
};
