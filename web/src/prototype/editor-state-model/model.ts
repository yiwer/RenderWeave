// Throwaway T09 prototype model — EditorSession state architecture simulation.
// This file is NOT product code. It encodes the frozen editor rules from
// .scratch/renderweave-template-v1/issues/18-editor-preview-and-recovery.md
// as a deterministic fixture state machine for validation.

export type Lifecycle = 'ACTIVE' | 'DELETED';
export type Readiness = 'checking' | 'READY' | 'INVALID' | 'STALE' | 'DELETED';
export type Mode = 'structured' | 'rawRepair' | 'compatibility';
export type MutationStatus = 'idle' | 'inFlight' | 'unknown';
export type ReconcileStatus =
  | 'none'
  | 'unknown'
  | 'adopted'
  | 'retryable'
  | 'conflict'
  | 'deleted'
  | 'failedClosed';

export interface Problem {
  code: string;
  pointer: string;
  severity: 'hard' | 'dependency' | 'runtime';
}

export interface ServerState {
  revision: number;
  contentHash: string;
  dsl: string;
  lifecycle: Lifecycle;
  readiness: Readiness;
}

export interface RecoveryDraft {
  baseRevision: number;
  baseContentHash: string;
  draft: string;
  updatedAt: string;
}

export interface PreviewBasis {
  revision: number;
  contentHash: string;
  samples: number;
  format: 'PNG' | 'JPEG';
  dpi: number;
  quality: number;
}

export interface PreviewSlot {
  imageId: string;
  basis: string;
}

export interface ActivePreview {
  opId: number;
  basis: string;
  generation: number;
}

export interface Session {
  opened: boolean;
  mode: Mode;
  baseline: { revision: number; contentHash: string; dsl: string } | null;
  workingDsl: string | null;
  rawBuffer: string | null;
  undoStack: string[];
  redoStack: string[];
  mutation: { status: MutationStatus; kind: 'save' | 'invalidConfirm' | null };
  overwrite: {
    offered: boolean;
    offeredRevision: number | null;
    confirmedRevision: number | null;
  } | null;
  invalidConfirmation: { fingerprint: string } | null;
  recovery: RecoveryDraft | null;
  preview: {
    slot: PreviewSlot | null;
    active: ActivePreview | null;
    generation: number;
    basis: PreviewBasis | null;
  };
  reconcile: { status: ReconcileStatus; detail: string };
  problems: Problem[];
  readiness: Readiness;
  focus: string;
  lastEvent: string;
  log: string[];
}

export interface Fixture {
  server: ServerState;
  forceUnknown: boolean;
  latePreview: boolean;
  previewFails: boolean;
  integrityBroken: boolean;
  opCounter: number;
}

const TEMPLATE_DSL = `{"dslVersion":"renderweave-design/1.0","displayName":"名片","definitions":[],"designRoot":{"nodeId":"11111111-1111-4111-8111-111111111111","kind":"canvas","widthMm":90,"heightMm":54,"bindings":[],"children":[]}}`;

export function hashOf(dsl: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < dsl.length; i++) {
    h ^= dsl.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return 'sha256:' + (h >>> 0).toString(16).padStart(8, '0').repeat(8);
}

/** Simplified canonical form: parse strict JSON, sort object keys, compact. */
export function canonicalize(dsl: string): string | null {
  try {
    const parsed = JSON.parse(dsl) as unknown;
    return JSON.stringify(sortDeep(parsed));
  } catch {
    return null;
  }
}

function sortDeep(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sortDeep);
  }
  if (value !== null && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    const out: Record<string, unknown> = {};
    for (const key of Object.keys(record).sort()) {
      out[key] = sortDeep(record[key]);
    }
    return out;
  }
  return value;
}

export function createFixture(): Fixture {
  return {
    server: {
      revision: 3,
      contentHash: hashOf(TEMPLATE_DSL),
      dsl: TEMPLATE_DSL,
      lifecycle: 'ACTIVE',
      readiness: 'READY',
    },
    forceUnknown: false,
    latePreview: false,
    previewFails: false,
    integrityBroken: false,
    opCounter: 0,
  };
}

export function openSession(fx: Fixture): Session {
  return {
    opened: true,
    mode: 'structured',
    baseline: {
      revision: fx.server.revision,
      contentHash: fx.server.contentHash,
      dsl: fx.server.dsl,
    },
    workingDsl: fx.server.dsl,
    rawBuffer: null,
    undoStack: [],
    redoStack: [],
    mutation: { status: 'idle', kind: null },
    overwrite: null,
    invalidConfirmation: null,
    recovery: null,
    preview: { slot: null, active: null, generation: 0, basis: null },
    reconcile: { status: 'none', detail: '' },
    problems: [],
    readiness: 'checking',
    focus: 'none',
    lastEvent: 'open 请求已发出，权威重检中',
    log: ['open: trusted current 读取，recheck 进行中'],
  };
}

export function recheckReady(s: Session): Session {
  return { ...s, readiness: 'READY', lastEvent: '权威重检完成：READY，预览已解锁', log: log(s, 'recheck → READY') };
}

function log(s: Session, event: string): string[] {
  return [event, ...s.log].slice(0, 40);
}

