# 实现 definite Stack 两 FILL 单 min-overflow 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69（均已 resolved）

## Question

Ticket 10 §3/§7 冻结了 multiple Stack main-FILL 的 weighted iterative min/max water filling，并明确“所有
min 总和超过空间时允许布局溢出而不缩减 min”。T69 已支持冻结值不超过 remaining 的 exactly-two/
single-active-bound 路径。如何实现唯一 active min 严格大于 remaining 的最小溢出退化路径，同时不开放第二次
freeze、三个 FILL 重分、一般 water filling 或 Profile residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化既有 allocation deep module**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好有两个 main-axis FILL；第一轮 share 恰好一个 active
   `min*Pt`，另一项 owning axis 的 min/max 均 absent；active min 必须 finite、非负且严格大于本轮
   `remaining`。active max、两项 active、另一项携带任何 bound 与三个及以上 FILL 不进入本票。
3. **单次 min-overflow 冻结**：active child 取 authored min，唯一未冻结 child 直接取正零。不得计算负
   `remaining-min` 后再靠 epsilon 归零，不做第二次 division、weight sum、clamp、freeze 或迭代；T69 的
   `min <= remaining` exact-remainder 路径保持原 bit behavior。
4. **溢出与 justify**：accepted 两项尺寸和可大于 remaining；既有 occupied 规则据此产生 overflow，free
   space 仍为 `max(0, available-occupied)=0`，所以所有 justifyContent 退回零 extra/START 分布。signed
   margins、gap、authored order、cross alignment 与 cursor 公式不变。
5. **单次 deferred remeasure**：冻结结果 staged 后，每项仍按 authored order 至多执行一次 deferred
   cross-HUG remeasure；deeper child/resource/rotation 错误继续按 authored DFS first-error 暴露，全有或全无
   output 不变。
6. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/33`。把现有 min-overflow negative 转
   positive，并新增 active-first、COLUMN 对称、cross-HUG remeasure 与 initial-remaining-zero positives；目标
   144 laid-out + 12 unsupported、156 cases、470 checks，fixture `/3` bytes 不变。
7. **固定能力边界**：另一项带 bound 后的 cascading/second freeze、三个及以上 FILL redistribution、多个
   active min/max、全部 max 后 justify release 的一般轮次、Profile residual tolerance/public numeric error、
   HUG main-axis FILL cycle、rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource
   fetch/decode、scene/raster/JPEG、daemon RESULT/Profile 与 E6 均不在本票。
8. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取
   API Key、不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改 shared `/33` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个
  转正 min-overflow case 共同 RED；再分别实现严格控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- shared `/33` 的 Rust primary 与 Python independent verifier 先在同一首个转正 case
  `stack-two-main-fills-active-min-overflows-remaining` 共同 RED；分别实现严格控制流后达到 144 laid-out +
  12 unsupported、156/156 cases、470 checks。
- `stack_main_fill_allocations` 保留 T69 的 within-remaining exact-remainder 路径；只在 exactly-two、唯一 active
  min、另一项 owning-axis 无 bound 且 min 严格大于 remaining 时，把 active child 冻结到 authored min、唯一
  未冻结 child 直接设为正零。控制流不计算负 residual，不执行第二次 division/freeze/redistribution；每项随后仍
  至多一次 deferred cross-HUG remeasure。
- vector identity 为 `renderweave-definite-layout-vectors/33`，Python independent identity 为
  `renderweave-definite-layout-python-independent/33`；vector SHA-256 为
  `478624aa567fb559364c8117a1ab55a9f1b0a7a673c8e5a00be17de18b5ddce2`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- focused Rust 3/3、Python independent 156/156、workspace fmt/clippy `-D warnings`/tests、`py_compile`、JSON
  inventory/SHA/unique 与 `git diff --check` 全绿；分级证据为 `render`
  `.sdlc/evidence/20260823-110718-render/`、affected `fast` `.sdlc/evidence/20260823-110800-fast/`、顺序
  `server` `.sdlc/evidence/20260823-110816-server/` 与 Goal `full`
  `.sdlc/evidence/20260823-112808-full/`；resolution 后 fast
  `.sdlc/evidence/20260823-115754-fast/` 的 3 steps 也均 exit 0（A1，10.057 秒）。
- full 17 steps 均 exit 0、总耗时 1631.439 秒；App 344 tests/0 failures/0 errors/15 skipped，Node 24 Web
  26 files/212 tests、runtime canary、23 passed + 1 controlled skip Playwright、browser journeys 与最终 inference
  replay E2E 1/1 均通过。R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0。
- 另一项带 bound 后的 second freeze、three-FILL active-bound redistribution、多 active/cascading freeze 与一般
  water filling 仍在首个 authored FILL occurrence 返回 `STACK_MAIN_FILL`；Profile 仍 `NOT_REGISTERED`、
  certification `NOT_CERTIFIED`、Raster `ABSENT`、daemon `UNWIRED`，未推进 A3/J1/READY。
