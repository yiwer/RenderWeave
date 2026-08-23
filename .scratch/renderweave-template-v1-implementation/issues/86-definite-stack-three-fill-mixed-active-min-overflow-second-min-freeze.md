# 实现 definite Stack 三 FILL mixed active-min overflow 第二 min freeze 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85（均已 resolved）

## Question

T85 已允许 exactly-three main FILL 的 mixed active min 自身大于 remaining 时以 `min/0/0` 退化，并允许一个
终止正零仍满足的 inactive max-only bound。若另一个 child 携带初始 proportional share 下 inactive、但在终止
正零下必然 active 的正 min-only bound，如何复用 T83 两次 min freeze overflow，而不开放初始多个 active、
second mixed bound、post-overflow redistribution、第三次 freeze、一般循环或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有三个 main-axis FILL，三项 weight 均 positive；第一轮恰好一个
   finite/nonnegative active min，active child 携带合法 finite/nonnegative min/max、`min <= max`，且 first min
   严格大于 `remaining`。另外两项中恰好一项只携带 finite positive min-only，其初始 proportional share 不低于
   second min；另一项 owning-axis min/max 全 absent。初始 `share == secondMin` 合法。
3. **固定第二次 min freeze 后终止**：首个 mixed min 先冻结；second min 在退化终止正零下必然 active，随后按
   authored position 显式提交 `firstMin/secondMin/0`。因为 first min 已严格大于 remaining 且 second min 为正，
   不计算负 residual、post-overflow weight/share、redistribution、第三次 freeze、循环或 epsilon/tolerance。
4. **溢出、布局与错误顺序不变**：accepted 尺寸和严格大于 remaining；既有 occupied/free-space 公式令六种
   `justifyContent` 退回零 extra/START 分布。signed margins、gap、cursor、cross alignment、每项至多一次 deferred
   cross-HUG remeasure、authored DFS first-error 与全有或全无 output 均不变。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/49`。把 T85 replacement negative 转为
   active-first positive，新增 active-middle、active-last（覆盖初始 `share == secondMin`）、COLUMN 与 cross-HUG
   positives；以 second min 同时携带合法 inactive max 的 second-mixed negative 替换。能力值新增
   `OR_EXACT_THREE_FILL_MIXED_ACTIVE_MIN_OVERFLOW_SECOND_MIN_FREEZE_OVERFLOW`；目标 222 laid-out + 16
   unsupported、238 cases/712 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：second min 为零/负/非 finite、second mixed/max-only、两个额外 bounded children、第三个 min、
   首轮多个 active、active child 非 mixed 或 min 不大于 remaining、post-overflow redistribution、第三次 freeze/
   cascade、four-or-more active-bound FILL、一般多轮 water filling、Profile residual tolerance/public numeric error、
   HUG-main FILL cycle、rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource fetch/decode、
   scene/raster/JPEG、daemon RESULT/Profile、Java/OpenAPI/migration/Web/route、J1/A3/READY 与外部副作用均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/49` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  mixed-active-min-overflow/second-min-freeze case、同一 `STACK_MAIN_FILL` occurrence 共同 RED；再分别实现严格
  控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/49` 先只改 vectors/identity；Rust primary 与 Python independent verifier 在首个转正 case
  `stack-three-main-fills-mixed-active-min-overflow-second-min-freeze-overflow` 的同一 `STACK_MAIN_FILL`
  occurrence `rwocc_0000000000000002` 共同 RED。第一次实现后，cross-HUG fixture 暴露其可用主轴为 80pt，原
  second min 30 会让首轮出现两个 active bound；只把该 vector 的 second min 校正为 25（期望 `110/25/0`），
  未拓宽实现。Rust focused 首次命令误用了不存在的 package `renderweave-layout`，随后用正确 package
  `renderweave-renderer-layout` 重跑并取得上述同位 RED。
- Rust 只在既有 mixed-active-min overflow early branch 中辨认唯一 positive min-only second freeze；Python 以
  absent positions、inactive max tuples 与 second-min candidates 的独立分类控制流实现同一冻结语义。双方最终
  exact-bit GREEN 为 222 laid-out + 16 unsupported、238/238 cases、712 checks；replacement negative
  `stack-three-main-fills-mixed-active-min-overflow-second-mixed-min-remains-unsupported` 证明 second mixed 仍关闭。
- focused Rust 1/1、Python 238/238、workspace `fmt --check`、`check --locked --offline`、clippy
  `-D warnings`、tests、`py_compile`、JSON inventory/unique 与 `git diff --check` 全绿。vector SHA-256 为
  `622167dd7bc74fc454600ac508b0072fd825717dbe8de5805d8c4d22a5cdd745`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- 分级 A1 证据全部 exit 0：`render` `.sdlc/evidence/20260824-054717-render/`（19.440 秒）、affected
  `fast` `.sdlc/evidence/20260824-054742-fast/`（15.960 秒）、顺序 `server`
  `.sdlc/evidence/20260824-054805-server/`（1109.994 秒）与 17-step `full`
  `.sdlc/evidence/20260824-060641-full/`（1679.041 秒）。full definite replay 为 238/238、712 checks；App
  344/0/0/15、Node 24 Web 26 files/212 tests、runtime canary、23 passed + 1 controlled skip Playwright、
  prototype/Draft journeys 与 inference replay E2E 1/1 均通过。R0/R1/P0 provider attempts=0，P0 API Key
  reads/reservations/cost=0；R1 A2 60 cases/58 metrics、J0，P0 A2 60 cases（20 holdout）/58 metrics。
- 本状态回填后的 resolution `fast` `.sdlc/evidence/20260824-063610-fast/` 的 3 steps 均 exit 0
  （A1，12.951 秒）。
- Profile/A3/J1/READY 未推进；未运行 provider、读取 API Key 或发送真实数据，未 push/tag/PR。