function nextBasis(s: Session, format: 'PNG' | 'JPEG', dpi: number, quality: number): PreviewBasis | null {
  if (!s.baseline) {
    return null;
  }
  return {
    revision: s.baseline.revision,
    contentHash: s.baseline.contentHash,
    samples: 1,
    format,
    dpi,
    quality,
  };
}

export function basisId(b: PreviewBasis): string {
  return `${b.revision}:${b.contentHash}:${b.samples}:${b.format}:${b.dpi}:${b.quality}`;
}

export function isDirty(s: Session): boolean {
  if (!s.baseline || !s.workingDsl || s.mode !== 'structured') {
    return false;
  }
  return canonicalize(s.workingDsl) !== canonicalize(s.baseline.dsl);
}

export function hardProblems(s: Session): Problem[] {
  return s.problems.filter((p) => p.severity === 'hard');
}

/** Local structured edit: disqualify the in-flight preview (generation++), push undo. */
export function editDsl(s: Session, nextDsl: string): Session {
  if (!s.opened || s.mode !== 'structured') {
    return s;
  }
  return {
    ...s,
    workingDsl: nextDsl,
    undoStack: [...s.undoStack, s.workingDsl ?? ''],
    redoStack: [],
    preview: { ...s.preview, slot: null, generation: s.preview.generation + 1, basis: null },
    lastEvent:
      '编辑生效：dirty=' +
      (canonicalize(nextDsl) !== canonicalize(s.baseline?.dsl ?? '')) +
      '，旧权威预览已撤下（在途 op 失去展示资格）',
    log: log(s, 'edit: preview withdrawn, generation++'),
  };
}

export function undo(s: Session): Session {
  if (s.undoStack.length === 0 || s.mode !== 'structured') {
    return s;
  }
  const previous = s.undoStack[s.undoStack.length - 1] ?? '';
  return {
    ...s,
    workingDsl: previous,
    undoStack: s.undoStack.slice(0, -1),
    redoStack: [s.workingDsl ?? '', ...s.redoStack],
    preview: { ...s.preview, slot: null, generation: s.preview.generation + 1, basis: null },
    lastEvent: 'undo：恢复上一份工作副本，预览撤下',
    log: log(s, 'undo'),
  };
}

export function redo(s: Session): Session {
  if (s.redoStack.length === 0 || s.mode !== 'structured') {
    return s;
  }
  const next = s.redoStack[0] ?? '';
  return {
    ...s,
    workingDsl: next,
    undoStack: [...s.undoStack, s.workingDsl ?? ''],
    redoStack: s.redoStack.slice(1),
    preview: { ...s.preview, slot: null, generation: s.preview.generation + 1, basis: null },
    lastEvent: 'redo：重放下一份工作副本，预览撤下',
    log: log(s, 'redo'),
  };
}

/** External writer drifts the server current (same or different content). Mutates the fixture in place. */
export function serverDrift(fx: Fixture, sameContent: boolean): Fixture {
  const nextDsl = sameContent ? fx.server.dsl : TEMPLATE_DSL.replace('"名片"', '"名片（远端）"');
  fx.server = {
    ...fx.server,
    revision: fx.server.revision + 1,
    contentHash: hashOf(nextDsl),
    dsl: nextDsl,
  };
  return fx;
}

/** External writer commits a specific content as the new current. Mutates the fixture in place. */
export function serverDriftTo(fx: Fixture, dsl: string): Fixture {
  const canonical = canonicalize(dsl) ?? dsl;
  fx.server = { ...fx.server, revision: fx.server.revision + 1, contentHash: hashOf(canonical), dsl: canonical };
  return fx;
}

/** External delete. Mutates the fixture in place. */
export function serverDelete(fx: Fixture): Fixture {
  fx.server = { ...fx.server, lifecycle: 'DELETED', readiness: 'DELETED' };
  return fx;
}

export function resetProblems(s: Session, problems: Problem[]): Session {
  return { ...s, problems };
}

/** Save: guards + server call; returns the mutated session (state machine is synchronous in the demo). */
export function save(s: Session, fx: Fixture): Session {
  if (!s.opened || s.mode !== 'structured') {
    return { ...s, lastEvent: '保存被拒绝：模式不可保存' };
  }
  if (s.mutation.status !== 'idle') {
    return { ...s, lastEvent: '保存被拒绝：已有 mutation 在途/未知，锁未解除' };
  }
  if (s.overwrite?.offered) {
    return confirmOverwrite(s, fx);
  }
  if (!isDirty(s)) {
    return { ...s, lastEvent: '保存被拒绝：clean 状态不开放无意义保存' };
  }
  const hard = hardProblems(s);
  if (hard.length > 0) {
    return { ...s, lastEvent: '保存被拒绝：存在 hard problem（零写）' };
  }
  const dep = dependencyProblems(s);
  if (dep.length > 0 && !s.invalidConfirmation) {
    return {
      ...s,
      lastEvent: '保存被拒绝：依赖 ERROR 需先二次确认（绑定完整问题集）',
      focus: 'problemSummary',
    };
  }
  if (fx.server.revision !== s.baseline?.revision) {
    return offerConflict(s, fx);
  }
  return commitSave(s, fx, s.invalidConfirmation !== null);
}

