// @vitest-environment happy-dom

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useMemo, useReducer } from 'react';
import { afterEach, describe, expect, it } from 'vitest';

import { FormSurface } from './EditorSurfaces';
import { FieldInspector } from './FieldInspector';
import { createNewEditorSession, editorReducer } from './editor-session';
import { serializeDefinition } from './editor-types';
import { localDiagnostics } from './editor-validation';

afterEach(cleanup);

describe('Schema Studio production components', () => {
  it('edits an array item reference and enforces the object-array uniqueItems affordance', () => {
    const { container } = render(<StudioHarness />);
    const typeSelect = screen.getByLabelText('字段类型') as HTMLSelectElement;
    expect([...typeSelect.options].map((option) => option.value)).toEqual([
      'text', 'decimal', 'date', 'time', 'boolean', 'reference', 'array',
    ]);

    fireEvent.change(typeSelect, { target: { value: 'array' } });
    const uniqueItems = screen.getByRole('checkbox', { name: /uniqueItems/ }) as HTMLInputElement;
    expect(uniqueItems.disabled).toBe(false);
    fireEvent.click(uniqueItems);
    expect(uniqueItems.checked).toBe(true);

    fireEvent.change(screen.getByLabelText('数组元素类型'), { target: { value: 'reference' } });
    expect(uniqueItems.disabled).toBe(true);
    expect(uniqueItems.checked).toBe(false);
    expect(screen.getByText(/对象数组不支持唯一性约束/)).toBeTruthy();

    const schemaKey = container.querySelector<HTMLInputElement>('[data-pointer="/definition/fields/0/value/items/ref/schemaKey"]');
    expect(schemaKey).not.toBeNull();
    fireEvent.change(schemaKey!, { target: { value: 'child-schema' } });
    expect(container.querySelector('.dsl-preview')?.textContent).toContain('child-schema');
  });

  it('keeps high-density row operations on the shared reducer', () => {
    render(<StudioHarness />);
    expect(screen.getAllByRole('article')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: '复制字段' }));
    expect(screen.getAllByRole('article')).toHaveLength(2);
    fireEvent.click(screen.getAllByRole('button', { name: /删除/ })[0]!);
    expect(screen.getAllByRole('article')).toHaveLength(1);
  });
});

function StudioHarness() {
  const [session, dispatch] = useReducer(editorReducer, undefined, createNewEditorSession);
  const diagnostics = useMemo(() => localDiagnostics(session), [session]);
  const selectedIndex = Math.max(0, session.fields.findIndex((field) => field.rowKey === session.selectedRowKey));
  const selected = session.fields[selectedIndex];
  let preview: string;
  try {
    preview = serializeDefinition(session.displayName, session.description, session.fields, true);
  } catch {
    preview = 'invalid';
  }
  return (
    <>
      <FormSurface
        session={session}
        diagnostics={diagnostics}
        search=""
        dispatch={dispatch}
        onSelectField={(rowKey) => dispatch({ type: 'select-field', rowKey })}
        onAddField={() => dispatch({ type: 'add-field' })}
      />
      <FieldInspector
        field={selected}
        fieldIndex={selectedIndex}
        allFields={session.fields}
        revision={session.revision}
        dirty={session.dirty}
        diagnostics={diagnostics}
        definitionPreview={preview}
        open
        dispatch={dispatch}
        onClose={() => undefined}
        onAddField={() => dispatch({ type: 'add-field' })}
        onTouch={() => undefined}
        showProblem={() => false}
      />
    </>
  );
}
