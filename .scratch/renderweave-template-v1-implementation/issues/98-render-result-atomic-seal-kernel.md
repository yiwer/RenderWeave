# 实现 Renderer RESULT 原子封存与双帧 payload 内核

Type: task
Status: resolved / automated_verified
Claimed by: Codex `/root`（single-writer）
Blocked by: 22, 23, 24, 92（均已 resolved）

## Question

process contract、Java Adapter 与 shared vectors 已冻结 `RESULT_METADATA` + `RESULT_IMAGE`，Engine 也已能为一个
严格子闭包产生真实 PNG bytes；但 Rust protocol 目前只会把 caller-supplied vector payload 包成 frame，没有一个
可由后续 daemon success path 复用的原子 seal Interface。怎样补齐 exact metadata、image length/SHA-256 与 UUID
network-order 前缀的单一封存边界，同时不在 Profile 尚未完整实现和认证时打开 daemon success、测试旁路或产品 route？

## Answer（本票冻结的实施决定）

1. **深化既有 protocol deep module**：新增唯一纯函数式 `seal_result` Interface；输入是 closed
   `ResultSealInput`（request/profile/document identity、像素尺寸、PNG/JPEG output selection 与 exact image bytes），
   输出是不可变 `SealedResult` 的 metadata payload 与 image payload。函数不读 Clock、网络、文件、配置、Engine
   state、Template/Asset 身份或 registry。
2. **derived facts 不信 caller**：`byteLength` 与 raw lowercase `contentSha256` 只从 exact image bytes 计算；
   `RESULT_IMAGE` 的前 16 bytes 只从 canonical lowercase UUID v4 解码为 network order，后接 exact image bytes。
   caller 不能传 digest、length、media type、format 或 quality member shape。
3. **closed output shape**：PNG 固定 `renderweave-output-png/1.0` / `PNG` / `image/png` 且禁止 quality；JPEG 固定
   `renderweave-output-jpeg/1.0` / `JPEG` / `image/jpeg` 且 quality 必须为 1..100。共同要求 exact
   `renderweave-renderer/1.0`、`renderweave-render/1.0`、`renderweave-layout/1.0`、正 width/height/dpi 与非空 bytes。
4. **canonical 与 payload-safe**：metadata 使用 frozen member order 直接 canonical serialize；`SealedResult::Debug`
   只允许输出 requestId、format、length 与 digest，不得输出图片、payload 或完整 metadata。任何 validation/
   allocation/serialization 失败都零 payload 输出。
5. **共同语料纵向 TDD**：Rust 新测试必须从既有 `png-result-metadata` / `png-result-image` shared vectors 反向调用
   `seal_result` 并逐 byte 相等；另覆盖 UUID、identity、zero dimensions/DPI、empty image、JPEG quality 与 PNG/JPEG
   closed member shape。现有 Java/Python 对同一 7-case process corpus 的 110 checks 保持不变。
6. **诚实接线边界**：本票不修改 process manifest、HELLO profile 集、daemon command handler/registry、Engine
   raster、resource fetch/decode、Profile registration、Java/OpenAPI/Web/E6 或产品 route。`seal_result` 是真实可执行
   payload 内核，但 daemon 仍不调用它；Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster
   `ABSENT`、daemon output `UNWIRED`、product route `CLOSED`。

## 验证与完成信号

- TDD RED：先增加 public-interface tests，因 `ResultSealInput` / `seal_result` 不存在而编译失败；记录 exact RED。
- TDD GREEN：focused protocol tests → workspace fmt/check/clippy/tests → Python process replay、JSON/SHA/inventory 与
  `git diff --check`。
- 分级：`render` → affected `fast` → sequential `server` → Goal `full` → resolution `fast`。
- 最高状态只到 `automated_verified`；provider attempts/API Key reads/付费调用/真实数据保持 0；不 push/tag/PR，
  不开放产品 route，不宣称 Template/Renderer READY。

## Resolution（2026-08-24）

- TDD RED 已捕获：public-interface tests 因 `ResultSealInput`、`ResultOutputSelection`、`seal_result` 与
  `SealedResult` 尚不存在而编译失败。GREEN 后 focused protocol 3/3，Rust workspace fmt/check/clippy/tests 全绿，
  protocol 累计 10 tests。
- `seal_result` 已成为唯一纯封存边界：从 owned exact image bytes 派生 frozen canonical metadata、raw lowercase
  SHA-256、byteLength 与 `UUID network bytes || image bytes`；PNG/JPEG member shape、identity、尺寸/DPI、空 payload
  与 JPEG quality bounds 均由同一入口验证。`SealedResult::Debug` 不泄漏 payload 或完整 metadata。
- Rust semantic replay 直接消费既有 `png-result-metadata` / `png-result-image` shared vectors 并逐 byte 相等；既有
  Java 26/0/0/0 与独立 Python 7 cases/110 checks 继续通过。render gate 冻结边界为
  `CANONICAL_METADATA_LENGTH_SHA256_UUID_IMAGE_PAYLOAD_AUTOMATED_VERIFIED_UNWIRED`。
- 分级证据均 exit 0：`render` `.sdlc/evidence/20260824-190140-render/`、affected `fast`
  `.sdlc/evidence/20260824-190221-fast/`、顺序 `server` `.sdlc/evidence/20260824-190237-server/`、17-step `full`
  `.sdlc/evidence/20260824-191952-full/`，以及状态回填后的 resolution `fast`
  `.sdlc/evidence/20260824-194702-fast/`。full 用时 1543.248 秒；App 344/0/0/15、Node 24 Web 26 files/
  212 tests、runtime canary、Playwright 23 passed + 1 controlled skip、Draft journey 与 inference replay E2E 1/1
  均通过。
- 诚实边界保持 Profile `NOT_REGISTERED`、certification `NOT_CERTIFIED`、process raster `ABSENT`、daemon output
  `UNWIRED`、product route `CLOSED`；provider attempts/API Key reads/付费调用/真实数据均为 0，visual diff J0，未
  push/tag/PR，未把 `/prototype` 当作最终产品交付。
