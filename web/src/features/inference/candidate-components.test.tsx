// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useReducer } from 'react';
import { afterEach, describe, expect, it } from 'vitest';

import { CandidateInspector } from './CandidateInspector';
import { candidateReviewReducer, createCandidateReviewState, findSelected } from './candidate-session';
import { snapshot } from './candidate-session.test';

afterEach(cleanup);

describe('Candidate review components', () => {
  it('resolves an unresolved AI field by editing its type and keeps evidence visible', () => {
    const { container } = render(<InspectorHarness />);
    fireEvent.change(screen.getByLabelText('Candidate 字段类型'), { target: { value: 'ARRAY' } });

    expect(screen.getByLabelText('Candidate 数组元素类型')).toBeTruthy();
    const itemType = screen.getByLabelText('Candidate 数组元素类型') as HTMLSelectElement;
    expect([...itemType.options].map((option) => option.value)).not.toContain('ARRAY');
    expect(screen.getByText('编辑解决')).toBeTruthy();
    expect(container.querySelectorAll('[data-evidence-box]')).toHaveLength(1);
    expect(screen.getByText('/total')).toBeTruthy();
  });

  it('edits type-specific constraints using Candidate string literals', () => {
    render(<InspectorHarness />);
    fireEvent.change(screen.getByLabelText('Candidate 字段类型'), { target: { value: 'TEXT' } });
    fireEvent.click(screen.getByRole('checkbox', { name: '启用最小长度' }));
    fireEvent.change(screen.getByLabelText('最小长度'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('checkbox', { name: '启用允许值' }));
    fireEvent.change(screen.getByLabelText('允许值'), { target: { value: '["CNY","USD"]' } });

    expect((screen.getByLabelText('最小长度') as HTMLInputElement).value).toBe('3');
    expect((screen.getByLabelText('允许值') as HTMLTextAreaElement).value).toBe('["CNY","USD"]');
    expect(screen.getByText('编辑解决')).toBeTruthy();
  });

  it('lets reviewers inspect every linked image and only draws that image boxes', () => {
    const { container } = render(<InspectorHarness />);
    expect(screen.getByRole('img', { name: '证据图片 1' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: '查看证据图片 2' })).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: '查看证据图片 2' }));

    expect(screen.getByRole('img', { name: '证据图片 2' })).toBeTruthy();
    expect(container.querySelectorAll('[data-evidence-box]')).toHaveLength(1);
  });

  it('exposes only an item-level confirmation action', () => {
    render(<InspectorHarness />);
    expect((screen.getByRole('button', { name: '确认当前项' }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByRole('button', { name: /全部确认/ })).toBeNull();
  });
});

function InspectorHarness() {
  const [state, dispatch] = useReducer(candidateReviewReducer, snapshot(), createCandidateReviewState);
  const selectedSchema = state.draft.schemas[0]!;
  const selectedField = selectedSchema.fields[0]!;
  const selected = findSelected({ ...state, selectedFieldId: selectedField.candidateFieldId });
  return <CandidateInspector state={state} schema={selected.schema!} field={selected.field} dispatch={dispatch} />;
}
