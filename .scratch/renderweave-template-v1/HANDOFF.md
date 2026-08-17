# RenderWeave Template v1 跨设备交接

## 1. 交接目标

在另一台设备、另一个 Codex coding agent session 中，继续 `RenderWeave Template v1` 规格探索与验收登记收口。

- 只修改规格、scratch、ADR、registry、fixture 与 evidence。
- 不实现 Java/Web/Rust 产品代码，不创建占位页面、接口、表或产品路径。
- 不启动浏览器/Web 服务，不执行 J1，不声称 Editor/Renderer READY。
- 不运行付费 provider、不发送真实数据、不读取或输出 API Key。
- Ticket 19 仍保持 open；fixture 静态重放不是产品执行、物理 Linux Renderer 认证或完整自动 corpus。

## 2. Git 与工作区锚点

```text
source worktree: E:\java_project\RenderWeave-template-v1
branch:          spec/template-v1
HEAD:            b14c2d7d4978c679e7ab8e7a2bace3da7af884de
upstream:        spec/template-v1 at the same commit
ahead/behind:    0/0
change state:    all Template v1 exploration after the baseline is uncommitted
```

重要：另一台设备仅 checkout 上述 branch/commit 看不到本次工作。必须先把随本文件交付的 overlay 解压到该 checkout，或由用户另行授权在本机创建 checkpoint commit 并 push。当前 session 没有 commit 或 push。

## 3. 新 session 的必读顺序

1. `AGENTS.md`
2. `CONTEXT.md`
3. `.scratch/renderweave-template-v1/HANDOFF.md`（本文件）
4. `.scratch/renderweave-template-v1/map.md`
5. `.scratch/renderweave-template-v1/issues/17-authoring-workflow-prototype.md`
6. `.scratch/renderweave-template-v1/issues/18-editor-preview-and-recovery.md`
7. `.scratch/renderweave-template-v1/issues/19-security-capacity-acceptance.md`
8. `.scratch/renderweave-template-v1/acceptance-manifest-v1.json`
9. `.scratch/renderweave-template-v1/requirements-v1.json`

恢复后首先重新核验 branch、HEAD、upstream、ahead/behind 与 dirty paths。不得用 reset/checkout 清理或覆盖现有改动。

## 4. 已冻结的用户决策

- 后续批次均按推荐方向推进。
- v1 的 Q3 采用“后使用/后提交覆盖前者”的简单处理，不增加 merge、历史仲裁或其他复杂度。
- “不实现产品代码”始终有效。
- 规划 fixture、静态 replay 与 A2 registry replay 必须如实标记，不能伪装成产品实现、浏览器观察、J1、Linux 认证或 READY。

## 5. 当前全局快照

### Requirements 与 capacity

- Atomic requirement registry：3,651 条。
- Capacity axis：175 个；每轴 below/at/above 共 525 个 shape candidate。
- Combined capacity：18 个；严格 capacity case floor 为 543。
- 正式 SPEC_REGISTRY Case/Oracle：46/46；capacity 正式 record 仍为 0。

### Probe Profile

- Current issued Profile：`renderweave-conformance-probes/1.0`，110 probes，`recordMayReference=true`。
- Current Profile bytes SHA-256：`f800eb1e6e138215c26c7761ed80e0fc9cf77fc3ce051be4e3c5ba530cd6053d`。
- Complete but unissued candidate：`renderweave-conformance-probes/1.1`，119 probes，`recordMayReference=false`。
- Editor candidate bindings：109 assertions / 82 candidates；candidate Profile 未发行。

### Editor atomic candidates

- 12 journey seeds，108 planning candidates，138/138 seed requirement planned closure。
- 1,265 assertion plans：
  - exact literal 749
  - exact artifact 108
  - exact ABSENT 289
  - exact total 1,146
  - pending 119
- Target bindings：108 artifact + 81 literal = 189 exact；119 pending。
- Semantic content prerequisites：原始 58；由 source record 收口 1；剩余 57。
- UI observations pending：62。
- First-layer `EditorContentSource` slots：47；exact 1；UNBOUND 46。
- 仍带 `TARGET_LITERAL_OR_ARTIFACT_MISSING` 的 candidate：93。
- Formal Editor Case/Oracle：0/0。

