import { FlaskConical } from 'lucide-react';
import { useEffect } from 'react';

import { type PrototypeVariant } from './model';

interface PrototypeSwitcherProps {
  current: PrototypeVariant;
  onChange: (variant: PrototypeVariant) => void;
}

const variants: PrototypeVariant[] = ['A', 'B', 'C'];
const names: Record<PrototypeVariant, string> = {
  A: 'Library Studio',
  B: 'Canvas Focus',
  C: 'Structure Bench',
};

function adjacent(current: PrototypeVariant, direction: -1 | 1): PrototypeVariant {
  const index = variants.indexOf(current);
  return variants[(index + direction + variants.length) % variants.length] ?? 'A';
}

export function PrototypeSwitcher({ current, onChange }: PrototypeSwitcherProps) {
  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      const target = event.target;
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        (target instanceof HTMLElement && target.isContentEditable)
      ) {
        return;
      }
      if (event.key === 'ArrowLeft') {
        onChange(adjacent(current, -1));
      }
      if (event.key === 'ArrowRight') {
        onChange(adjacent(current, 1));
      }
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [current, onChange]);

  if (!import.meta.env.DEV) {
    return null;
  }

  return (
    <aside className="prototype-switcher rwtd-switcher" aria-label="原型方案切换器">
      <span className="rwtd-switcher-kicker">
        <FlaskConical aria-hidden="true" size={14} />
        方案
      </span>
      <div className="rwtd-switcher-tabs" role="tablist" aria-label="选择原型方案">
        {variants.map((variant) => (
          <button
            key={variant}
            type="button"
            role="tab"
            aria-selected={current === variant}
            className={current === variant ? 'active' : ''}
            title={names[variant]}
            onClick={() => onChange(variant)}
          >
            {variant}
          </button>
        ))}
      </div>
      <span className="rwtd-switcher-name" aria-live="polite">{names[current]}</span>
      <kbd>← →</kbd>
    </aside>
  );
}
