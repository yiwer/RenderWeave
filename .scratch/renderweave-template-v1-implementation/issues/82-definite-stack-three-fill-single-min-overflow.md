# 实现 definite Stack 三 FILL 单 min-overflow 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling，并明确所有 min
总和超过空间时允许 overflow 而不缩减 min。T70 已支持 exactly-two 的唯一 active min 严格大于 remaining；
T71 已支持 exactly-three 的唯一 active bound 不高于 remaining。如何把同一 min-overflow 退化扩展到恰好三项，
同时不开放 post-freeze redistribution、unfrozen bound、多个 active、一般循环或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有三个 main-axis FILL；三项 weight 均 positive；第一轮 share
   恰好一个 finite/nonnegative min-only bound active，且该 min 严格大于本轮 `remaining`；另两项 owning-axis
   min/max 必须全部 absent。active child 的 mixed bound、任一 unfrozen bound 或多个 active 均不进入本票。
3. **单次 min-overflow 后终止**：active child 取 authored min，另外两项按 authored position 直接取正零；不得
   计算负 `remaining-min`、post-freeze weight sum/share、redistribution、第二次 freeze、循环或 epsilon/tolerance。
   T71 的 `min <= remaining` 一次重分配与 T75 的 second-min 路径保持原 bit behavior。
4. **溢出与 justify**：accepted 三项尺寸和可大于 remaining；既有 occupied 规则据此产生 overflow，free-space
   仍为 `max(0, available-occupied)=0`，所以六种 `justifyContent` 均退回零 extra/START 分布。signed margins、gap、
   authored cursor 与 cross alignment 不变。
5. **布局与错误顺序不变**：每项仍按 authored order 至多执行一次 deferred cross-HUG remeasure；deeper child/
   resource/rotation 错误继续按 authored DFS first-error 暴露，全有或全无 output 不变。
6. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/45`。把既有 three-FILL active-min-overflow
   negative 转为 active-first positive，新增 active-middle、active-last、COLUMN 与 cross-HUG positives，并以
   active child 同时携带 inactive max 的 mixed-min-overflow negative 替换。能力值新增
   `OR_EXACT_THREE_FILL_SINGLE_ACTIVE_MIN_OVERFLOW`；目标 202 laid-out + 16 unsupported、218 cases、652 checks，
   fixture `/3` bytes 不变。
7. **固定能力边界**：mixed active-min overflow、任一 unfrozen bound、首轮多个 active、second-min sum overflow、
   four-or-more active-bound FILL、一般多轮 water filling、Profile residual tolerance/public numeric error、
   HUG-main FILL cycle、rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource fetch/decode、
   scene/raster/JPEG、daemon RESULT/Profile、Java/OpenAPI/migration/Web/route、J1/A3/READY 与外部副作用均不在本票。
8. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/45` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个转正
  active-min-overflow case、同一 `STACK_MAIN_FILL` occurrence 共同 RED；再分别实现严格控制流并达到 exact-bit
  GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、`py_compile`、
  JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/45` 先只改 vectors/identity；Rust primary 与 Python independent verifier 在首个
  `stack-three-main-fills-active-min-overflows-remaining` case 共同 RED，均返回首个 authored FILL 的
  `STACK_MAIN_FILL`（`rwocc_0000000000000002`）。分别实现严格分支后，Rust focused 1/1、Python independent
  218/218 均 GREEN；最终语料为 202 laid-out + 16 unsupported、218 cases/652 checks。vector SHA-256 为
  `9e0a86fd5ba9240fc7c75743f235b48e4fe9d68aba111664d443adbcf85be83b`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- Rust 只在 exactly-three、唯一 active finite/nonnegative min-only 严格大于 remaining、另两项 owning-axis
  bound 全 absent 时，按 authored position 显式提交 `min/0/0`；Python independent verifier 以独立控制流重放。
  不计算负 residual/post-freeze weight，不开放 redistribution、第二次 freeze、一般循环或 tolerance；能力值新增
  `OR_EXACT_THREE_FILL_SINGLE_ACTIVE_MIN_OVERFLOW`。mixed active-min 与任一 unfrozen bound 继续 fail closed。
- focused/local 的 Cargo fmt/check、workspace clippy `--all-targets --locked --offline -D warnings`、workspace tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check` 全绿。分级 A1 证据为 `render`
  `.sdlc/evidence/20260824-014002-render/`（20.899 秒）、affected `fast`
  `.sdlc/evidence/20260824-014029-fast/`（10.255 秒）、顺序 `server`
  `.sdlc/evidence/20260824-014052-server/`（1226.166 秒）与 17-step `full`
  `.sdlc/evidence/20260824-020133-full/`（1837.817 秒），全部 exit 0。
- full 中 definite-layout independent replay 为 218/218、652 checks；App 344/0/0/15、Node 24 Web 26 files/212
  tests、runtime canary、23 passed + 1 controlled skip Playwright、prototype/Draft journeys 与 inference replay E2E
  1/1 均通过。R0 provider attempts=0；R1 A2 60 cases/58 metrics、J0；P0 A2 60 cases（20 holdout）/58 metrics，
  API Key reads/provider attempts/reservations/cost 均为 0。Profile/scene/raster/daemon 与 A3/J1/READY 未推进，未
  push/tag/PR。
- 本状态更新后的 resolution `fast` `.sdlc/evidence/20260824-023332-fast/` 的 3 steps 也均 exit 0（A1，
  10.598 秒）。
