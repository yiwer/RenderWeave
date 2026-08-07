import type { Dispatch } from 'react';

import type { PrototypeEditorAction, PrototypeEditorState, SchemaField } from './model';
import {
  AddFieldButton,
  FieldInspector,
  FieldRow,
  ProductChrome,
  ResourceRail,
  SchemaHeading,
  ViewToggle,
} from './SharedPrototypeParts';

interface VariantProps {
  state: PrototypeEditorState;
  selectedField: SchemaField;
  dispatch: Dispatch<PrototypeEditorAction>;
}

export function VariantA({ state, selectedField, dispatch }: VariantProps) {
  return (
    <div className="variant-shell variant-a">
      <ProductChrome state={state} dispatch={dispatch} layoutName="Column Workbench" />
      <div className="variant-a-body">
        <ResourceRail />
        <main className="form-workspace" id="main-content">
          <SchemaHeading state={state} />
          <div className="workspace-toolbar">
            <ViewToggle state={state} dispatch={dispatch} />
            <span>{state.fields.length} fields · additionalProperties=true</span>
          </div>
          <section className="field-list" aria-label="Schema 字段">
            {state.fields.map((field, index) => (
              <FieldRow
                key={field.key}
                field={field}
                index={index}
                total={state.fields.length}
                selected={field.key === state.selectedFieldKey}
                dispatch={dispatch}
              />
            ))}
            <AddFieldButton dispatch={dispatch} />
          </section>
        </main>
        <FieldInspector field={selectedField} />
      </div>
    </div>
  );
}
