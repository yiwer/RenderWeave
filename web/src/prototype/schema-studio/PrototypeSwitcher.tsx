import { ArrowLeft, ArrowRight, FlaskConical } from 'lucide-react';
import { useEffect } from 'react';

import { type PrototypeVariant, variantNames } from './model';

interface PrototypeSwitcherProps {
  current: PrototypeVariant;
  onChange: (variant: PrototypeVariant) => void;
}

const variants: PrototypeVariant[] = ['A', 'B', 'C'];

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
    <aside className="prototype-switcher" aria-label="原型方案切换器">
      <button
        type="button"
        className="switcher-arrow"
        aria-label="上一个原型方案"
        onClick={() => onChange(adjacent(current, -1))}
      >
        <ArrowLeft aria-hidden="true" size={17} />
      </button>
      <div className="switcher-label" aria-live="polite">
        <FlaskConical aria-hidden="true" size={16} />
        <span>
          {current} — {variantNames[current]}
        </span>
      </div>
      <button
        type="button"
        className="switcher-arrow"
        aria-label="下一个原型方案"
        onClick={() => onChange(adjacent(current, 1))}
      >
        <ArrowRight aria-hidden="true" size={17} />
      </button>
    </aside>
  );
}
