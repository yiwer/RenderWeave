import { useMemo, useState } from 'react';

import './editor-state-model.css';

// Throwaway T09 prototype — EditorSession state architecture simulator.
// NOT product code. Validates the frozen editor rules from
// .scratch/renderweave-template-v1/issues/18-editor-preview-and-recovery.md.
import {
  type Fixture,
  type Session,
  SCENARIOS,
  basisId,
  cancelPreview,
  changePreviewParam,
  completePreview,
  confirmOverwrite,
  createFixture,
  discardDraft,
  editDsl,
  hardProblems,
  importBuffer,
  invalidatePreview,
  isDirty,
  offerInvalidConfirmation,
  openSession,
  recheckReady,
  reconcile,
  recordRecovery,
  redo,
  repairToStructured,
  resetProblems,
  restoreRecovery,
  save,
  serverDelete,
  serverDrift,
  startPreview,
  undo,
} from './model';

interface WalkState {
  scenarioIndex: number;
  stepIndex: number;
  results: Array<{ pass: boolean; message: string }>;
}

function StatePanel({ s }: { s: Session }) {
  return (
    <section className="esm-panel" aria-label="会话状态">
      <h3>EditorSession 状态（每次动作后刷新）</h3>
      <dl className="esm-state">
        <dt>mode</dt>
        <dd>{s.mode}</dd>
        <dt>baseline</dt>
        <dd>{s.baseline ? `r${s.baseline.revision} · ${s.baseline.contentHash.slice(0, 12)}…` : '—'}</dd>
        <dt>dirty（canonical 判定）</dt>
        <dd>{isDirty(s) ? 'true' : 'false'}</dd>
        <dt>mutation 锁</dt>
        <dd>{s.mutation.status} {s.mutation.kind ?? ''}</dd>
        <dt>overwrite 确认</dt>
        <dd>{s.overwrite ? (s.overwrite.offered ? `待确认（r${s.overwrite.confirmedRevision ?? '?'}）` : '已确认') : '—'}</dd>
        <dt>invalid 确认</dt>
        <dd>{s.invalidConfirmation ? '已绑定问题集' : '—'}</dd>
        <dt>recovery draft</dt>
        <dd>{s.recovery ? `base r${s.recovery.baseRevision}` : '—'}</dd>
        <dt>preview 槽 / 活跃 op</dt>
        <dd>
          {s.preview.slot ? `图片 ${s.preview.slot.imageId}` : '空'} /{' '}
          {s.preview.active ? `#${s.preview.active.opId}` : '无'} · gen={s.preview.generation}
        </dd>
        <dt>readiness</dt>
        <dd>{s.readiness}</dd>
        <dt>reconcile</dt>
        <dd>{s.reconcile.status}{s.reconcile.detail ? ` — ${s.reconcile.detail}` : ''}</dd>
        <dt>problems</dt>
        <dd>
          hard={hardProblems(s).length} dep=
          {s.problems.filter((p) => p.severity === 'dependency').length} runtime=
          {s.problems.filter((p) => p.severity === 'runtime').length}
        </dd>
        <dt>焦点（可访问性模拟）</dt>
        <dd>{s.focus}</dd>
        <dt>undo / redo</dt>
        <dd>{s.undoStack.length} / {s.redoStack.length}</dd>
      </dl>
      <div className="esm-log" aria-live="polite">
        <strong>最近事件：</strong>
        <span>{s.lastEvent}</span>
      </div>
      <details>
        <summary>事件日志（有界）</summary>
        <ul className="esm-log-list">
          {s.log.map((entry, index) => (
            <li key={`${index}-${entry}`}>{entry}</li>
          ))}
        </ul>
      </details>
    </section>
  );
}

