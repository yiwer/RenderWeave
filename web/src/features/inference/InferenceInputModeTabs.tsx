import { FileJson2, Image, Images } from 'lucide-react';

import type { InferenceMode } from '../../api/generated';
import { inferenceModeLabels } from './inference-mode';

const modeIcons = {
  IMAGE_ONLY: Image,
  JSON_ONLY: FileJson2,
  COMBINED: Images,
} satisfies Record<InferenceMode, typeof Image>;

export function InferenceInputModeTabs({
  mode,
  onChange,
}: {
  mode: InferenceMode;
  onChange: (mode: InferenceMode) => void;
}) {
  return (
    <div className="mode-tabs" role="tablist" aria-label="输入模式">
      {(Object.keys(inferenceModeLabels) as InferenceMode[]).map((value) => {
        const Icon = modeIcons[value];
        return (
          <button
            key={value}
            type="button"
            role="tab"
            aria-selected={mode === value}
            className={mode === value ? 'active' : ''}
            onClick={() => onChange(value)}
          >
            <Icon aria-hidden="true" size={15} />{inferenceModeLabels[value]}
          </button>
        );
      })}
    </div>
  );
}
