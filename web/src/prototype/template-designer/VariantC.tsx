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
  const sourceTab = ['data', 'definitions', 'tree', 'exchange'].includes(state.leftTab)
    ? state.leftTab
    : 'data';
  return (
    <div className="td-shell td-c rwtd rwtd-binding-bench">
      <TdChrome {...props} layoutName="C · Binding Bench" />
      <ScenarioBar state={state} dispatch={dispatch} />
      <div className="td-c-body">
        <aside className="td-c-left" aria-label="绑定来源">
          <header className="rwtd-panel-header">
            <span className="rwtd-panel-title"><strong>来源</strong></span>
            <span className="rwtd-panel-context">typed</span>
          </header>
          <div className="rwtd-source-tabs" role="tablist" aria-label="绑定来源视图">
            {([
              ['data', '样本'],
              ['definitions', '定义'],
              ['tree', '结构'],
              ['exchange', '交换'],
            ] as const).map(([tab, label]) => (
              <button
                key={tab}
                type="button"
                role="tab"
                aria-selected={sourceTab === tab}
                className={sourceTab === tab ? 'active' : ''}
                onClick={() => dispatch({ type: 'set-tab', tab })}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="rwtd-panel-scroll" tabIndex={0} aria-label="绑定来源内容">
            {sourceTab === 'data' ? <DataPanel state={state} /> : null}
            {sourceTab === 'definitions' ? <DefinitionsPanel dispatch={dispatch} /> : null}
            {sourceTab === 'tree' ? <NodeTree state={state} dispatch={dispatch} /> : null}
            {sourceTab === 'exchange' ? <ExchangePanel state={state} dispatch={dispatch} /> : null}
          </div>
        </aside>
        <main className="td-c-center" id="main-content">
          <section className="td-c-block td-c-hero" aria-label="绑定目标与映射">
            <Inspector state={state} dispatch={dispatch} initialMode="binding" />
          </section>
          <section className="td-c-block rwtd-canvas-reference" aria-label="草稿画布引用">
            <header className="rwtd-panel-header">
              <span className="rwtd-panel-title"><strong>画布引用</strong></span>
              <span className="rwtd-panel-context">非权威</span>
            </header>
            <Artboard state={state} dispatch={dispatch} compact />
          </section>
        </main>
        <aside className="td-c-right" aria-label="验证与输出">
          <section className="td-c-block rwtd-preview-pane" aria-label="权威预览">
            <PreviewPanel {...props} />
          </section>
          <section className="td-c-block rwtd-problems-pane" aria-label="问题">
            <header className="rwtd-panel-header">
              <span className="rwtd-panel-title"><strong>问题</strong></span>
              <span className="rwtd-panel-context">current</span>
            </header>
            <ProblemsList state={state} dispatch={dispatch} />
          </section>
        </aside>
      </div>
    </div>
  );
}