function VerdictPanel() {
  return (
    <section className="esm-panel" aria-label="原型结论">
      <h3>T09 原型结论（verdict）</h3>
      <h4>可复用（来自 T17 Canvas Focus 的视觉与信息架构决策）</h4>
      <ul>
        <li>固定物理画布居中 + 左导航（结构/节点/资产/定义/交换）+ 右检视器 + 底部 dock 的 IA 基线。</li>
        <li>顶栏持续显示 Template 身份、永久 StaticSchema、readiness/revision 与保存状态。</li>
        <li>画布只提供非权威编辑反馈；权威预览是独立动作（同一 Evaluator/Profile 路径）。</li>
        <li>问题面板统一、键盘可达；非阻塞变化用克制 live region；失败聚焦问题摘要。</li>
      </ul>
      <h4>必须丢弃（刷新即失 / 无契约的内存状态模型）</h4>
      <ul>
        <li>无 baseline（revision/contentHash/canonical）的裸 working copy；dirty 必须按 exact canonicalization 判定，不能靠表单 touched/transport bytes。</li>
        <li>无 generation guard 的预览槽：任何 basis 输入变化立即撤下图，迟到结果必须丢弃。</li>
        <li>无条件 last-write-wins / 无 unknown reconciliation 的保存：transport 不明必须读 trusted current 分类（adopted/retryable/conflict/deleted/fail closed）。</li>
        <li>场景切换器当正式导航、无模式边界的 single editor：Raw Repair / Compatibility 必须显式互斥。</li>
        <li>每请求自造 identity 而不区分服务端 assetId 与前端本地 nodeId 的 UUID 职责。</li>
      </ul>
      <h4>验证通过的状态架构（按冻结规则）</h4>
      <ul>
        <li>单一 canonical baseline + 工作副本 + 结构命令 undo/redo（跨 baseline 清空）；save 成功以服务端 canonical 重建基线。</li>
        <li>mutation 单飞（同时间最多一个 save/invalid-confirmation 在途）；unknown 期间禁止新 mutation/preview/清 recovery。</li>
        <li>conflict overwrite：确认 → 重读 → 再提交 → 再漂移重新确认；invalid 确认绑定完整未截断问题集。</li>
        <li>preview：current-only basis（revision/hash/无本地分歧/样例/format/DPI/quality）、单槽单活跃、save-and-preview 顺序非原子。</li>
        <li>Local recovery：每 Template 一份（base+草稿+时间）、base==current 才直恢复、否则先显示基线变化再确认；best-effort 非持久承诺。</li>
        <li>dirty replacement guard 覆盖导入/migration 接受/恢复。</li>
      </ul>
      <h4>后续产品 Editor 实施纵切（占位-free，逐会话）</h4>
      <ol>
        <li>E1：open → trusted current → canonical baseline + readiness 重检 + 三模式骨架（只读投影）。</li>
        <li>E2：Structured 本地编辑 + canonical dirty + undo/redo + 预览 basis 失效/generation guard。</li>
        <li>E3：显式 save + expectedRevision + conflict overwrite（重读/重确认）。</li>
        <li>E4：依赖 ERROR 二次确认（绑定完整问题集）与 hard error 零写。</li>
        <li>E5：unknown → Save reconciliation 全分支 + mutation 锁纪律。</li>
        <li>E6：save-and-preview + preview slot/active op + 失败撤下 + 问题面板聚焦。</li>
        <li>E7：Local recovery draft 生命周期（记录/恢复/导出/放弃/7 天）。</li>
        <li>E8：import（bare/export）+ Raw Repair/Compatibility 模式与 dirty guard。</li>
        <li>E9：a11y（WCAG 2.2 AA 键盘流、live region、200% zoom、不支持宽度状态）与问题定位投影。</li>
      </ol>
      <p className="esm-note">
        本原型是 throwaway 逻辑演示（无持久化、无真实 API、无产品 route）；结论登记在 T09 票，后续 E1–E9
        切片待各自前置满足后按 single-writer 登记。
      </p>
    </section>
  );
}

