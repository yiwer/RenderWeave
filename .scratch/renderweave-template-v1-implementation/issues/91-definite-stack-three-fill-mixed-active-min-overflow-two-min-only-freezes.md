# 实现 definite Stack 三 FILL mixed active-min overflow 两个 min-only freezes 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 33, 38, 43, 55, 56, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90（均已 resolved）

## Question

T90 已允许 exactly-three main FILL 的 mixed active min 自身大于 remaining 后，另外两项为一个合法
mixed min/max 与一个 positive min-only，并固定到三个 authored minima。若另外两项都只携带 positive min，
且二者在初始 proportional share 下都 inactive，如何固定到三个 minima，而不开放 active child 自身为
min-only、初始 multiple-active、post-overflow redistribution、four-or-more FILL、一般循环或 Profile
residual tolerance？

## Answer（本票冻结的实施决定）

1. **只深化 T90 early branch**：继续使用 `measure_and_allocate_stack_children` →
   `stack_main_fill_allocations` staged seam；不新增 public API、crate、parser、Profile、route 或第二套 solver。
2. **固定准入子集**：owning definite Stack 恰好三个 main-axis FILL，三项 weight 均 positive；第一轮恰好一个
   finite/nonnegative active min，active child 携带合法 finite/nonnegative min/max、`min <= max`，且 first min
   严格大于 `remaining`。另外两项都携带 finite positive min、不携带 max，且各自初始 proportional share
   `>= min`。`share == min`、`share == max` 与 `min == max` 均接受。
3. **固定两个 additional min 后终止**：active child 取 first min，另外两项各取 authored min，按 authored
   position 显式提交三个 minima。因 first min 已严格大于 remaining 且两个 additional min 均为正，尺寸和
   必然 overflow；不计算负 residual、post-overflow weight/share、redistribution、第四轮、循环或
   epsilon/tolerance。
4. **既有输出语义不变**：既有 occupied/free-space 公式令六种 `justifyContent` 退回零 extra/START 分布。
   signed margins、gap、cursor、cross alignment、每项至多一次 deferred cross-HUG remeasure、authored DFS
   first-error 与全有或全无 output 均不变。
5. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/54`。先把 T90 replacement negative 转为
   active-first tracer positive，使 Rust primary 与 Python independent verifier 在同一 case/occurrence 共同
   RED；最小实现 GREEN 后，再新增 active-middle、active-last equality、COLUMN 与 cross-HUG regression
   positives。以 active child 为 min-only、另外两项也为 min-only 的 negative 替换。能力值新增
   `OR_EXACT_THREE_FILL_MIXED_ACTIVE_MIN_OVERFLOW_TWO_MIN_ONLY_FREEZES_OVERFLOW`；目标 247 laid-out +
   16 unsupported、263 cases/787 checks，fixture `/3` bytes 不变。
6. **固定能力边界**：active child 为 min-only、任一 additional max-only/absent/非法 mixed、初始 share 越界、
   首轮多个 active、active child min 不大于 remaining、post-overflow redistribution、four-or-more active-bound
   FILL、一般多轮 water filling、Profile residual tolerance/public numeric error、HUG-main FILL cycle、
   rows→columns、任意非直角 rotation、Text/Image/compositionViewport、resource fetch/decode、scene/raster/JPEG、
   daemon RESULT/Profile、Java/OpenAPI/migration/Web/route、J1/A3/READY 与外部副作用均不在本票。
7. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。它深化最终产品所依赖的权威 Renderer 链路，但不等于最终 Template 页面已
   开放。

## 验证与完成信号

- TDD tracer：先只改 shared `/54` identity 与首个转正 vector，让 Rust primary 与 Python independent verifier
  在同一 `stack-three-main-fills-mixed-active-min-overflow-two-min-only-freezes`、同一 `STACK_MAIN_FILL`
  occurrence 共同 RED；再分别实现严格控制流并 exact-bit GREEN。
- 回归：GREEN 后补齐 active-middle、active-last equality、COLUMN 与 cross-HUG vectors，并确认 replacement
  negative 继续 fail closed。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/check/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory/SHA/unique 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Result

- shared identity 已升级为 `/54`。首个
  `stack-three-main-fills-mixed-active-min-overflow-two-min-only-freezes` tracer 在 Rust primary 与 Python
  independent verifier 中于同一 `STACK_MAIN_FILL` occurrence `rwocc_0000000000000002` 共同 RED；移除
  additional minimum candidates 必须至少一个为 mixed 的额外限制后共同 GREEN。
- 已补齐 active-middle、active-last equality、COLUMN 与 cross-HUG remeasure positives，并以 active child
  min-only + two min-only replacement negative 继续 fail closed。最终语料为 247 laid-out + 16 unsupported，
  263/263 cases、787 checks；vector SHA-256 为
  `fc3b06ff5691b538ed95d1c43437ec89d23ab14965d0237363fb8226611e6bec`，fixture `/3` SHA-256 保持
  `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- 局部 fmt/check/clippy `-D warnings`/workspace tests、Python `py_compile`、JSON inventory/unique 与
  `git diff --check` 均绿色；`render` `.sdlc/evidence/20260824-114308-render/`、affected `fast`
  `.sdlc/evidence/20260824-114342-fast/`、顺序 `server` `.sdlc/evidence/20260824-114400-server/` 与 Goal
  `full` `.sdlc/evidence/20260824-120412-full/` 均 exit 0。
- full 中 definite-layout A2 replay 为 263/263、787 checks；App 为 344/0/0/15，Node 24 Web 为 26 files/
  212 tests，runtime canary 通过，Playwright 23 passed + 1 controlled skip，Draft 与 inference browser E2E
  均绿色。R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost/open authorization=0；R1/P0 均为
  A2 strict replay。
- 状态回填后的 resolution `fast` `.sdlc/evidence/20260824-122543-fast/` 三步均 exit 0；其中
  `t91-final-a2.json` 再次独立确认 263/263 cases、787 checks、provider attempts 0。
- 未推进 Profile/A3/J1/READY，未运行 provider、读取 API Key、发送真实数据或 push/tag/PR。现有 Web 构建仍
  只有 Template prototype/fixture，不含正式 Template 产品 route；本票只收口 Renderer 前置能力，不宣称最终
  Template 页面已可预览。