### SPEC_REGISTRY

- Implementation revision：`spec-registry-bootstrap/1.13`。
- Target artifact count：382。
- Node primary replay：22,838 checks，0 failure。
- Python independent replay：22,746 checks，0 failure。
- 正式 JSONL 保持 46 Case / 46 Oracle；current Probe Profile 1.0 bytes 未变化。

## 6. 最后完成的批次：dirty-guard content source

唯一新绑定：

```text
candidate:       EDC::J10::012
assertion:       PA011
sourceSlotId:    ECS::J10::012::PA011
scenario:        dirty-guard-blocks-replacement
adapter:         IMMUTABLE_SPEC_FIXTURE
terminal:        replaceWorkingDraft / NONTERMINAL_REJECTION
code:            EDITOR_DIRTY_DRAFT_REPLACEMENT_BLOCKED
post-action rule: BYTE_IDENTICAL_TO_PRE_ACTION
```

相关 artifact：

- `editor-automated/content-sources/dirty-guard-clean-baseline.design.json`
  - exact `renderweave-design-c14n/1.0` bytes
  - byteLength 272
  - SHA-256 / clean working-copy digest：`eebf61d7025416476990adda79caeb6d66fe30f19acf90faa0447aa44a0c949c`
  - domain-separated contentHash：`sha256:2d567534cc6046da753216035ad99d4e356fc871b1f69cc2bab49932fbad4635`
- `editor-automated/content-sources/dirty-guard-working-copy.design.json`
  - byteLength 282
  - SHA-256 / expected working-copy digest：`ee0e5678114d1c725d6669c0f354562f273a56124d1541f3abae688696e18b26`
- `editor-automated/content-sources/ecs-j10-012-pa011.json`
  - closed source record
  - byteLength 2456
  - SHA-256：`fd6b4f3a120ed98ef31f56409140ef31d34c012a4628f700c1eff57f79177d5f`

两份 DesignDSL 都是完整合法的最小 Canvas 文档，唯一语义差异是顶层 authored `displayName`。该 fixture 只冻结 spec-owned bytes 与 expected atomic-rejection state rule；它没有执行产品动作，也不证明 dirty guard 已实现。

本批次验证：

- Node atomic generator/validator：37 checks，0 failure。
- Python independent atomic replay：21,141 checks，0 failure。
- Acceptance manifest path/hash walker：103 artifact bindings，0 failure。
- A2 evidence：`editor-automated/editor-atomic-candidates-static-a2-2026-08-17.json`。

## 7. 关键文件

### Editor content/target seam

- `editor-automated/content-source-contract-v1.json` (`1.1`)
- `editor-automated/content-source-catalog-v1.json` (`1.1`)
- `editor-automated/target-binding-contract-v1.json` (`1.3`)
- `editor-automated/target-binding-catalog-v1.json` (`1.3`)
- `editor-automated/atomic-candidate-contract-v1.json` (`1.5`)
- `editor-automated/atomic-scenario-candidates-v1.json` (`1.5`)
- `editor-automated/atomic-candidate-readiness-audit-v1.json` (`1.5`)
- `editor-automated/generate-editor-atomic-candidates.mjs`
- `editor-automated/validate_editor_atomic_candidates_independent.py`
- `editor-automated/write-editor-atomic-candidate-evidence.mjs`

### Registry

- `spec-registry/target-manifest-v1.json`
- `spec-registry/refresh-spec-registry-postissuance-target.mjs`
- `spec-registry/validate-spec-registry-primary.mjs`
- `spec-registry/validate-spec-registry-independent.py`
- `spec-registry/write-spec-registry-a2-evidence.mjs`
- `spec-registry/spec-registry-a2-2026-08-17.json`

## 8. 精确重放命令

在 repository root 执行 Editor static replay：

```powershell
node .scratch/renderweave-template-v1/editor-automated/generate-editor-atomic-candidates.mjs
python .scratch/renderweave-template-v1/editor-automated/validate_editor_atomic_candidates_independent.py
node .scratch/renderweave-template-v1/editor-automated/write-editor-atomic-candidate-evidence.mjs
```

在 `.scratch/renderweave-template-v1` 执行 SPEC_REGISTRY replay：

