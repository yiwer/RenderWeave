import { useCallback, useMemo, useReducer } from 'react';
import { useSearchParams } from 'react-router-dom';

import { editorReducer, initialEditorState, parseVariant, type PrototypeVariant } from './model';
import { PrototypeSwitcher } from './PrototypeSwitcher';
import { VariantA } from './VariantA';
import { VariantB } from './VariantB';
import { VariantC } from './VariantC';

export function SchemaStudioPrototype() {
  const [searchParams, setSearchParams] = useSearchParams();
  const variant = parseVariant(searchParams.get('variant'));
  const [state, dispatch] = useReducer(editorReducer, initialEditorState);
  const selectedField = useMemo(
    () => state.fields.find((field) => field.key === state.selectedFieldKey) ?? state.fields[0],
    [state.fields, state.selectedFieldKey],
  );

  const changeVariant = useCallback(
    (next: PrototypeVariant) => {
      const params = new URLSearchParams(searchParams);
      params.set('variant', next);
      setSearchParams(params, { replace: true });
    },
    [searchParams, setSearchParams],
  );

  if (!selectedField) {
    return null;
  }

  return (
    <div data-prototype="schema-studio">
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      {variant === 'A' && <VariantA state={state} selectedField={selectedField} dispatch={dispatch} />}
      {variant === 'B' && <VariantB state={state} selectedField={selectedField} dispatch={dispatch} />}
      {variant === 'C' && <VariantC state={state} selectedField={selectedField} dispatch={dispatch} />}
      <PrototypeSwitcher current={variant} onChange={changeVariant} />
      <div className="unsupported-width" role="status">
        <strong>RenderWeave v1 需要至少 1024px 宽度</strong>
        <span>请扩大窗口后继续 Schema 设计。</span>
      </div>
    </div>
  );
}
