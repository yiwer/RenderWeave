import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { createPortal } from 'react-dom';
import { Check, ChevronDown } from 'lucide-react';

export interface SelectFieldOption {
  value: string;
  label: string;
  disabled?: boolean;
}

interface PopCoords {
  left: number;
  width: number;
  top?: number;
  bottom?: number;
  up: boolean;
}

/**
 * Token-styled replacement for the native <select>: the browser popup cannot
 * be styled, so this follows the ARIA collapsible listbox-button pattern —
 * focus stays on the trigger, the active option is exposed via
 * aria-activedescendant, and the panel is portalled to <body> so it escapes
 * the inspector's overflow clipping.
 */
export function SelectField({
  id,
  ariaLabel,
  value,
  options,
  onChange,
  disabled = false,
  placeholder = '请选择',
  dataPointer,
  invalid = false,
  onBlur,
}: {
  id?: string;
  ariaLabel: string;
  value: string;
  options: SelectFieldOption[];
  onChange: (value: string) => void;
  disabled?: boolean;
  placeholder?: string;
  dataPointer?: string;
  invalid?: boolean;
  onBlur?: () => void;
}) {
  const listId = useId();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const popRef = useRef<HTMLUListElement>(null);
  const [open, setOpen] = useState(false);
  const selectedIndex = options.findIndex((option) => option.value === value);
  const [activeIndex, setActiveIndex] = useState(0);
  const [coords, setCoords] = useState<PopCoords>({ left: 0, width: 0, up: false });
  const selected = selectedIndex >= 0 ? options[selectedIndex] : null;

  const close = useCallback(() => setOpen(false), []);

  const computeCoords = useCallback((): PopCoords => {
    const rect = triggerRef.current?.getBoundingClientRect() ?? { left: 0, top: 0, bottom: 0, width: 0 };
    const estimatedHeight = Math.min(options.length, 8) * 36 + 14;
    const spaceBelow = window.innerHeight - rect.bottom;
    const up = spaceBelow < estimatedHeight + 8 && rect.top > spaceBelow;
    return up
      ? { left: rect.left, width: rect.width, bottom: window.innerHeight - rect.top + 6, up }
      : { left: rect.left, width: rect.width, top: rect.bottom + 6, up };
  }, [options.length]);

  const openList = () => {
    if (disabled || options.length === 0) return;
    setCoords(computeCoords());
    const firstEnabled = options.findIndex((option) => !option.disabled);
    setActiveIndex(selectedIndex >= 0 ? selectedIndex : Math.max(firstEnabled, 0));
    setOpen(true);
  };

  const select = (index: number) => {
    const option = options[index];
    if (!option || option.disabled) return;
    if (option.value !== value) onChange(option.value);
    close();
    triggerRef.current?.focus();
  };

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: Event) => {
      const target = event.target as Node;
      if (triggerRef.current?.contains(target) || popRef.current?.contains(target)) return;
      close();
    };
    // Follow the trigger instead of dismissing: focusing the trigger or an
    // autosave reflow makes the browser fire async scroll-into-view events
    // right after open, which a close-on-scroll would misread as dismissal.
    const onReposition = (event: Event) => {
      if (event.target instanceof Node && popRef.current?.contains(event.target)) return;
      setCoords(computeCoords());
    };
    document.addEventListener('mousedown', onPointerDown);
    window.addEventListener('scroll', onReposition, true);
    window.addEventListener('resize', onReposition);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      window.removeEventListener('scroll', onReposition, true);
      window.removeEventListener('resize', onReposition);
    };
  }, [open, close, computeCoords]);

  useLayoutEffect(() => {
    if (!open) return;
    // Keep the active option visible by scrolling the panel directly —
    // scrollIntoView would scroll the page too and trigger a reposition storm.
    const pop = popRef.current;
    const active = pop?.querySelector<HTMLElement>('[data-active="true"]');
    if (!pop || !active) return;
    if (active.offsetTop < pop.scrollTop) pop.scrollTop = active.offsetTop;
    else if (active.offsetTop + active.offsetHeight > pop.scrollTop + pop.clientHeight) {
      pop.scrollTop = active.offsetTop + active.offsetHeight - pop.clientHeight;
    }
  }, [open, activeIndex]);

  const moveActive = (delta: number) => {
    setActiveIndex((current) => {
      let next = current;
      for (let step = 0; step < options.length; step += 1) {
        next = (next + delta + options.length) % options.length;
        if (!options[next]?.disabled) break;
      }
      return next;
    });
  };

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return;
    if (!open) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp' || event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        openList();
      }
      return;
    }
    switch (event.key) {
      case 'ArrowDown': event.preventDefault(); moveActive(1); break;
      case 'ArrowUp': event.preventDefault(); moveActive(-1); break;
      case 'Home': event.preventDefault(); setActiveIndex(Math.max(options.findIndex((option) => !option.disabled), 0)); break;
      case 'End': {
        event.preventDefault();
        for (let index = options.length - 1; index >= 0; index -= 1) {
          if (!options[index]?.disabled) { setActiveIndex(index); break; }
        }
        break;
      }
      case 'Enter': case ' ': event.preventDefault(); select(activeIndex); break;
      case 'Escape': event.preventDefault(); close(); break;
      case 'Tab': close(); break;
      default:
        if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
          const key = event.key.toLowerCase();
          const match = options.findIndex((option, index) => index > activeIndex && !option.disabled && option.label.toLowerCase().startsWith(key));
          const fallback = options.findIndex((option) => !option.disabled && option.label.toLowerCase().startsWith(key));
          if (match >= 0) setActiveIndex(match);
          else if (fallback >= 0) setActiveIndex(fallback);
        }
    }
  };

  return (
    <>
      <button
        ref={triggerRef}
        id={id}
        type="button"
        className={`select-field ${open ? 'open' : ''} ${selected ? '' : 'empty'}`}
        aria-label={ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-activedescendant={open && activeIndex >= 0 ? `${listId}-${activeIndex}` : undefined}
        aria-invalid={invalid || undefined}
        data-pointer={dataPointer}
        disabled={disabled}
        onClick={() => (open ? close() : openList())}
        onKeyDown={onKeyDown}
        onBlur={onBlur}
      >
        <span>{selected ? selected.label : placeholder}</span>
        <ChevronDown aria-hidden="true" size={14} />
      </button>
      {open && createPortal(
        <ul
          ref={popRef}
          id={listId}
          role="listbox"
          aria-label={ariaLabel}
          className={`select-field-pop ${coords.up ? 'up' : ''}`}
          style={{
            left: coords.left,
            width: coords.width,
            top: coords.top,
            bottom: coords.bottom,
          }}
        >
          {options.map((option, index) => (
            <li
              key={option.value || `empty-${index}`}
              id={`${listId}-${index}`}
              role="option"
              aria-selected={index === selectedIndex}
              aria-disabled={option.disabled || undefined}
              data-active={index === activeIndex}
              className="select-field-option"
              onMouseEnter={() => setActiveIndex(index)}
              onClick={() => select(index)}
            >
              <span>{option.label}</span>
              {index === selectedIndex && <Check aria-hidden="true" size={13} />}
            </li>
          ))}
        </ul>,
        document.body,
      )}
    </>
  );
}