function dependencyProblems(s: Session): Problem[] {
  return s.problems.filter((p) => p.severity === 'dependency');
}

function commitSave(s: Session, fx: Fixture, confirmedInvalid: boolean): Session {
  const proposed = canonicalize(s.workingDsl ?? '') ?? s.workingDsl ?? '';
  if (fx.forceUnknown) {
    fx.forceUnknown = false;
    return {
      ...s,
      mutation: { status: 'unknown', kind: confirmedInvalid ? 'invalidConfirm' : 'save' },
      lastEvent: '保存结果不明：进入 Save reconciliation',
      reconcile: { status: 'unknown', detail: 'transport 层结果不明，不报告成功或失败' },
      log: log(s, 'save → unknown'),
    };
  }
  const server = fx.server;
  if (server.lifecycle === 'DELETED') {
    return { ...s, readiness: 'DELETED', lastEvent: '保存失败：Template 已 DELETED（只读/导出）' };
  }
  const nextRevision = server.revision + 1;
  const nextHash = hashOf(proposed);
  fx.server = {
    ...server,
    revision: nextRevision,
    contentHash: nextHash,
    dsl: proposed,
    readiness: confirmedInvalid ? 'INVALID' : 'READY',
  };
  return {
    ...s,
    baseline: { revision: nextRevision, contentHash: nextHash, dsl: proposed },
    workingDsl: proposed,
    undoStack: [],
    redoStack: [],
    mutation: { status: 'idle', kind: null },
    overwrite: null,
    invalidConfirmation: null,
    problems: [],
    readiness: confirmedInvalid ? 'INVALID' : 'READY',
    recovery: {
      baseRevision: nextRevision,
      baseContentHash: nextHash,
      draft: proposed,
      updatedAt: new Date().toISOString(),
    },
    preview: { ...s.preview, slot: null, generation: s.preview.generation + 1, basis: null },
    reconcile: { status: 'none', detail: '' },
    lastEvent: `保存成功：revision ${nextRevision}，canonical baseline 已重建，undo/redo 清空`,
    log: log(s, 'save → saved (canonical baseline adopted)'),
  };
}

/** Conflict overwrite: user confirmed local content over the latest current. */
export function confirmOverwrite(s: Session, fx: Fixture): Session {
  if (!s.overwrite?.offered) {
    return s;
  }
  const server = fx.server;
  if (s.overwrite.confirmedRevision !== null && s.overwrite.confirmedRevision !== server.revision) {
    // Drift after the confirmation was pinned: re-offer.
    return {
      ...s,
      overwrite: { offered: true, offeredRevision: server.revision, confirmedRevision: null },
      lastEvent: `确认后 current 再次漂移（现为 revision ${server.revision}）：需要重新确认覆盖`,
    };
  }
  if (s.overwrite.confirmedRevision === null && s.overwrite.offeredRevision !== server.revision) {
    // Drift between the offer and the confirmation: re-offer.
    return {
      ...s,
      overwrite: { offered: true, offeredRevision: server.revision, confirmedRevision: null },
      lastEvent: `确认前 current 再次漂移（现为 revision ${server.revision}）：需要重新确认覆盖`,
    };
  }
  const confirmedInvalid = s.invalidConfirmation !== null;
  const next = commitSave(
    { ...s, overwrite: { offered: false, offeredRevision: null, confirmedRevision: null } },
    fx,
    confirmedInvalid,
  );
  return { ...next, lastEvent: `覆盖提交完成：${next.lastEvent}` };
}

export function offerConflict(s: Session, fx: Fixture): Session {
  return {
    ...s,
    mutation: { status: 'idle', kind: null },
    overwrite: { offered: true, offeredRevision: fx.server.revision, confirmedRevision: null },
    lastEvent: `expectedRevision 冲突：远端 current 为 revision ${fx.server.revision}，需显式确认覆盖`,
    focus: 'problemSummary',
    log: log(s, 'save → conflict'),
  };
}

export function offerInvalidConfirmation(s: Session): Session {
  const fingerprint = dependencyProblems(s).map((p) => p.code + p.pointer).join('|');
  return {
    ...s,
    invalidConfirmation: { fingerprint },
    lastEvent: '依赖 ERROR 二次确认已就绪（绑定完整未截断问题集）',
    focus: 'problemSummary',
  };
}

