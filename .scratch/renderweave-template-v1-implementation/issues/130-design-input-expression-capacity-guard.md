# 物化 Design/Input/Expression 共享容量 module 与首批产品路径

Type: task
Status: automated_verified
Claimed by: Codex `/root`
Blocked by: 129（已 resolved）

## Question

T129 已让 bootstrap ordinal 1–2 可执行；ordinal 3 `EXEC::DESIGN_INPUT_EXPRESSION::1.0` 虽有 65 axes、195 个
静态 fixture 与双重 fixture replay，却明确没有 exact product target、required executor manifests 或产品执行证据。
当前 DesignDSL parser/canonical、语义计数、RenderInput、problem budget 与 Expression 执行分别散布在 Template/
Rendering 中，部分阈值已局部实现、部分尚未实现。如何建立唯一容量事实源并开始真实接线，同时避免 65 个浅方法、
重复 guard、一次性大爆炸改造，或把标量 fixture replay 冒充完整 execution class？

## Answer（本票冻结的实施决定）

1. **一个深 module、一个小 Interface**：由 `renderweave-template` 拥有
   `DesignInputExpressionCapacityAuthority`，Interface 只接受 closed `{limitId, observedValue}` observation 并返回
   closed accepted/rejected/invalid decision；65 个 value encoding、comparator、limit、terminal stage/code、zero boundary
   与 downstream effects 全部隐藏在唯一 canonical implementation。删除该 module 会让规则重新散落到多个调用者，
   因而该 seam 具有真实 leverage/locality。
2. **进程内依赖，不造 port**：容量判断是纯确定性计算，不访问 I/O、时钟、数据库或网络；不为它创建 SPI、远程 Adapter
   或可替换配置。Template 通过 `TemplateModule` 组装唯一实现；Rendering 在后续票沿既有单向 compile edge 消费同一
   Interface。测试只从该 Interface 观察结果，不读取 implementation 内部 map。
3. **完整规则 profile，首批只接 9 轴**：T130 物化全部 65 axes 的冻结 product profile 与 fail-closed evaluator，使
   195 个 scalar/token fixture 可经真实 canonical implementation 执行；但产品 reservation-point 接线只覆盖
   `designDslParser` 的 raw/canonical bytes、depth、object members、array items、total values/containers、string/member-name
   UTF-8 bytes 与 number-token bytes 九轴。原有 `DesignDslAuthority` closed wire 不扩张，错误 code/stage/limit 保持不变。
4. **双语言 component replay，不冒充 class executor**：Java primary 直接调用产品 Interface；TypeScript independent
   replayer 从 frozen coverage 独立解释 encoding/comparator/terminal，不导入 Java profile/helper，也不读取
   `plannedAssertions`/Oracle。两者必须 195/195，但 target 明确 `wiredProductAxisCount=9`、remaining=56、
   `preissuanceReady=false`、`recordIssuanceAllowed=false`，本票不签发 class required executor manifests。
5. **TDD 与接线证明**：先以 missing Interface/profile/executor 捕获 RED；GREEN 后用 recording authority 证明
   `StrictJsonParser` 与 canonical counting sink 在真实 derived observed value 上调用同一 Interface，并保持既有 211 个
   DesignDSL exact vectors byte-identical。生成报告不得分配 at-limit 16 MiB/百万节点 payload；大边界只走标量 executor，
   产品路径用小型 override/recording proof，不以 fixture 旁路冒充 admission。
6. **门控与诚实边界**：focused Java/TypeScript → `template`/`template-static` → `fast` → 顺序 `server` → Goal `full` →
   resolution `fast`；Maven 串行、精确 staging。formal registry 保持 58/58，Domain Services 状态不回退；不改 API/
   OpenAPI/migration/Web 页面语义，不发行 195 records，不注册/认证 Profile，不运行独立 native build/provider/真实数据/
   生产/J1/A3/READY，360 项用户 dirty work 与备份 stash 保持原样。

## Results

- 产品实现 revision `3c583a4b01666a3122d1d11973f39cdcccb8eff5` 新增 Template-owned
  `DesignInputExpressionCapacityAuthority` closed Interface 与唯一 canonical implementation；65/65 冻结轴的 value
  encoding、比较器、上下限、terminal code/stage、zero boundary 与 downstream effects 均由该深 module 统一拥有。
- `StrictJsonParser` 的 raw bytes、depth、object members、array items、total values/containers、string/member-name UTF-8
  bytes、number-token bytes，以及 `CanonicalJsonWriter` 的 canonical bytes counting sink 共九个真实 reservation point
  已接到同一 authority。recording proof 验证调用的是产品派生观察值；既有 DesignDSL Java/Python canonical kernel 仍为
  211/211 byte-identical，Template module 回归为 88 tests 全绿。
- target revision `bafc2a23aaa750142df281625565732cc724d5f3` 冻结 component target；target 7762 bytes、SHA-256
  `049e78fb920d8317d95579b96cddca18de424811859b388e55cba1afbbe0432f`，并绑定实现 revision 与 source/fixture
  identities。Java primary 报告 139129 bytes、SHA-256
  `6122cabe4b0cf917cd078ff7d24f0d26668edb7c367980073600fc0c259fab94`。
- component gate `.sdlc/evidence/20260826-144454-design-input-expression-capacity/` 中 Java primary 与独立
  TypeScript replayer 均为 65 axes、195/195 cases（125 accepted、70 rejected）；独立 replay 为 2600 checks，观察
  digest `e760c63ae7a2cf364adc33dc366150e31c5e03eb70c0348e4c2d82c8de304796`。两份报告均明确
  `wiredProductAxisCount=9`、remaining=56、`preissuanceReady=false`、`recordIssuanceAllowed=false`、
  `executionClassExecutable=false`。
- 受影响 `template` `.sdlc/evidence/20260826-144600-template/`、`fast`
  `.sdlc/evidence/20260826-144653-fast/` 与顺序 `server`
  `.sdlc/evidence/20260826-151325-server/` 全绿。首次 server 证据
  `.sdlc/evidence/20260826-144741-server/` 仅在用户既有未跟踪 AF_UNIX Document Vision runner 用例出现低频
  timeout；隔离重放曾复现 1/20，未改动该用户-owned 路径，完整 server 重跑及下述 `full` 内同一 4/4 用例均通过。
- 发布级 `full` `.sdlc/evidence/20260826-152600-full/` 在 exact target revision 上 17/17 steps、1669.266 秒
  通过，覆盖完整 Maven reactor、Node 24 Web、runtime/R0/R1/P0、正式 Template 产品浏览器旅程（25 passed +
  1 controlled skip；另有 inference replay 1/1）。formal registry 保持 58 Case / 58 Oracle，SPEC Registry
  仍为 393 artifacts、Node/Python 22974/22882 checks，provider attempts/API Key reads/reservations/cost 均为 0。
- 用户既有 360 项 dirty work 继续保持指纹
  `4bddd1c955a4b4f55d984f3febd551742dd6f63c`，备份 stash
  `f3c29199ec510ec3f809b3f8263f5d2806cb0740` 未变。本票未签发两个 class required executor manifests，未发行
  195 条 Design/Input/Expression records，未更改中央 executable 状态、API/OpenAPI/migration/Web 页面语义，也未
  注册/认证 Profile、运行独立 native build/provider/真实数据/生产或取得 J1/A3/READY；仓库 `full` 内既有 Rust
  checks 不改变 `BUILD_NOT_AUTHORIZED`。
