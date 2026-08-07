export type PrototypeVariant = 'A' | 'B' | 'C';
export type EditorView = 'form' | 'map';
export type FieldType = 'text' | 'decimal' | 'date' | 'time' | 'boolean' | 'reference' | 'array';

export interface SchemaField {
  key: string;
  label: string;
  type: FieldType;
  required: boolean;
  detail: string;
  confidence: 'confirmed' | 'review';
}

export interface PrototypeEditorState {
  schemaKey: string;
  displayName: string;
  description: string;
  revision: number;
  dirty: boolean;
  view: EditorView;
  selectedFieldKey: string;
  fields: SchemaField[];
}

export type PrototypeEditorAction =
  | { type: 'select'; fieldKey: string }
  | { type: 'toggle-required'; fieldKey: string }
  | { type: 'set-view'; view: EditorView }
  | { type: 'move'; fieldKey: string; direction: -1 | 1 }
  | { type: 'add-field' }
  | { type: 'save' };

export const initialEditorState: PrototypeEditorState = {
  schemaKey: 'campaign-card',
  displayName: '商品推广卡',
  description: '用于电商活动主视觉的聚合数据结构',
  revision: 7,
  dirty: true,
  view: 'form',
  selectedFieldKey: 'price',
  fields: [
    {
      key: 'title',
      label: '主标题',
      type: 'text',
      required: true,
      detail: '1–48 code points',
      confidence: 'confirmed',
    },
    {
      key: 'price',
      label: '活动价',
      type: 'decimal',
      required: true,
      detail: 'min 0 · multipleOf 0.01',
      confidence: 'confirmed',
    },
    {
      key: 'launchDate',
      label: '上线日期',
      type: 'date',
      required: false,
      detail: 'YYYY-MM-DD',
      confidence: 'confirmed',
    },
    {
      key: 'cutoffTime',
      label: '截止时间',
      type: 'time',
      required: false,
      detail: 'HH:mm:ss',
      confidence: 'review',
    },
    {
      key: 'tags',
      label: '卖点标签',
      type: 'array',
      required: false,
      detail: 'Array[text] · maxItems 6',
      confidence: 'confirmed',
    },
    {
      key: 'brand',
      label: '品牌信息',
      type: 'reference',
      required: true,
      detail: 'brand-profile@v2',
      confidence: 'confirmed',
    },
  ],
};

export function editorReducer(
  state: PrototypeEditorState,
  action: PrototypeEditorAction,
): PrototypeEditorState {
  switch (action.type) {
    case 'select':
      return { ...state, selectedFieldKey: action.fieldKey };
    case 'set-view':
      return { ...state, view: action.view };
    case 'toggle-required':
      return {
        ...state,
        dirty: true,
        fields: state.fields.map((field) =>
          field.key === action.fieldKey ? { ...field, required: !field.required } : field,
        ),
      };
    case 'move': {
      const sourceIndex = state.fields.findIndex((field) => field.key === action.fieldKey);
      const targetIndex = sourceIndex + action.direction;
      if (sourceIndex < 0 || targetIndex < 0 || targetIndex >= state.fields.length) {
        return state;
      }
      const fields = [...state.fields];
      const source = fields[sourceIndex];
      const target = fields[targetIndex];
      if (!source || !target) {
        return state;
      }
      fields[sourceIndex] = target;
      fields[targetIndex] = source;
      return { ...state, fields, dirty: true };
    }
    case 'add-field': {
      const sequence = state.fields.length + 1;
      const field: SchemaField = {
        key: `subtitle-${sequence}`,
        label: '新字段',
        type: 'text',
        required: false,
        detail: '未设置约束',
        confidence: 'confirmed',
      };
      return {
        ...state,
        fields: [...state.fields, field],
        selectedFieldKey: field.key,
        dirty: true,
      };
    }
    case 'save':
      return { ...state, revision: state.dirty ? state.revision + 1 : state.revision, dirty: false };
  }
}

export const variantNames: Record<PrototypeVariant, string> = {
  A: 'Column Workbench',
  B: 'Map Studio',
  C: 'Schema Ledger',
};

export function parseVariant(value: string | null): PrototypeVariant {
  return value === 'B' || value === 'C' ? value : 'A';
}

export const fieldTypeLabels: Record<FieldType, string> = {
  text: '文本',
  decimal: '数值',
  date: '日期',
  time: '时间',
  boolean: '布尔',
  reference: '引用',
  array: '数组',
};
