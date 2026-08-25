# 实现 prepared IMAGE 精确直角旋转 Engine PNG 子闭包

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 10, 16, 19, 23, 24, 25, 26, 48, 92, 97, 99, 103, 105, 106, 107, 108, 109, 110, 111（本切片前置均已 resolved）

## Question

T107–T111 已把 prepared IMAGE 的 exact 1:1 pixels、alpha、authored source-over、rectangular clip 与 subtree
opacity isolation 接入唯一 Engine PNG 内核，但任何非零 transform 仍以 `IMAGE_PAINT` fail closed。如何接受
中心原点、单位正缩放、像素对齐方形 IMAGE 的精确 90° 倍数旋转，并在 clip/opacity/source-over 中保持正确
source mapping，同时不开放任意角度、reflection、缩放、resampling、subpixel coverage、native raster build 或
不完整 Renderer Profile？

## Answer（本票冻结的实施决定）

1. **只深化唯一 Engine deep Interface**：继续使用
   `render_png_with_prepared_resources(document, manifest, dpi)`；不新增 caller pixels、test bypass、第二套
   scene/raster、Java/OpenAPI/Web/route 或 Profile registration seam。
2. **固定准入子集**：IMAGE 必须沿既有路径完成 prepared identity、orientation-normalized straight RGBA8、
   exact 1:1 source/device dimensions 与整数 device edges；transform 必须是 exact
   `origin=(0.5,0.5)`、`scale=(1,1)`，rotation 仅接受 `-360..360` 内的 90° 整数倍。非零旋转当前还要求
   source/device box 为同尺寸方形，使旋转后的 self clip 与 device box 精确重合。
3. **精确 clockwise mapping**：按目标 pixel 的 box-local integer coordinate 做 inverse quarter-turn mapping；
   90°/180°/270° 分别映射到唯一 source pixel，不执行 sampling、浮点三角函数、coverage 或 pixel snap。
   已裁剪目标先恢复为 unclipped local coordinate，再做 inverse mapping，确保 ancestor clip 不改变 source identity。
4. **保持 alpha 与绘制顺序**：source straight RGBA8 仍按 T109 固定整数规则 premultiply/source-over；T111
   opacity layer 仍在旋转后的整棵 subtree 上统一合成。资源准备、layout、scene prepare 任一失败继续零 output，
   authored preorder 与首错顺序不变。
5. **TDD 与独立重放**：prepared IMAGE shared vectors 升级为 `/3`；先把既有
   `rotated-image-fails-closed` 转为 90° positive，使 Rust public Interface 与 Python independent verifier 共同
   RED。GREEN 后补 180°、270°、负 90°、ancestor clip + partial opacity positives，并以 45° rotation
   replacement negative 保持 `IMAGE_PAINT` fail closed。
6. **诚实边界**：本票不开放 non-square nonzero rotation、非中心 origin、negative/non-unit scale、任意角度、
   ancestor transform、resampling/CONTAIN bars/COVER crop、subpixel/rounded/stroke/vector/Text/QR/Barcode、JPEG、
   native Skia/FreeType/HarfBuzz build、process Profile registration/certification、RequestRegistry product success、
   Java/OpenAPI/Web/正式产品 route、J1/A3/READY 或外部副作用；`/prototype` 不计最终交付。

## 验证与完成信号

- Rust/Python 在首个 90° case 共同 RED 后，以独立控制流逐 pixel/PNG byte GREEN；新增 rotations、clip、alpha、
  opacity 回归并检查 JSON inventory/SHA/unique。
- 局部：focused Rust、Python independent、Cargo fmt/check/clippy `-D warnings`/tests、`py_compile`、
  `git diff --check`。
- 分级：canonical `render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`；Maven gate
  不并发。
- 最高只报 `automated_verified`；Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
  `ABSENT`、正式产品 route `CLOSED`、native stack `BUILD_NOT_AUTHORIZED`；provider/API Key/费用/真实数据=0，
  不 push/tag/PR。

## Result

- shared prepared IMAGE vectors 已升级为 `/3`。Rust primary 与 Python independent verifier 先在
  `centered-unit-square-image-quarter-turn-clockwise` 共同 RED，独立实现后冻结为 23 rendered + 3
  unsupported，共 26/26 cases、160 independent checks。vector SHA-256 为
  `a9f849611f0075e413eda281e0b40c4cec8efbae9bf607122e4d8975c0c480ec`。
- Engine 只在既有 exact 1:1 integer device box 上接受 centered origin、positive unit scale 与
  `[-360,360]` 内 exact quarter turn；以目标 pixel 的 unclipped local coordinate 执行 inverse integer mapping。
  90°、180°、270°、-90°、surface clip + partial opacity 均有 positive regression；45° replacement negative
  继续以 `IMAGE_PAINT` fail closed。
- alpha premultiply/source-over、authored preorder、ancestor clip 与 subtree opacity isolation 沿用 T109–T111
  的同一内核；未引入 resampling、trigonometry、tolerance、第二套 raster 或 caller-supplied pixels。
- focused Rust/Python、workspace fmt/check/clippy `-D warnings`/tests、`py_compile`、JSON inventory/SHA/unique 与
  `git diff --check` 全绿。

## Evidence

- canonical `render`：`.sdlc/evidence/20260825-141348-render/`（40.489 秒）。
- affected `fast`：`.sdlc/evidence/20260825-141542-fast/`（3/3 steps，11.958 秒）。
- sequential `server`：`.sdlc/evidence/20260825-141603-server/`（1084.247 秒），App 347 tests、0 failures、
  0 errors、15 skipped。
- 第一次 Goal `full` `.sdlc/evidence/20260825-143420-full/` 如实保留为失败证据：前 14 steps 均通过，
  `prototype-e2e` 中产品 chunk-recovery heading 在 5 秒内未出现。该失败随后在 isolated 1 次、exact parallel
  20 次及 full-suite 5 轮中均未复现；另 100 个 exact executions 均输出 `ok`，但诊断进程在 teardown 挂起，
  已中止且未作为正式绿证据。未做推测性代码修改。
- 重新执行的 Goal `full`：`.sdlc/evidence/20260825-150934-full/`（17/17 steps，1471.031 秒）；App
  347 tests/0 failures/0 errors/15 skipped、Node 24 Web 26 files/212 tests、runtime canary、Playwright
  23 passed + 1 controlled skip、Draft 与 inference browser journeys、inference E2E 1/1 均通过；恢复页用例
  本次 1.6 秒通过，provider attempts/API Key reads/reservations/cost/真实数据=0。
- 状态回填后的 resolution `fast`：`.sdlc/evidence/20260825-153729-fast/`（3/3 steps，11.146 秒）。
- Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、正式产品 route `CLOSED`、
  native stack `BUILD_NOT_AUTHORIZED`；`/prototype` 不计最终产品交付，未 push/tag/PR。
