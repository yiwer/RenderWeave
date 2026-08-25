# 实现 Renderer daemon 单槽 FIFO 与并发控制帧纵切

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 08, 19（容量数值）, 22, 47, 101, 105, 108, 122

## Question

现有 Linux UDS daemon 已能 strict 解析帧、保留 terminal replay、按 manifest 顺序准备资源，并拥有一个独立的
prepared PNG result kernel；但每条连接仍同步执行 `read → prepare → write`。资源处理期间不能读取同一连接上的
`CANCEL` 或其他 requestId，且尚未物化 Ticket 19 已冻结的 1 active slot、4 FIFO positions 与 5 秒 queue wait。
如何在不注册 partial Renderer Profile、不调用当前 success kernel的前提下，先把真实进程控制面改为可多路复用、
有界且可取消的生产结构？

## Answer（本票冻结的实施决定）

1. **沿用唯一公共 seam**：可观察接口仍只有 ADR-0045 的 UDS typed frames；不新增管理 HTTP、调试端口、
   test-only Profile 或外露 registry API。测试只经真实 frame bytes 观察 requestId 对应的 terminal response。
2. **单 reader、单 worker、单 writer**：reader 持续 strict 解析 `COMMAND/CANCEL`，worker 严格一次只执行一个
   accepted request，writer 串行写完整 terminal frame group，禁止 metadata/image 或不同 request 的帧交错。
3. **容量先于工作**：active lookup/identity conflict 在 queue admission 前；新 request 只有原子取得 4 个等待位之一
   才 accepted，无法取位返回 nonterminal `RENDER_ENGINE_BUSY`，reservation 保留到原 deadline，同 digest 可重试。
4. **FIFO 与 deadline**：waiting request 按首次 accepted 顺序进入唯一 slot；queue wait 同时受冻结 5 秒上限与原
   absolute deadline 约束，耗尽形成 terminal `RENDER_DEADLINE_EXCEEDED`，任何 retry/join/cancel 均不续期。
5. **cancel/replay**：pre-command cancel 保留既有 60 秒 tombstone；queued cancel 原子移出等待集并 terminal
   `RENDER_CANCELLED`；active cancel 设置同一 request 的 cancellation fence，所有既有资源/Engine工作完成后、任何
   result seal 前必须丢弃结果并形成唯一 cancel terminal。same digest join/replay观察同一 terminal；不同 digest仍
   `RENDER_REQUEST_CONFLICT`。
6. **诚实执行边界**：当前机器 manifest仍为 `NOT_REGISTERED/NOT_CERTIFIED/ABSENT`，worker完成既有 document、lease、
   target、layout preflight与resource preparation后仍返回原有 fail-closed internal problem；本票不调用T108 success
   kernel，也不声称 50ms cooperative checkpoint或250ms物理停止已完成。
7. **明确排除**：Renderer/Profile registration、native build、Text/AA/JPEG/LayoutTrace、public Rendering API、E6、
   正式 `/templates` route、physical Linux certification、J1/A3/READY、provider/API Key/真实数据/费用及部署。

## TDD 与验证

- 已确认的测试 seam 是 ADR-0045 UDS frame protocol。首个 Linux tracer test 先让外部 HTTPS fetch boundary受控延迟，
  在同一连接中先发 `COMMAND` 再发 exact `CANCEL`；旧同步 daemon 必须 RED（先返回 internal terminal），GREEN 后首个
  terminal 必须是 `RENDER_CANCELLED` 且零 `RESULT_*` frame。
- 后续逐个 tracer覆盖 1 active + 4 FIFO + 第6个 BUSY、FIFO顺序、queued deadline、same-digest join/replay、conflict与
  writer frame-group原子性；每一条严格执行 red → green，不直接测试私有字段或内部调用次数。
- focused Linux/Rust后依次运行 canonical `render`、affected `fast`、sequential `server`、Goal `full` 与resolution
  `fast`；Maven不并发，精确 staging，不把用户 Image-Only dirty work纳入提交。

## Results

- Linux 生产 UDS 路径已改为持续 reader、单槽 FIFO scheduler 与串行 writer；同一连接可在 active work 期间继续
  接收其他 requestId 与 `CANCEL`，完整 terminal frame group 不交错。request registry 位于连接生命周期之外，
  terminal replay、pre-command cancel tombstone 与容量 reservation 可跨连接 session 保持原 identity/deadline。
- 已物化 1 active + 4 waiting、首次 accepted 严格 FIFO、5 秒 queue wait 与原 absolute deadline 的较早者、queue 满
  nonterminal `RENDER_ENGINE_BUSY` reservation、queued/active cancel、final deadline fence、same-digest join/replay 和
  drift conflict。active work 只能在 seal 前丢弃；没有把它报告成 50ms checkpoint 或 250ms physical stop。
- TDD 的外部 UDS frame tracer 逐条观察过旧实现的错误结果：处理期 `CANCEL` 前先返回 internal terminal、第 2 个
  request 直接 BUSY、queued cancel 重复 terminal、queue wait 约 7 秒、identity drift 被 BUSY 遮蔽、deadline 后泄漏
  `FETCH_FAILED`、expired request 被 BUSY 遮蔽、active deadline 被 CANCELLED 覆盖，以及 BUSY stage 不在
  `REQUEST_CONTROL`；GREEN 后这些路径均按冻结顺序与唯一 terminal 语义通过。
- Linux offline/no-network workspace 回归中 daemon 22 tests 与既有 prepared-result integration 3 tests 全绿；Windows
  workspace、Rust fmt/clippy、Java process port 与 A2 independent replay 同时通过。A1 evidence：canonical `render`
  `.sdlc/evidence/20260826-040703-render/`、affected `fast` `.sdlc/evidence/20260826-040933-fast/`、clean snapshot
  sequential `server` `.sdlc/evidence/20260826-044232-server/`，以及 clean-checkout 17-step Goal `full`
  `.sdlc/evidence/20260826-053343-full/`（17/17，1514.434 秒）均 passed；状态回填后的 resolution `fast`
  `.sdlc/evidence/20260826-060147-fast/` 也以 3/3 steps 通过。
- full 覆盖 App 362 tests/0 failures/0 errors/15 skipped、Node 24 Web 28 files/217 tests、runtime canary、Playwright
  23 passed + 1 controlled skip、生产 Draft 与 inference browser journeys；R0/R1/P0 provider attempts=0，P0 API Key
  reads/reservations/cost/open authorization=0。Windows clean checkout 暴露 replay JSON 的 CRLF 漂移后，仓库已用
  `.gitattributes` 固定 `.scratch/renderweave-template-v1/**` 为 LF（`.bin` 保持 binary），fresh worktree 验证字节不变。
- 状态为 `resolved / automated_verified`。Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、daemon success output path `UNWIRED`、native stack `BUILD_NOT_AUTHORIZED`；未调用 T108 success kernel，
  public Rendering API/E6/正式 `/templates` route 仍 `CLOSED`，未推进 J1/A3/READY，`/prototype` 不计最终交付。
