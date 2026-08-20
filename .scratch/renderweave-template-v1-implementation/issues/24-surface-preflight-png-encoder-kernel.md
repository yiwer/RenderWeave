# 实现 surface preflight 与 exact PNG encoder kernel

Type: task
Status: resolved / automated_verified
Resolved by: Codex `/root`（single-writer）
Blocked by: 22, 23（均已 resolved：offline Rust workspace/render gate 与 exact RenderDocument Canvas handoff）

## Question

如何在不提前注册 Renderer/Output Profile、不生成任何 synthetic rendered scene、也不把编码器单测冒充 raster
认证的前提下，先把冻结 Ticket 16 中完全独立且 byte-exact 的 surface preflight 与 PNG encoder 物化成 Rust
kernel：从 T23 已准入的 Canvas/bleed 与 effective PNG DPI 精确计算输出尺寸和容量，接收已经完成 raster/
unpremultiply 的 canonical straight RGBA8 surface，并产出严格 `renderweave-output-png/1.0` bytes；同时让 Rust
primary 与 Python 标准库 independent verifier 重放同一向量？

## Answer（本票冻结的实施决定）

1. **deep kernel seam**：新增 workspace-internal `renderweave-renderer-output-png` crate；唯一入口由 exact
   surface preflight 与 `encode_straight_rgba8` 组成，隐藏 decimal、stored-DEFLATE、chunk、CRC 与 Adler
   细节。它不依赖 daemon/protocol/document crate，不暴露 public product API，也不让 Engine 在 Profile
   lookup 失败后继续执行。
2. **exact surface arithmetic**：只接收 T23 RenderDocument 已量化为最多六位小数的 canonical nonnegative pt
   decimal 与 effective positive integer DPI；使用十进制 `10^6` 定点和 checked `i128` 实现
   `ROUND_HALF_UP((trim + two bleeds) × dpi / 72)`，禁止 binary float、逐边 rounding 与 pixel snapping。
   执行并命名冻结 Ticket 19 数值边界：DPI 600、surface edge 16,384 px、surface 50,000,000 px、RGBA8
   200,000,000 bytes、encoder scratch 67,108,864 bytes、encoded image 536,870,912 bytes；零尺寸或算术溢出
   fail closed。
3. **exact PNG bytes**：输入恰为 row-major straight RGBA8，长度必须等于 `width × height × 4`。输出固定
   `signature → IHDR → sRGB(intent=0) → pHYs → IDAT+ → IEND`；RGBA8/type 6、non-interlaced、每行 filter 0、
   zlib `78 01`、stored DEFLATE block 最大 65,535 bytes、IDAT payload 最大 1,048,576 bytes、标准 Adler-32/
   PNG CRC；pHYs 为 `ROUND_HALF_UP(dpi × 5000 / 127)`。encoder 直接向预估容量的结果 buffer 流式写 IDAT，
   不复制完整 filtered/zlib surface，scratch 上界由实际最大 chunk 证明。
4. **共同语料与 TDD**：新增 closed vector manifest，至少覆盖 surface half-up、bleed 整体舍入、DPI/edge/
   pixel/zero 边界，transparent/opaque/多像素 exact bytes，以及 65,535-byte DEFLATE 和 1,048,576-byte IDAT
   分块边界。先提交 Rust vector tests 与 Python verifier 使实现缺位 RED，再完成 kernel；Python 只用标准库
   独立重算 bytes/chunk/CRC/Adler/尺寸与 SHA，不调用 Rust 或共享 helper。
5. **gate 与诚实边界**：`render` gate 增加 output-PNG independent report并继续在 Windows Rust workspace与
   pinned no-network Linux Docker 重放；process manifest、HELLO capability、daemon execution path 与
   `rendererProfiles:[]` 不变。`profileAvailability=NOT_REGISTERED`、`certificationStatus=NOT_CERTIFIED`、
   `rasterImplementation=ABSENT` 持续为硬断言。
6. **明确排除**：本票不做 layout/measure/arrange、resource fetch/decode、font shaping、premultiply/
   unpremultiply、paint/raster、JPEG、QR/Barcode、daemon RESULT、Java/public render/preview route、OpenAPI/Web、
   physical Linux CPU-family certification、J1/A3 或 Ticket 19 formal record issuance。向量里的生成 RGBA bytes
   只验证 encoder 输入合同，绝不称为 RenderEngine raster 或 exact-pixel certification。

## 验证与完成信号

- 局部：Rust crate focused tests（含向量）→ workspace fmt/clippy/test；Python independent verifier。
- 受影响：`render` → `server`/`fast` → 完整 `full`，按局部到 Goal 扩大且保留原始 evidence。
- 保证上限：Rust/kernel/gate 为 A1；Rust 与独立 Python 对相同 exact surface/PNG vectors 为 A2；Docker/Windows
  不构成物理 Linux certification，无 A3/J1。
- 完成：Ticket 24 仅在全部 gate 绿色后改为 `resolved / automated_verified`，形成一个 verified local commit、
  worktree clean；不 push/tag/PR，且不升级任何 Renderer/Template v1 READY 状态。

## Resolution（2026-08-21）

1. 新增 workspace-internal `renderweave-renderer-output-png` crate；`preflight_surface` 使用 canonical decimal6、
   checked `i128` 与整体 half-up 计算 trim+bleed surface，执行 DPI 600、edge 16,384、pixel 50,000,000、
   RGBA8 200,000,000、encoder scratch 64 MiB 与 encoded 512 MiB 的冻结边界。`SurfaceDimensions` 在任何
   raster/output allocation 前同时给出 exact width/height、RGBA8 bytes 与 PNG encoded bytes。
2. `encode_straight_rgba8` 已实现冻结 PNG bytes：固定 chunk 顺序、RGBA8 IHDR、sRGB intent 0、exact pHYs、
   filter 0、`78 01` zlib、65,535-byte stored blocks、1 MiB IDAT payload、Adler-32 与 CRC-32；它直接向唯一
   预留结果 buffer 写入，不复制完整 filtered/zlib surface，并拒绝长度漂移及 alpha=0/RGB非零输入。
3. 共享 manifest 包含 10 个 surface 与 6 个 PNG case；Rust primary 3/3，Python stdlib independent verifier
   16/16、90 checks，向量 SHA-256 为
   `78d63f97d59af3e70e15bae0236b7c02701afa3137b15bfecbac1a57634d1c56`。小图固定完整 exact bytes，较大图
   固定 SHA、DEFLATE block 与 IDAT split；stdlib zlib 再独立解压核对 filtered bytes。
4. `render` gate 1.1 已把该 A2 replay 纳入 Windows workspace 与 pinned Rust 1.89 no-network Linux Docker
   复核；通过证据为 `.sdlc/evidence/20260821-021837-render/`。受影响 server 与 fast 分别通过于
   `.sdlc/evidence/20260821-022145-server/`、`.sdlc/evidence/20260821-023312-fast/`。最终整树 `full` 在
   本 Resolution 冻结后捕获，目录只在提交交接中报告以避免证据自指。
5. Cargo.lock、process manifest 与 HELLO vectors 的 SHA 链已同步，但 process capability 与 daemon execution
   path 未新增输出能力；`rendererProfiles:[]`、`NOT_REGISTERED`、`NOT_CERTIFIED`、raster `ABSENT`、daemon
   output `UNWIRED` 均由 gate 硬断言。没有 layout/resource/shaping/raster/JPEG/公开 route、formal record、
   physical certification、J1/A3、provider、真实数据或 API Key 副作用。