/** Save reconciliation: read trusted current and classify. */
export function reconcile(s: Session, fx: Fixture): Session {
  if (s.mutation.status !== 'unknown') {
    return s;
  }
  if (fx.integrityBroken) {
    return {
      ...s,
      reconcile: {
        status: 'failedClosed',
        detail: 'trusted current integrity mismatch：fail closed，保留本地副本，禁止新 mutation',
      },
      mutation: { status: 'unknown', kind: s.mutation.kind },
      lastEvent: 'reconciliation：integrity mismatch → fail closed',
    };
  }
  const server = fx.server;
  const expected = s.baseline?.revision ?? -1;
  const proposed = canonicalize(s.workingDsl ?? '') ?? s.workingDsl ?? '';
  if (server.lifecycle === 'DELETED') {
    return {
      ...s,
      reconcile: { status: 'deleted', detail: '目标已 DELETED：进入只读/导出状态' },
      readiness: 'DELETED',
      mutation: { status: 'idle', kind: null },
      lastEvent: 'reconciliation：DELETED → 只读/导出',
    };
  }
  if (server.revision > expected && server.contentHash === hashOf(proposed)) {
    return {
      ...s,
      baseline: { revision: server.revision, contentHash: server.contentHash, dsl: server.dsl },
      workingDsl: server.dsl,
      undoStack: [],
      redoStack: [],
      mutation: { status: 'idle', kind: null },
      reconcile: {
        status: 'adopted',
        detail: 'revision 已前进且 contentHash 等于 proposed：采用该 current（“内容已在服务器确认”，不宣称请求归属证明）',
      },
      lastEvent: 'reconciliation：adopted（内容已在服务器确认）',
    };
  }
  if (server.revision === expected) {
    return {
      ...s,
      mutation: { status: 'idle', kind: null },
      reconcile: { status: 'retryable', detail: 'current 仍精确等于原 expectedRevision：允许作者显式重试' },
      lastEvent: 'reconciliation：retryable（显式重试）',
    };
  }
  if (server.revision > expected) {
    return {
      ...s,
      mutation: { status: 'idle', kind: null },
      overwrite: { offered: true, offeredRevision: server.revision, confirmedRevision: null },
      reconcile: { status: 'conflict', detail: 'revision 已前进且 hash 不同：进入 conflict overwrite' },
      lastEvent: 'reconciliation：conflict → 覆盖流程',
    };
  }
  return {
    ...s,
    reconcile: { status: 'failedClosed', detail: 'revision 回退等无法解释状态：fail closed' },
    lastEvent: 'reconciliation：无法解释状态 → fail closed',
  };
}

/** Authoritative preview: current-only basis, one slot, one active op, generation guard. */
export function startPreview(s: Session, fx: Fixture, format: 'PNG' | 'JPEG', dpi: number, quality: number): Session {
  if (!s.opened || s.readiness !== 'READY') {
    return { ...s, lastEvent: '预览被拒绝：readiness 非 READY（INVALID/STALE/重检中均不可）' };
  }
  if (isDirty(s)) {
    return { ...s, lastEvent: '预览被拒绝：存在未保存更改，请先“保存并预览”' };
  }
  const basis = nextBasis(s, format, dpi, quality);
  if (!basis) {
    return s;
  }
  const id = basisId(basis);
  fx.opCounter += 1;
  const active: ActivePreview = { opId: fx.opCounter, basis: id, generation: s.preview.generation };
  return {
    ...s,
    preview: { ...s.preview, active, basis, slot: null },
    lastEvent: `预览 operation #${active.opId} 启动（basis ${id}）`,
    log: log(s, 'preview start'),
  };
}

/** Preview completion — the fixture decides success/failure/lateness. */
export function completePreview(s: Session, fx: Fixture): Session {
  const active = s.preview.active;
  if (!active) {
    return s;
  }
  const stale = active.generation !== s.preview.generation || s.preview.basis === null;
  if (stale) {
    return {
      ...s,
      preview: { ...s.preview, active: null },
      lastEvent: `迟到的 preview #${active.opId} 被 generation guard 丢弃（不重新显示）`,
      log: log(s, 'preview late result discarded'),
    };
  }
  if (fx.previewFails) {
    fx.previewFails = false;
    return {
      ...s,
      preview: { ...s.preview, active: null, slot: null },
      problems: [
        ...s.problems,
        { code: 'RENDER_LAYOUT_CONSTRAINT_INVALID', pointer: '/designRoot', severity: 'runtime' },
      ],
      focus: 'problemSummary',
      lastEvent: '预览失败：旧权威结果已撤下，问题面板聚焦（含总数/分类/截断状态）',
      log: log(s, 'preview failure: old result withdrawn'),
    };
  }
  return {
    ...s,
    preview: {
      ...s.preview,
      active: null,
      slot: { imageId: `img-${active.opId}`, basis: active.basis },
    },
    lastEvent: `预览成功：图片 #${active.opId} 已核验 length/digest 后替换结果槽`,
    log: log(s, 'preview success'),
  };
}

export function cancelPreview(s: Session): Session {
  if (!s.preview.active && !s.preview.slot) {
    return s;
  }
  return {
    ...s,
    preview: { ...s.preview, active: null, slot: null, generation: s.preview.generation + 1 },
    lastEvent: '预览已取消：结果槽清空',
    log: log(s, 'preview cancel'),
  };
}

/** Invalidate preview because basis inputs changed (edit/sample/param/current/readiness). */
export function invalidatePreview(s: Session, reason: string): Session {
  if (!s.preview.slot && !s.preview.active) {
    return s;
  }
  return {
    ...s,
    preview: { ...s.preview, slot: null, generation: s.preview.generation + 1, basis: null },
    lastEvent: `预览 basis 失效（${reason}）：已撤下旧图，需重新预览`,
    log: log(s, 'preview basis invalidated'),
  };
}