```powershell
node spec-registry/refresh-spec-registry-postissuance-target.mjs
node spec-registry/validate-spec-registry-primary.mjs --target spec-registry/target-manifest-v1.json
python spec-registry/validate-spec-registry-independent.py --target spec-registry/target-manifest-v1.json
node spec-registry/write-spec-registry-a2-evidence.mjs
```

最后执行：

```powershell
git diff --check
git status --short
git rev-parse --abbrev-ref HEAD
git rev-parse HEAD
git rev-parse '@{upstream}'
git rev-list --left-right --count 'HEAD...@{upstream}'
```

注意：`write-spec-registry-a2-evidence.mjs` 会把当时的 `acceptance-manifest-v1.json` hash 写入 evidence。若修改 acceptance manifest，最后要再运行一次该 writer。

## 9. 下一推荐批次

继续逐个审计“拒绝后 working copy 必须保持不变”的 source slot，但只绑定能够由完整、合法、Editor-owned canonical DesignDSL 独立证明的场景。

当前不要强行绑定：

- `EDC::J04::003` / `004` / `005`：array reorder rejection 需要 exact semantic array、numeric targetPropertyRef 与 overlap/out-of-bounds/duplicate fixture wire；当前抽象 baseline 不足。
- `EDC::J02::004`：unsafe delete rejection 需要完整且无争议的 CustomDefinition + binding reference DesignDSL wire；未先冻结该 exact fixture 前不得生成 digest。
- 任何 `SERVER_CANONICAL_RESPONSE` slot：没有 admitted product capture 时保持 `UNBOUND`。

推荐顺序：

1. 从剩余 46 slots 中寻找无需 server response、无需未冻结 ID/locator/array semantics 的 complete fixture。
2. 对每个候选先证明完整 DesignDSL 合法性、canonical bytes、pre/post rule 与 source record closed shape。
3. 只通过 `EditorContentSource` exact result 向 `EditorTargetBinding` 提供 digest/artifact，不另开 test-only bypass。
4. 每批继续双实现 replay，更新 acceptance manifest、map、CONTEXT 与 SPEC_REGISTRY target。

若没有新的无推断候选，应如实停在 1 exact / 46 UNBOUND，而不是为降低 pending 计数发明 bytes。

## 10. 严格负面边界

- Ticket 19 open。
- Renderer calibration：当前 Windows 主机没有工作的 Linux execution environment，也没有可证实的旧 busbox harness/image；不得用本机数值冻结 Linux READY capacity。
- Renderer hermetic build、ELF closure、portable tricky-font、两台不同 CPU 家族物理 Linux pixel replay均未完成。
- Editor repository admission 仍 REJECTED；exact product build、browser/OS、environment targets、browser automation runner、product fixture/fault adapters、product replay与独立 J1均未完成。
- Probe Profile 1.1 未发行；formal Editor Case/Oracle 不得引用它。
- 规划 artifacts、A1/A2 static evidence 与 registry replay不得称为产品功能或人工 acceptance。

## 11. 可直接粘贴给新 agent 的续接提示

```text
继续 RenderWeave Template v1 规格探索，不实现产品代码。

工作区必须从 branch spec/template-v1、commit b14c2d7d4978c679e7ab8e7a2bace3da7af884de 恢复，并叠加 handoff overlay 中的全部未提交变更。先读取 AGENTS.md、CONTEXT.md、.scratch/renderweave-template-v1/HANDOFF.md、map.md、issues/17、18、19 与 acceptance-manifest-v1.json。先核验 branch/HEAD/upstream/ahead-behind/dirty paths，再重放 HANDOFF 第8节全部 gate。

当前最新收口是 EDC::J10::012::PA011 dirty-guard EditorContentSource：47 slots 中1 exact、46 UNBOUND；1,265 assertions 中1,146 exact、119 pending；SPEC_REGISTRY 1.13含382 artifacts，Node/Python分别22,838/22,746 checks。该结果只证明spec fixture bytes/state rule，不证明产品行为。下一批按 HANDOFF 第9节逐个寻找可由完整合法canonical DesignDSL无推断绑定的source slot；若无候选，保持fail-closed。禁止浏览器/Web/J1/产品代码/paid provider/READY claim；不擅自commit或push。
```
