import { useCallback, useEffect, useReducer, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';

import { designerReducer, initialDesignerState, parseVariant, type PrototypeVariant } from './model';
import { PrototypeSwitcher } from './PrototypeSwitcher';
import { ConflictBanner, BindingDialog, InvalidSaveDialog, NoticeToast } from './SharedParts';
import { VariantA } from './VariantA';
import { VariantB } from './VariantB';
import { VariantC } from './VariantC';

/**
 * PROTOTYPE — throwaway route /prototype/template-designer?variant=A|B|C
 * 三个在线 Template 设计器变体,验证 issues/17-authoring-workflow-prototype 的信息架构。
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

  const partProps = { state, dispatch, onRunPreview: runPreview, onCancelPreview: cancelPreview };

  return (
    <div data-prototype="template-designer">
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
        <strong>RenderWeave Template 设计器需要至少 1024px 宽度</strong>
        <span>请扩大窗口后继续模板设计。</span>
      </div>
    </div>
  );
}