export function changePreviewParam(s: Session, format: 'PNG' | 'JPEG', dpi: number, quality: number): Session {
  return invalidatePreview(s, `参数变化 ${format}/${dpi}dpi/q${quality}`);
}

/** Import: dirty guard + mode routing. */
export function importBuffer(s: Session, raw: string): Session {
  if (isDirty(s)) {
    return { ...s, lastEvent: 'dirty replacement guard：先保存 / 导出并离开 / 放弃，再导入', focus: 'problemSummary' };
  }
  const canonical = canonicalize(raw);
  if (canonical === null) {
    return {
      ...s,
      mode: 'rawRepair',
      rawBuffer: raw,
      workingDsl: null,
      lastEvent: '非法 UTF-8/JSON/duplicate key：进入 Raw Repair（无结构树/无保存/无预览）',
      log: log(s, 'import → rawRepair (malformed)'),
    };
  }
  if (raw.includes('unsupported-wire')) {
    return {
      ...s,
      mode: 'compatibility',
      rawBuffer: raw,
      workingDsl: null,
      lastEvent: '输入完整但 exact wire 不受支持：进入 Compatibility Read-only（只读/导出/显式 migration）',
      log: log(s, 'import → compatibility'),
    };
  }
  if (!raw.includes('renderweave-design/1.0')) {
    return {
      ...s,
      mode: 'rawRepair',
      rawBuffer: raw,
      workingDsl: null,
      lastEvent: 'unsupported exact profile：进入 Raw Repair（无法构造可信 DesignDSL）',
      log: log(s, 'import → rawRepair (unsupported profile)'),
    };
  }
  return {
    ...s,
    mode: 'structured',
    workingDsl: canonical,
    undoStack: [],
    redoStack: [],
    preview: { ...s.preview, slot: null, generation: s.preview.generation + 1, basis: null },
    lastEvent: '导入接受：完整输出成为新的 dirty working draft（零服务端写入），undo/redo 清空',
    log: log(s, 'import accepted (dirty draft, no server write)'),
  };
}

export function repairToStructured(s: Session): Session {
  const canonical = s.rawBuffer ? canonicalize(s.rawBuffer) : null;
  if (s.mode !== 'rawRepair' || canonical === null) {
    return s;
  }
  return {
    ...s,
    mode: 'structured',
    workingDsl: canonical,
    rawBuffer: null,
    undoStack: [],
    redoStack: [],
    preview: { ...s.preview, slot: null, generation: s.preview.generation + 1, basis: null },
    lastEvent: 'Raw Repair 修复完成（strict JSON + 受支持 profile）：显式进入 Structured Editor（dirty）',
    log: log(s, 'rawRepair → structured'),
  };
}

export function recordRecovery(s: Session): Session {
  if (!s.baseline || !s.workingDsl) {
    return s;
  }
  return {
    ...s,
    recovery: {
      baseRevision: s.baseline.revision,
      baseContentHash: s.baseline.contentHash,
      draft: s.workingDsl,
      updatedAt: new Date().toISOString(),
    },
    lastEvent: 'Local recovery draft 已记录（base revision/hash + 完整工作副本；仅当前设备、best-effort）',
    log: log(s, 'recovery recorded'),
  };
}

export function restoreRecovery(s: Session, fx: Fixture): Session {
  if (!s.recovery) {
    return s;
  }
  if (isDirty(s)) {
    return { ...s, lastEvent: 'dirty replacement guard：恢复前需先保存 / 导出并离开 / 放弃' };
  }
  const server = fx.server;
  if (s.recovery.baseRevision === server.revision && s.recovery.baseContentHash === server.contentHash) {
    return {
      ...s,
      workingDsl: s.recovery.draft,
      undoStack: [],
      redoStack: [],
      preview: { slot: null, active: null, generation: s.preview.generation + 1, basis: null },
      lastEvent: '恢复：base 与服务器 current 相同，直接恢复为 dirty EditorSession（不自动提交）',
      log: log(s, 'recovery restored (base matches)'),
    };
  }
  return {
    ...s,
    lastEvent: `恢复前检查：base（r${s.recovery.baseRevision}）已落后服务器 current（r${server.revision}）；确认后恢复为 dirty 草稿，保存时走 conflict overwrite`,
    focus: 'problemSummary',
    log: log(s, 'recovery base drifted — confirmation required'),
  };
}

export function discardDraft(s: Session): Session {
  return {
    ...s,
    workingDsl: s.baseline?.dsl ?? s.workingDsl,
    undoStack: [],
    redoStack: [],
    overwrite: null,
    preview: { ...s.preview, slot: null, generation: s.preview.generation + 1, basis: null },
    lastEvent: '已放弃本地草稿（回到 canonical baseline）',
    log: log(s, 'draft discarded'),
  };
}

// ---------- guided walkthrough scenarios ----------

export interface ScenarioStep {
  label: string;
  act?: (s: Session, fx: Fixture) => Session;
  expect: (s: Session) => { pass: boolean; message: string };
}

export interface Scenario {
  id: string;
  title: string;
  goal: string;
  steps: ScenarioStep[];
}

