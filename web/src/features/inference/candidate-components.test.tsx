// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useReducer } from 'react';
import { afterEach, describe, expect, it } from 'vitest';

import { CandidateInspector } from './CandidateInspector';
import { CandidateBundleNav } from './CandidateSurfaces';
import { candidateReviewReducer, createCandidateReviewState, findSelected } from './candidate-session';
import { snapshot } from './candidate-session.test';

afterEach(cleanup);

describe('Candidate review components', () => {
  it('makes the scrollable Candidate Schema list keyboard reachable', () => {
    render(<BundleNavHarness />);

    expect(screen.getByRole('region', { name: '候选数据结构列表' }).tabIndex).toBe(0);
  });

  it('keeps frozen Candidate navigation inspectable while disabling mutations', () => {
    const { container } = render(<BundleNavHarness readOnly />);

    expect((screen.getByRole('button', { name: '新增' }) as HTMLButtonElement).disabled).toBe(true);
    expect((container.querySelector('.bundle-schema-select') as HTMLButtonElement).disabled).toBe(false);
  });

  it('does not allocate an order-control column for a single Schema', () => {
    const { container } = render(<BundleNavHarness />);

    expect(container.querySelectorAll('.bundle-order-actions')).toHaveLength(0);
    expect(container.querySelector('.bundle-schema-entry.sortable')).toBeNull();
  });

  it('keeps frozen Candidate evidence navigable while disabling definition edits', () => {
    render(<InspectorHarness readOnly />);

    expect((screen.getByLabelText('Candidate 字段类型') as HTMLSelectElement).disabled).toBe(true);
    expect((screen.getByRole('tab', { name: '查看证据图片 2' }) as HTMLButtonElement).disabled).toBe(false);
    expect(screen.queryByRole('button', { name: '确认当前项' })).toBeNull();
  });

  it('does not offer removal for the immutable root Schema', () => {
    render(<SchemaInspectorHarness />);

    expect(screen.queryByRole('button', { name: '移除当前项' })).toBeNull();
    expect(screen.getByText('根数据结构不可移除；可继续修改名称与 schemaKey。')).toBeTruthy();
  });

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

function InspectorHarness({ readOnly = false }: { readOnly?: boolean }) {
  const [state, dispatch] = useReducer(candidateReviewReducer, snapshot(), createCandidateReviewState);
  const selectedSchema = state.draft.schemas[0]!;
  const selectedField = selectedSchema.fields[0]!;
  const selected = findSelected({ ...state, selectedFieldId: selectedField.candidateFieldId });
  return <CandidateInspector state={state} schema={selected.schema!} field={selected.field} dispatch={dispatch} readOnly={readOnly} />;
}

function BundleNavHarness({ readOnly = false }: { readOnly?: boolean }) {
  const [state, dispatch] = useReducer(candidateReviewReducer, snapshot(), createCandidateReviewState);
  return <CandidateBundleNav state={state} dispatch={dispatch} readOnly={readOnly} />;
}

function SchemaInspectorHarness() {
  const [state, dispatch] = useReducer(candidateReviewReducer, snapshot(), createCandidateReviewState);
  const schema = state.draft.schemas.find((item) => item.candidateSchemaId === state.draft.rootCandidateSchemaId)!;
  return <CandidateInspector state={state} schema={schema} field={null} dispatch={dispatch} />;
}
