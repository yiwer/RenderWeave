# 实现 definite multi-FRACTION Grid stable last-remainder 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 23, 25, 26, 34, 39, 40, 41, 44, 45, 57, 58, 59, 60, 64（均已 resolved）

## Question

Ticket 10 §3/§8 已冻结 definite Grid 轴按 FIXED → AUTO → FRACTION 求解、FRACTION 按正 weight 分配剩余空间，
并要求 authored-order 前 `n-1` 项计算、最后一项接收余量；当前 kernel 仍在第二个 FRACTION 处 fail closed。
如何只实现不需要尚未物化 Profile tolerance 的 finite/nonnegative 子闭包，同时保留跨多 AUTO deficit、Stack
water filling 与一般数值误差边界？

## Answer（本票冻结的实施决定）

1. **只深化既有 Grid axis deep module**：继续使用 `definite_grid_axis`，不新增 public API、crate、Profile、
   parser、preflight bypass 或第二套 track solver。FIXED 与 independent AUTO 阶段、columns-first 顺序、arrange
   与全有或全无 output 保持不变。
2. **只支持 definite multi-FRACTION**：轴必须已有 finite definite `available`；所有 FRACTION weight 已由
   admission/preflight 证明为 finite positive binary64。先按现有顺序解 FIXED/AUTO/gaps，再令
   `remaining=max(0,available-usedWithoutFraction)`。
3. **固定稳定余量算法**：按 authored track order 求 finite `totalWeight`；前 `n-1` 个 FRACTION 以固定
   binary64 顺序 `remaining * weight / totalWeight` 求 share，最后一个接收
   `remaining - allocatedBeforeLast`。每次累加、share 与最后余量都必须 finite；最后余量必须非负，`-0` 归零。
4. **数值异常继续 fail closed**：weight sum/intermediate 非 finite 或最后余量为负时仍返回 closed internal
   `GRID_FRACTION_TRACK`。canonical decimal6 的 checked `i128` 准入与文档容量使 weight-sum overflow 对 admitted
   document 不可达，因此保留防御性 guard 而不伪造越过准入的 shared negative；本票不选择 epsilon/tolerance。
5. **保持 spec stage order**：AUTO 即使 authored 晚于 FRACTION 仍先求；FIXED/AUTO+gaps 超 available 时所有
   FRACTION 为零且不缩小既有 track；没有 FRACTION 的 extra space 仍留在物理右/下端。
6. **共同语料与 TDD**：shared vector/verifier identity 升级为 `/28`。把两个现有 multi-FRACTION boundary
   negatives 转为 positives，并新增 mixed FIXED/gaps/weighted last remainder 与 overflow-zero positives，目标
   123 laid-out + 13 unsupported、136 cases、409 checks；layout-preflight fixture bytes 不变。
7. **固定能力边界**：跨多个 AUTO 的平均 deficit、multiple Stack main FILL/min-max water filling、Profile
   tolerance/numeric public error、一般 `UNBOUNDED/AT_MOST/EXACT` constraint engine、rows→columns、任意非直角
   rotation、Text/Image/Vector intrinsic、resource/scene/raster/JPEG、daemon RESULT/Profile 与 E6 均不在本票。
8. **诚实状态**：本票最高只到 `automated_verified`；不推进 A3/J1/READY，不运行 provider、不读取 API Key、
   不发送真实数据，不 push/tag/PR。

## 验证与完成信号

- TDD：先只改变 shared `/28` vectors/identity，让 Rust primary 与 Python independent verifier 在同一首个新
  positive 共同 RED；再分别实现固定控制流并达到 exact-bit GREEN。
- 局部：focused Cargo vector tests、Python stdlib replay、workspace fmt/clippy `-D warnings`/tests、
  `py_compile`、JSON inventory、fixture/vector SHA 与 `git diff --check`。
- 分级：`render` → affected `fast` → 顺序 `server` → `full` → resolution `fast`；Maven gate 不并发。
- 完成：全部 gate 绿色后才改为 `resolved / automated_verified` 并形成 verified local commit。

## Resolution evidence

- TDD：shared `/28` 先把两个既有 multi-FRACTION boundary negatives 转为 positives，并加入 mixed
  FIXED/gaps/`1fr+3fr` last remainder 与 fixed-overflow-zero positives；Rust primary 与 Python independent
  verifier 在同一首个新增 positive 共同 RED，分别实现冻结算法后达到 123 laid-out + 13 unsupported、
  136/136 cases、409 checks exact-bit GREEN。
- 实施：`definite_grid_axis` 的 FRACTION scan 现在保留 authored-order `(index, weight)`；singleton 行为不变，
  multi-FRACTION 先按固定顺序求 finite `totalWeight`，前 `n-1` 项计算 weighted share，最后一项接收余量。
  FIXED/AUTO/gaps overflow 时所有 FRACTION 稳定为零；所有 finite/nonnegative guard、`-0` 归零、columns-first、
  admission/preflight、arrange、authored DFS first-error 与全有或全无 output 合同保持关闭边界。
- identity：shared vector/verifier identity
  `renderweave-definite-layout-vectors/28` / `renderweave-definite-layout-python-independent/28`；vector SHA-256
  `55e1f99966a386897bd3b9f727342634f11a346ff1675e2920e6ad3a751c2cd6`，layout-preflight fixture `/3`
  SHA-256 保持 `a11475bcebad7e1c35cb0acd7018419d94afcb4b37d7f1df7346a055ad1df669`。
- 局部验证：focused Rust vector tests 3/3、Python independent 136/136 cases/409 checks、workspace `cargo fmt`、
  clippy `-D warnings`、全部 Cargo tests、Python `py_compile`、JSON inventory/hash/unique 与 `git diff --check`
  全绿。
- 分级 gate：`render` `.sdlc/evidence/20260823-053418-render/`、affected `fast`
  `.sdlc/evidence/20260823-053443-fast/`、顺序 `server` `.sdlc/evidence/20260823-053502-server/` 与 Goal `full`
  `.sdlc/evidence/20260823-055512-full/` 均通过。full 17 steps 均 exit 0、总耗时 1749.85 秒；Maven App
  344 tests/0 failures/0 errors/15 skipped，Node 24 Web 26 files/212 tests、runtime canary、23 passed +
  1 controlled skip Playwright、prototype/Draft/Inference browser journeys 与最终 inference replay E2E 1/1
  均通过；resolution 后 fast `.sdlc/evidence/20260823-062823-fast/` 的 3 steps 也均 exit 0。
- 边界：R0/R1/P0 provider attempts=0，P0 API Key reads/reservations/cost=0；Profile `NOT_REGISTERED`、
  certification `NOT_CERTIFIED`、Raster `ABSENT`、daemon `UNWIRED`。跨多 AUTO deficit、Stack water filling、
  general constraint/tolerance、resource/scene/pixel 与完整 Renderer 仍未实现；最高仅
  `automated_verified`，未推进 A3/J1/READY，未 push/tag/PR。