export const SCENARIOS: Scenario[] = [
  {
    id: 'open-baseline',
    title: '打开与基线',
    goal: '打开 ACTIVE Template：读取 trusted canonical current 建立 baseline；权威重检完成前预览禁用；recheck 后 READY。',
    steps: [
      {
        label: '打开 Template（模拟 openTemplate API）',
        act: (_s, fx) => openSession(fx),
        expect: (s) => ({
          pass: s.opened && s.baseline?.revision === 3 && s.readiness === 'checking',
          message: 'baseline=r3，readiness=checking（旧 report 不作为结论）',
        }),
      },
      {
        label: '重检期间尝试预览',
        act: (s, fx) => startPreview(s, fx, 'PNG', 96, 90),
        expect: (s) => ({ pass: s.preview.active === null, message: '重检完成前预览保持禁用' }),
      },
      {
        label: '权威重检完成',
        act: (s) => recheckReady(s),
        expect: (s) => ({ pass: s.readiness === 'READY', message: 'readiness=READY' }),
      },
    ],
  },
  {
    id: 'edit-save',
    title: '编辑 → 保存成功',
    goal: 'Structured 编辑使 dirty=true 并撤下旧预览；保存成功重建 canonical baseline、清空 undo/redo、更新 recovery。',
    steps: [
      {
        label: '打开并完成重检',
        act: (_s, fx) => recheckReady(openSession(fx)),
        expect: (s) => ({ pass: s.readiness === 'READY', message: 'READY' }),
      },
      {
        label: '本地编辑（修改 displayName）',
        act: (s) => editDsl(s, TEMPLATE_DSL.replace('"名片"', '"名片 V2"')),
        expect: (s) => ({ pass: isDirty(s), message: 'dirty=true（canonical 差异判定，非 transport bytes）' }),
      },
      {
        label: '保存',
        act: (s, fx) => save(s, fx),
        expect: (s) => ({
          pass: s.baseline?.revision === 4 && !isDirty(s) && s.undoStack.length === 0,
          message: 'baseline=r4、clean、undo/redo 清空',
        }),
      },
      {
        label: '检查 Local recovery draft',
        expect: (s) => ({ pass: s.recovery?.baseRevision === 4, message: 'recovery.baseRevision=r4（有界 debounce 后替换）' }),
      },
    ],
  },
  {
    id: 'conflict-overwrite',
    title: '冲突与覆盖重确认',
    goal: 'expectedRevision 冲突 → 显式确认覆盖 → 重读最新 revision 再提交；确认期间再次漂移必须重新确认；无静默 last-write-wins。',
    steps: [
      {
        label: '打开、重检、编辑',
        act: (_s, fx) => editDsl(recheckReady(openSession(fx)), TEMPLATE_DSL.replace('"名片"', '"本地草稿"')),
        expect: (s) => ({ pass: isDirty(s), message: 'dirty' }),
      },
      {
        label: '远端作者提交了新 revision（外部漂移）',
        act: (s, fx) => {
          serverDrift(fx, false);
          return s;
        },
        expect: (s) => ({ pass: s.mutation.status === 'idle', message: '编辑会话不受影响' }),
      },
      {
        label: '保存 → 冲突',
        act: (s, fx) => save(s, fx),
        expect: (s) => ({ pass: s.lastEvent.includes('冲突') && s.overwrite !== null, message: '冲突：远端已有新 current，需显式确认' }),
      },
      {
        label: '确认覆盖（重读 latest 为 expectedRevision）',
        act: (s, fx) => confirmOverwrite(s, fx),
        expect: (s) => ({ pass: s.baseline?.revision === 5 && !isDirty(s), message: '以本地完整内容覆盖成功，baseline=r5' }),
      },
      {
        label: '再次编辑 + 再漂移 → 覆盖前需重新确认',
        act: (s, fx) => {
          const next = editDsl(s, TEMPLATE_DSL.replace('"名片"', '"再次本地"'));
          serverDrift(fx, false);
          return save(next, fx);
        },
        expect: (s) => ({ pass: s.overwrite?.offered === true, message: '再次漂移后重新进入覆盖确认（不静默覆盖）' }),
      },
    ],
  },
  {
    id: 'unknown-reconcile',
    title: 'unknown 结果 → Save reconciliation',
    goal: 'transport 结果不明时保持锁与草稿，读 trusted current 分类：adopted / retryable / conflict / deleted / fail closed。',
    steps: [
      {
        label: '打开、重检、编辑',
        act: (_s, fx) => editDsl(recheckReady(openSession(fx)), TEMPLATE_DSL.replace('"名片"', '"结果不明草稿"')),
        expect: (s) => ({ pass: isDirty(s), message: 'dirty' }),
      },
      {
        label: '保存 → 结果不明（fixture 注入 unknown）',
        act: (s, fx) => {
          fx.forceUnknown = true;
          return save(s, fx);
        },
        expect: (s) => ({ pass: s.mutation.status === 'unknown', message: 'mutation=unknown：不报告成功/失败，锁保持' }),
      },
      {
        label: '远端已提交相同内容（revision 前进 + hash 等于 proposed）→ reconciliation 采用',
        act: (s, fx) => {
          serverDriftTo(fx, s.workingDsl ?? TEMPLATE_DSL);
          return reconcile(s, fx);
        },
        expect: (s) => ({ pass: s.reconcile.status === 'adopted', message: 'adopted：内容已在服务器确认，baseline 采用' }),
      },
      {
        label: '另一场景：current 未变 → 可显式重试',
        act: (_s, fx) => {
          let next = editDsl(recheckReady(openSession(fx)), TEMPLATE_DSL.replace('"名片"', '"重试草稿"'));
          fx.forceUnknown = true;
          next = save(next, fx);
          return reconcile(next, fx);
        },
        expect: (s) => ({ pass: s.reconcile.status === 'retryable', message: 'retryable：允许作者显式重试' }),
      },
      {
        label: '再一场景：integrity mismatch → fail closed',
        act: (_s, fx) => {
          let next = editDsl(recheckReady(openSession(fx)), TEMPLATE_DSL.replace('"名片"', '"完整性草稿"'));
          fx.forceUnknown = true;
          next = save(next, fx);
          fx.integrityBroken = true;
          return reconcile(next, fx);
        },
        expect: (s) => ({ pass: s.reconcile.status === 'failedClosed', message: 'fail closed：保留可导出副本，禁止新 mutation' }),
      },
    ],
  },
  {
    id: 'preview-guard',
    title: '预览 basis 失效与 generation guard',
    goal: '单一结果槽 + 单一活跃 op；任何编辑/参数变化立即撤下图并要求重预览；迟到结果被 generation guard 丢弃。',
    steps: [
      {
        label: '打开、重检、预览成功',
        act: (_s, fx) => completePreview(startPreview(recheckReady(openSession(fx)), fx, 'PNG', 96, 90), fx),
        expect: (s) => ({ pass: s.preview.slot !== null, message: '结果槽已填充' }),
      },
      {
        label: '编辑 → 旧图立即撤下',
        act: (s) => editDsl(s, TEMPLATE_DSL.replace('"名片"', '"再编辑"')),
        expect: (s) => ({ pass: s.preview.slot === null && s.preview.generation > 0, message: '旧权威结果已撤下，需重新预览' }),
      },
      {
        label: '保存后再启动预览，随后编辑使其迟到，成功被 generation guard 丢弃',
        act: (s, fx) => {
          let next = save(s, fx);
          next = startPreview(next, fx, 'PNG', 96, 90);
          next = editDsl(next, TEMPLATE_DSL.replace('"名片"', '"编辑后再预览"'));
          return completePreview(next, fx);
        },
        expect: (s) => ({
          pass: s.preview.slot === null && s.preview.active === null && s.lastEvent.includes('丢弃'),
          message: '迟到结果被 generation guard 丢弃，不重新显示',
        }),
      },
      {
        label: '参数变化（DPI）也撤下图',
        act: (s) => changePreviewParam(s, 'PNG', 144, 90),
        expect: (s) => ({ pass: s.preview.slot === null, message: '参数变化 → 撤下（basis 含 format/DPI/quality）' }),
      },
    ],
  },
  {
    id: 'save-and-preview',
    title: '保存并预览（顺序非原子）',
    goal: 'dirty 时先完整保存，仅保存成功且重检 READY 才启动独立预览；保存成功与预览失败分别呈现，预览失败不回滚保存。',
    steps: [
      {
        label: '打开、重检、编辑',
        act: (_s, fx) => editDsl(recheckReady(openSession(fx)), TEMPLATE_DSL.replace('"名片"', '"保存并预览"')),
        expect: (s) => ({ pass: isDirty(s), message: 'dirty' }),
      },
      {
        label: '保存并预览：先保存',
        act: (s, fx) => save(s, fx),
        expect: (s) => ({ pass: s.baseline?.revision === 4 && s.readiness === 'READY', message: '保存成功，revision 已追加（不可回滚）' }),
      },
      {
        label: '随后预览失败（fixture 注入）',
        act: (s, fx) => {
          fx.previewFails = true;
          return completePreview(startPreview(s, fx, 'PNG', 96, 90), fx);
        },
        expect: (s) => ({
          pass: s.baseline?.revision === 4 && s.preview.slot === null && s.lastEvent.includes('预览失败'),
          message: '分别呈现“保存成功”与“预览失败”，不伪装单事务',
        }),
      },
    ],
  },
  {
    id: 'recovery-lifecycle',
    title: 'Local recovery 生命周期',
    goal: '每 Template 一份 recovery（base revision/hash + 完整草稿）；重开时 恢复/导出/放弃 三选；base 与 current 相同才直接恢复，否则先显示基线变化再确认。',
    steps: [
      {
        label: '打开、编辑、记录 recovery',
        act: (_s, fx) => recordRecovery(editDsl(recheckReady(openSession(fx)), TEMPLATE_DSL.replace('"名片"', '"恢复草稿"'))),
        expect: (s) => ({ pass: s.recovery?.baseRevision === 3, message: 'recovery 记录 base=r3' }),
      },
      {
        label: '模拟崩溃后重开（设备 recovery 保留，载入新 session）',
        act: (s, fx) => ({ ...openSession(fx), recovery: s.recovery }),
        expect: (s) => ({
          pass: s.opened && s.recovery !== null && !isDirty(s),
          message: '重开为 clean session（recovery 记录仍在），UI 提供 恢复/导出/放弃',
        }),
      },
      {
        label: '恢复检查：base 与 current 相同 → 直接恢复为 dirty',
        act: (s, fx) => restoreRecovery(s, fx),
        expect: (s) => ({ pass: isDirty(s), message: '恢复为 dirty EditorSession（不自动提交）' }),
      },
      {
        label: 'base 已漂移 → 显示基线变化并要求确认',
        act: (_s, fx) => {
          const recovery = {
            baseRevision: 3,
            baseContentHash: hashOf(TEMPLATE_DSL),
            draft: TEMPLATE_DSL.replace('"名片"', '"恢复草稿"'),
            updatedAt: new Date().toISOString(),
          };
          const next = { ...openSession(fx), recovery };
          serverDrift(fx, false);
          return restoreRecovery(next, fx);
        },
        expect: (s) => ({ pass: s.lastEvent.includes('已落后'), message: '先显示基线变化，再允许恢复（保存时走覆盖确认）' }),
      },
    ],
  },
  {
    id: 'dirty-guard',
    title: 'dirty replacement guard',
    goal: '导入 / migration 接受 / recovery 恢复在替换 working draft 前共用 guard：保存 / 导出并离开 / 明确放弃。',
    steps: [
      {
        label: '打开、重检、编辑（dirty）',
        act: (_s, fx) => editDsl(recheckReady(openSession(fx)), TEMPLATE_DSL.replace('"名片"', '"被保护草稿"')),
        expect: (s) => ({ pass: isDirty(s), message: 'dirty' }),
      },
      {
        label: '尝试导入新文件',
        act: (s) => importBuffer(s, TEMPLATE_DSL),
        expect: (s) => ({ pass: s.lastEvent.includes('guard'), message: '导入被 dirty guard 拦截：保存/导出并离开/放弃' }),
      },
      {
        label: '先保存再导入',
        act: (s, fx) => importBuffer(save(s, fx), TEMPLATE_DSL.replace('"名片"', '"导入内容"')),
        expect: (s) => ({ pass: s.mode === 'structured' && isDirty(s), message: '导入接受为 dirty draft（零服务端写入）' }),
      },
    ],
  },
  {
    id: 'modes',
    title: '三模式切换',
    goal: '非法 UTF-8/JSON/duplicate key → Raw Repair（无结构视图）；unsupported exact wire → Compatibility Read-only；修复/受支持后才显式进入 Structured。',
    steps: [
      {
        label: '打开并导入非法缓冲',
        act: (_s, fx) => importBuffer(recheckReady(openSession(fx)), '{"dslVersion":'),
        expect: (s) => ({ pass: s.mode === 'rawRepair' && s.workingDsl === null, message: 'Raw Repair：无结构树/保存/预览' }),
      },
      {
        label: '修复缓冲后显式进入 Structured',
        act: (s) => repairToStructured({ ...s, rawBuffer: TEMPLATE_DSL.replace('"名片"', '"修复后"') }),
        expect: (s) => ({ pass: s.mode === 'structured' && isDirty(s), message: 'Structured（dirty）' }),
      },
      {
        label: '导入 unsupported wire → Compatibility',
        act: (s, fx) =>
          importBuffer(save(s, fx), TEMPLATE_DSL.replace('"renderweave-design/1.0"', '"unsupported-wire/9.9"')),
        expect: (s) => ({ pass: s.mode === 'compatibility', message: 'Compatibility Read-only：只读/导出/显式 migration' }),
      },
    ],
  },
  {
    id: 'failure-a11y',
    title: '失败撤下与可访问性流',
    goal: '预览/保存失败撤下旧权威结果并把焦点移到带总数/分类/截断状态的问题摘要；核心流键盘可达、无 keyboard trap。',
    steps: [
      {
        label: '打开、重检、预览成功',
        act: (_s, fx) => completePreview(startPreview(recheckReady(openSession(fx)), fx, 'PNG', 96, 90), fx),
        expect: (s) => ({ pass: s.preview.slot !== null, message: '结果槽已填充' }),
      },
      {
        label: '注入预览失败',
        act: (s, fx) => {
          fx.previewFails = true;
          return completePreview(startPreview(s, fx, 'PNG', 96, 90), fx);
        },
        expect: (s) => ({
          pass: s.preview.slot === null && s.focus === 'problemSummary',
          message: '旧图撤下 + 问题摘要聚焦（总数/分类/截断）',
        }),
      },
      {
        label: 'hard problem 阻止保存（零写）',
        act: (_s, fx) => {
          let next = recheckReady(openSession(fx));
          next = editDsl(next, TEMPLATE_DSL.replace('"名片"', '"含 hard error"'));
          next = resetProblems(next, [
            { code: 'DESIGN_PROPERTY_CONSTRAINT_INVALID', pointer: '/designRoot/children/0', severity: 'hard' },
          ]);
          return save(next, fx);
        },
        expect: (s) => ({
          pass: s.baseline?.revision === 3 && s.lastEvent.includes('hard problem'),
          message: 'hard error 零写；依赖 ERROR 才可二次确认',
        }),
      },
    ],
  },
];
