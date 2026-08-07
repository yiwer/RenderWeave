import { Background, Controls, MarkerType, ReactFlow, type Edge, type Node } from '@xyflow/react';
import { AlertTriangle, CheckCircle2, PanelRightOpen, Plus } from 'lucide-react';
import { useMemo, type Dispatch } from 'react';

import { fieldTypeLabels, type PrototypeEditorAction, type PrototypeEditorState, type SchemaField } from './model';
import { FieldInspector, ProductChrome, ResourceRail, SchemaHeading, ViewToggle } from './SharedPrototypeParts';

interface VariantProps {
  state: PrototypeEditorState;
  selectedField: SchemaField;
  dispatch: Dispatch<PrototypeEditorAction>;
}

export function VariantB({ state, selectedField, dispatch }: VariantProps) {
  const nodes = useMemo<Node[]>(
    () => [
      {
        id: 'root',
        position: { x: 40, y: 154 },
        data: { label: `${state.displayName}\n${state.schemaKey}` },
        className: 'map-node map-root-node',
        draggable: false,
      },
      ...state.fields.map((field, index) => ({
        id: `field:${field.key}`,
        position: { x: 390, y: index * 84 + 24 },
        data: { label: `${field.label}  ·  ${fieldTypeLabels[field.type]}\n${field.key}` },
        className: `map-node ${field.key === state.selectedFieldKey ? 'map-selected-node' : ''}`,
        draggable: false,
      })),
    ],
    [state.displayName, state.fields, state.schemaKey, state.selectedFieldKey],
  );

  const edges = useMemo<Edge[]>(
    () =>
      state.fields.map((field) => ({
        id: `root-${field.key}`,
        source: 'root',
        target: `field:${field.key}`,
        markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14 },
        style: { stroke: field.required ? '#a9583e' : '#aaa197', strokeWidth: field.required ? 1.8 : 1.2 },
      })),
    [state.fields],
  );

  return (
    <div className="variant-shell variant-b">
      <ProductChrome state={state} dispatch={dispatch} layoutName="Map Studio" />
      <div className="variant-b-body">
        <ResourceRail />
        <main className="map-workspace" id="main-content">
          <div className="map-topbar">
            <SchemaHeading state={state} />
            <div className="map-actions">
              <ViewToggle state={state} dispatch={dispatch} />
              <button type="button" className="button ghost-button" onClick={() => dispatch({ type: 'add-field' })}>
                <Plus aria-hidden="true" size={16} />
                字段
              </button>
            </div>
          </div>
          <section className="map-canvas" aria-label="Schema 树状图；完整键盘编辑请切换表单模式">
            <ReactFlow
              key={state.fields.length}
              nodes={nodes}
              edges={edges}
              fitView
              fitViewOptions={{ padding: 0.18, maxZoom: 1.05 }}
              minZoom={0.55}
              maxZoom={1.3}
              nodesConnectable={false}
              nodesDraggable={false}
              elementsSelectable
              onNodeClick={(_, node) => {
                if (node.id.startsWith('field:')) {
                  dispatch({ type: 'select', fieldKey: node.id.slice('field:'.length) });
                }
              }}
              proOptions={{ hideAttribution: true }}
            >
              <Background color="#ded7cd" gap={22} size={1} />
              <Controls showInteractive={false} position="bottom-left" />
            </ReactFlow>
            <div className="map-legend">
              <span><i className="legend-required" /> 必填</span>
              <span><i /> 可选</span>
              <span>拖拽不保存坐标</span>
            </div>
          </section>
          <section className="diagnostic-dock" aria-label="诊断摘要">
            <div>
              <CheckCircle2 aria-hidden="true" size={17} />
              <span><strong>结构有效</strong> · 引用图无环</span>
            </div>
            <div className="dock-warning">
              <AlertTriangle aria-hidden="true" size={17} />
              <span><strong>1 项待确认</strong> · /cutoffTime</span>
            </div>
            <button type="button" className="button ghost-button">
              <PanelRightOpen aria-hidden="true" size={16} />
              展开全部问题
            </button>
          </section>
        </main>
        <FieldInspector field={selectedField} />
      </div>
    </div>
  );
}