export function EditorStateModelPrototype() {
  const [session, setSession] = useState<Session>(() => openSession(createFixture()));
  const [fixture, setFixture] = useState<Fixture>(createFixture);
  const [walk, setWalk] = useState<WalkState>({ scenarioIndex: 0, stepIndex: 0, results: [] });
  const [tab, setTab] = useState<'freeplay' | 'walkthrough' | 'verdict'>('freeplay');

  const scenario = useMemo(() => SCENARIOS[walk.scenarioIndex] ?? SCENARIOS[0]!, [walk.scenarioIndex]);

  const apply = (next: Session) => {
    setSession(next);
  };

  const runStep = (index: number) => {
    const step = scenario.steps[index];
    if (!step) {
      return;
    }
    const next = step.act ? step.act(session, fixture) : session;
    const check = step.expect(next);
    apply(next);
    setWalk((w) => ({
      ...w,
      stepIndex: Math.min(index + 1, scenario.steps.length - 1),
      results: [...w.results.slice(0, index), { pass: check.pass, message: check.message }],
    }));
  };

  const selectScenario = (index: number) => {
    setWalk({ scenarioIndex: index, stepIndex: 0, results: [] });
    setFixture(createFixture());
    apply(openSession(createFixture()));
  };

  const actions: Array<{ label: string; run: (s: Session) => Session }> = [
    { label: '打开 Template', run: () => openSession(fixture) },
    { label: '权威重检完成', run: (s) => recheckReady(s) },
    { label: '编辑（改 displayName）', run: (s) => editDsl(s, fixture.server.dsl.replace('"名片"', '"本地编辑"')) },
    { label: '撤销', run: (s) => undo(s) },
    { label: '重做', run: (s) => redo(s) },
    { label: '保存', run: (s) => save(s, fixture) },
    { label: '确认覆盖冲突', run: (s) => confirmOverwrite(s, fixture) },
    { label: '准备依赖 ERROR 二次确认', run: (s) => offerInvalidConfirmation(resetProblems(s, [{ code: 'TEMPLATE_ASSET_MISSING', pointer: '/designRoot/children/0', severity: 'dependency' }])) },
    { label: 'Save reconciliation（读 trusted current）', run: (s) => reconcile(s, fixture) },
    { label: '注入 unknown 结果（下次保存）', run: (s) => { setFixture({ ...fixture, forceUnknown: true }); return s; } },
    { label: '注入预览失败（下次预览）', run: (s) => { setFixture({ ...fixture, previewFails: true }); return s; } },
    { label: '注入 integrity 损坏', run: (s) => { setFixture({ ...fixture, integrityBroken: true }); return s; } },
    { label: '远端漂移（不同内容）', run: (s) => { setFixture(serverDrift({ ...fixture }, false)); return s; } },
    { label: '远端漂移（相同内容）', run: (s) => { setFixture(serverDrift({ ...fixture }, true)); return s; } },
    { label: '远端删除 Template', run: (s) => { setFixture(serverDelete({ ...fixture })); return s; } },
    { label: '启动权威预览 PNG/96', run: (s) => startPreview(s, fixture, 'PNG', 96, 90) },
    { label: '预览完成', run: (s) => completePreview(s, fixture) },
    { label: '取消预览', run: (s) => cancelPreview(s) },
    { label: '参数变化 144 DPI', run: (s) => changePreviewParam(s, 'PNG', 144, 90) },
    { label: '手动失效预览 basis', run: (s) => invalidatePreview(s, '手动') },
    { label: '导入 bare DesignDSL', run: (s) => importBuffer(s, fixture.server.dsl.replace('"名片"', '"导入内容"')) },
    { label: '导入非法缓冲（→ Raw Repair）', run: (s) => importBuffer(s, '{"dslVersion":') },
    { label: 'Raw Repair 修复完成', run: (s) => repairToStructured({ ...s, rawBuffer: fixture.server.dsl }) },
    { label: '记录 Local recovery', run: (s) => recordRecovery(s) },
    { label: '恢复 Local recovery', run: (s) => restoreRecovery(s, fixture) },
    { label: '放弃本地草稿', run: (s) => discardDraft(s) },
  ];

  return (
    <div className="esm-root">
      <header className="esm-header">
        <h1>EditorSession 状态架构原型（T09 · throwaway）</h1>
        <p>
          模拟冻结的编辑器状态规则：revision-aware baseline、三模式、dirty guard、local recovery、
          conflict/unknown reconciliation、current-only 权威预览与失败撤下。
        </p>
      </header>
      <nav className="esm-tabs" aria-label="原型分区">
        {(
          [
            ['freeplay', '自由操作'],
            ['walkthrough', '引导走查（10 场景）'],
            ['verdict', '结论与切片'],
          ] as const
        ).map(([id, label]) => (
          <button key={id} type="button" className={tab === id ? 'active' : ''} onClick={() => setTab(id)}>
            {label}
          </button>
        ))}
      </nav>

      {tab === 'freeplay' && (
        <div className="esm-freeplay">
          <section className="esm-panel" aria-label="自由操作">
            <h3>自由操作（服务器 fixture 在内存中，可随时重开）</h3>
            <div className="esm-actions">
              {actions.map((action) => (
                <button key={action.label} type="button" onClick={() => apply(action.run(session))}>
                  {action.label}
                </button>
              ))}
            </div>
          </section>
          <StatePanel s={session} />
        </div>
      )}

      {tab === 'walkthrough' && (
        <div className="esm-walk">
          <div className="esm-scenario-list" aria-label="场景列表">
            {SCENARIOS.map((sc, index) => (
              <button
                key={sc.id}
                type="button"
                className={index === walk.scenarioIndex ? 'active' : ''}
                onClick={() => selectScenario(index)}
              >
                {index + 1}. {sc.title}
              </button>
            ))}
          </div>
          <section className="esm-panel" aria-label="当前场景">
            <h3>
              {walk.scenarioIndex + 1}. {scenario.title}
            </h3>
            <p className="esm-goal">{scenario.goal}</p>
            <ol className="esm-steps">
              {scenario.steps.map((step, index) => {
                const result = walk.results[index];
                return (
                  <li key={step.label}>
                    <div className="esm-step-row">
                      <span>
                        {index + 1}. {step.label}
                      </span>
                      {index === walk.stepIndex && (
                        <button type="button" onClick={() => runStep(index)}>
                          执行
                        </button>
                      )}
                      {result && (
                        <span className={result.pass ? 'esm-pass' : 'esm-fail'} aria-live="polite">
                          {result.pass ? '✓' : '✗'} {result.message}
                        </span>
                      )}
                    </div>
                  </li>
                );
              })}
            </ol>
            <div className="esm-walk-nav">
              <button
                type="button"
                disabled={walk.scenarioIndex === 0}
                onClick={() => selectScenario(walk.scenarioIndex - 1)}
              >
                上一场景
              </button>
              <button
                type="button"
                disabled={walk.scenarioIndex >= SCENARIOS.length - 1}
                onClick={() => selectScenario(walk.scenarioIndex + 1)}
              >
                下一场景
              </button>
            </div>
          </section>
          <StatePanel s={session} />
        </div>
      )}

      {tab === 'verdict' && <VerdictPanel />}

      <footer className="esm-footer">
        当前服务器 fixture：revision {fixture.server.revision} · lifecycle {fixture.server.lifecycle} · basis{' '}
        {session.preview.basis ? basisId(session.preview.basis) : '—'} · 本原型无持久化、无真实 API、不进入产品 route。
      </footer>
    </div>
  );
}
