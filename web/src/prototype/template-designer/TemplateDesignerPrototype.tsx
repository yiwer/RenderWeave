import { useCallback, useEffect, useReducer, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';

import { designerReducer, initialDesignerState, parseVariant, type PrototypeVariant } from './model';
import { PrototypeSwitcher } from './PrototypeSwitcher';
import { BindingDialog, ConflictBanner, InvalidSaveDialog, NoticeToast } from './SharedParts';
import { VariantA } from './VariantA';
import { VariantB } from './VariantB';
import { VariantC } from './VariantC';
import './template-designer.css';

/**
 * PROTOTYPE — throwaway route /prototype/template-designer?variant=A|B|C
 * 三个在线 Template 设计器变体，验证 T220 的组件库、结构动作与属性分组。
 */
export function TemplateDesignerPrototype() {
  const [searchParams, setSearchParams] = useSearchParams();
  const variant = parseVariant(searchParams.get('variant'));
  const [state, dispatch] = useReducer(designerReducer, initialDesignerState);
  const previewTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const changeVariant = useCallback(
    (next: PrototypeVariant) => {
      const params = new URLSearchParams(searchParams);
      params.set('variant', next);
      setSearchParams(params, { replace: true });
    },
    [searchParams, setSearchParams],
  );

  const runPreview = useCallback(() => {
    if (previewTimer.current) {
      clearTimeout(previewTimer.current);
    }
    dispatch({ type: 'preview-start' });
    if (state.templateStatus === 'INVALID' || state.scenario === 'layout-error' || state.scenario === 'conflict') {
      previewTimer.current = null;
      return;
    }
    previewTimer.current = setTimeout(() => dispatch({ type: 'preview-finish' }), 900);
  }, [state.scenario, state.templateStatus]);

  const cancelPreview = useCallback(() => {
    if (previewTimer.current) {
      clearTimeout(previewTimer.current);
      previewTimer.current = null;
    }
    dispatch({ type: 'preview-cancel' });
  }, []);

  useEffect(() => () => {
    if (previewTimer.current) clearTimeout(previewTimer.current);
  }, []);

  useEffect(() => {
    function isEditableTarget(target: EventTarget | null) {
      return target instanceof HTMLInputElement
        || target instanceof HTMLTextAreaElement
        || target instanceof HTMLSelectElement
        || (target instanceof HTMLElement && target.isContentEditable);
    }

    function handleAuthoringShortcut(event: KeyboardEvent) {
      const target = event.target;
      if (isEditableTarget(target)) return;
      if (variant === 'B' && event.code === 'Space') {
        event.preventDefault();
        if (!event.repeat) dispatch({ type: 'set-space-pan', active: true });
        return;
      }
      const key = event.key.toLocaleLowerCase();
      if ((event.ctrlKey || event.metaKey) && !event.altKey && key === 'a') {
        event.preventDefault();
        dispatch({ type: 'select-all' });
      } else if (key === 'v') {
        event.preventDefault();
        dispatch({ type: 'set-tool', tool: 'select' });
      } else if (key === 'h') {
        event.preventDefault();
        dispatch({ type: 'set-tool', tool: 'pan' });
      } else if (event.key === 'Escape' && state.spacePanActive) {
        event.preventDefault();
        dispatch({ type: 'set-space-pan', active: false });
      } else if (event.key === 'Escape' && state.activeTool === 'pan') {
        event.preventDefault();
        dispatch({ type: 'set-tool', tool: 'select' });
      } else if (event.key === 'Delete' || event.key === 'Backspace') {
        event.preventDefault();
        dispatch({ type: 'delete-selection' });
      }
    }

    function handleAuthoringShortcutRelease(event: KeyboardEvent) {
      if (variant !== 'B' || event.code !== 'Space' || !state.spacePanActive) return;
      event.preventDefault();
      dispatch({ type: 'set-space-pan', active: false });
    }

    function handleWindowBlur() {
      if (state.spacePanActive) dispatch({ type: 'set-space-pan', active: false });
    }

    window.addEventListener('keydown', handleAuthoringShortcut);
    window.addEventListener('keyup', handleAuthoringShortcutRelease);
    window.addEventListener('blur', handleWindowBlur);
    return () => {
      window.removeEventListener('keydown', handleAuthoringShortcut);
      window.removeEventListener('keyup', handleAuthoringShortcutRelease);
      window.removeEventListener('blur', handleWindowBlur);
    };
  }, [state.activeTool, state.spacePanActive, variant]);

  useEffect(() => {
    if (variant !== 'B' && state.spacePanActive) dispatch({ type: 'set-space-pan', active: false });
  }, [state.spacePanActive, variant]);

  const partProps = { state, dispatch, onRunPreview: runPreview, onCancelPreview: cancelPreview };

  return (
    <div className="rwtd-root" data-prototype="template-designer" data-variant={variant}>
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      {variant === 'A' && <VariantA {...partProps} />}
      {variant === 'B' && <VariantB {...partProps} />}
      {variant === 'C' && <VariantC {...partProps} />}
      <ConflictBanner state={state} dispatch={dispatch} />
      <InvalidSaveDialog state={state} dispatch={dispatch} />
      <BindingDialog state={state} dispatch={dispatch} />
      <NoticeToast state={state} dispatch={dispatch} />
      <PrototypeSwitcher current={variant} onChange={changeVariant} />
      <div className="unsupported-width" role="status">
        <span className="weave-mark" aria-hidden="true">RW</span>
        <strong>模板设计器需要更宽的工作区</strong>
        <span>请将窗口扩大到至少 1024px；模板内容不会丢失。</span>
      </div>
    </div>
  );
}
