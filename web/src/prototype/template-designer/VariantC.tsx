import type { PartProps } from './SharedParts';
import {
  Artboard,
  DataPanel,
  DefinitionsPanel,
  ExchangePanel,
  Inspector,
  NodeTree,
  PreviewPanel,
  ProblemsList,
  ScenarioBar,
  TdChrome,
} from './SharedParts';

/**
 * 变体 C — Binding Bench 绑定工作台。
 * 围绕 RenderWeave 独有的 数据 → Binding → 权威预览 闭环重构:
 * 左列结构树 + 缩略画布(引用),中列是选中节点的「绑定板」(每个可绑定属性的
 * 三态:baseline / 覆盖 / ABSENT·ERROR),右列样本数据 + 权威预览 + 问题。
 * 主要供能:绑定关系本身;画布退居参考位。
 */
export function VariantC(props: PartProps) {
  const { state, dispatch } = props;
  return (
    <div className="td-shell td-c">
      <TdChrome {...props} layoutName="C · 绑定工作台" />
      <ScenarioBar state={state} dispatch={dispatch} />
      <div className="td-c-body">
        <div className="td-c-left">
          <section className="td-c-block" aria-label="结构树">
            <NodeTree state={state} dispatch={dispatch} />
          </section>
          <section className="td-c-block" aria-label="缩略草稿画布">
            <Artboard state={state} dispatch={dispatch} compact />
          </section>
        </div>
        <main className="td-c-center" id="main-content">
          <section className="td-c-block td-c-hero" aria-label="绑定板">
            <Inspector state={state} dispatch={dispatch} />
          </section>
          <section className="td-c-block" aria-label="定义">
            {state.leftTab === 'exchange'
              ? <ExchangePanel state={state} dispatch={dispatch} />
              : <DefinitionsPanel dispatch={dispatch} />}
          </section>
        </main>
        <div className="td-c-right">
          <section className="td-c-block" aria-label="样本数据">
            <DataPanel state={state} />
          </section>
          <section className="td-c-block" aria-label="权威预览">
            <PreviewPanel {...props} />
          </section>
          <section className="td-c-block" aria-label="问题">
            <ProblemsList state={state} dispatch={dispatch} />
          </section>
        </div>
      </div>
    </div>
  );
}
