# ADR-0030：v40 工程目录与 capability-aware admission

- 状态：Accepted
- 日期：2026-08-12
- 关联：AC-020、AC-021、AC-022、AC-VR-003、AC-VR-005、AC-VR-008、AC-VR-009、AC-VR-010、ADR-0024、ADR-0028

## 背景

product-v40 已具备 pipeline 4.27 的确定性 Candidate materializer、阶段 checkpoint、bounded verifier、
payload-free telemetry、审核与 Apply E2E，但 Flash 单 case live 的五次 attempt 均在 OBSERVE fail-closed，
没有形成真实识别质量证明。与此同时，普通产品入口仍固定在 product-v4；该路径会再次调用模型执行
STRUCTURE，因而保留了“上游 10 个字段 key 全部被改写、repair 无改善”的已知失败路径。

用户明确要求切换到 v40，并指出复杂站牌不应以 Flash 能力作为流水线可用性的唯一判断。v40 还精确绑定
本地 Document Vision capability；原入口只检查 worker、上传门与 Provider 配置，导致 capability 缺失时页面
仍显示可用、run 排队后才失败。

## 决策

1. 新 live run 的产品目录只包含三份 product-v40 Profile，顺序为 Plus、Max、通用 Flash。
   三者都只支持 `IMAGE_ONLY`，继续标记 `EXPERIMENTAL`；历史 product-v1..v4 资源和 run snapshot 保持可读、
   可恢复、不可修改，但不再接纳为新产品 run。
2. Web 默认选择 Plus，Max 明确面向高难度嵌套结构，Flash 明确标为低成本 smoke 且不推荐复杂站牌。这个顺序
   是工程能力路由，不是质量认证或自动模型升级；切换模型仍由用户显式完成。
3. pipeline 4.27、Prompt 10 和所有已发布 Profile 保持逐字节不可变。产品目录、OpenAPI、Web 默认值与
   admission 只能引用精确 immutable Profile；后续若改变模型合同或 bounded normalization，必须创建新版本。
4. `live-availability` 对每个 Profile 返回 payload-free `available` 与 nullable fixed
   `unavailabilityCode`。创建 run 和 live retry 都必须在持久化 run、reservation 或 Provider 调用前验证启动时
   探测到的 Document Vision capability 与 Profile 绑定 ID 精确相等；缺失或不匹配以既有
   `DOCUMENT_VISION_*` code 返回 503。
5. product-v40 的 STRUCTURE 与 REPAIR 继续禁止 Provider 调用。集成合同要求成功路径只有 OBSERVE、
   HIERARCHY、ELEMENT_BINDING 三次 reservation，随后由本地 materializer 生成 Candidate；测试 Provider 若收到
   STRUCTURE/REPAIR 直接失败。
6. 不实现跨模型自动 fallback。Flash 失败后若要使用 Plus/Max，必须由用户发起新的可审计 run，并重新应用
   Profile snapshot、外发确认和成本边界；不能在同一 run 内静默改变模型、身份或费用账本。
7. 该切换只声明“工程试用入口可用”：可启动、可在缺能力时提前拒绝、可恢复、可审计、可人工审核。
   product-v40 仍为 `EXPERIMENTAL`，N6 仍为 `automated_verified`，N7/Goal 仍为 `in_progress`；不声称生产
   可靠性、站牌识别成功率或 AC-021/AC-VR-010 质量晋级。

### 2026-08-12 Flash 精确模型补充决策

用户将产品 Flash 精确模型改为 `qwen3.7-flash`，Plus/Max 保持不变。新增
`dashscope-qwen37-flash-product-v40-hybrid-generic` 作为 additive successor，并从新建目录移除 dated Flash；
不得修改 `dashscope-qwen37-flash-20260715-product-v40-hybrid-generic` 或把其 live 证据继承给 successor。
两个 Flash alias 继续共享既有稳定预算槽位，因此该切换不重置 cap/consumption，也不隐式打开 ledger。
任何 successor live 都必须以新的 evaluation identity 与 Profile snapshot 重新通过 J1、时限、额度、进程和
lease preflight。本补充只改变产品选择器身份，不改变 pipeline 4.27 的三阶段 Provider 合同和确定性物化。

## 备选方案

| 方案 | 未采用原因 |
|---|---|
| 继续让 v4 作为产品入口 | 保留生成式 STRUCTURE 字段身份改写的已知缺陷 |
| 默认 Flash 并放宽 verifier | 把模型能力不足伪装成成功，会损害层级、归属和审计可信度 |
| Flash 失败后自动切 Plus/Max | 静默改变模型、成本和 evaluation identity，破坏一次 run 的不可变审计边界 |
| 直接修改 v40 Profile 或 pipeline 4.27 | 会改变既有 snapshot 与 live evidence 的历史含义 |
| capability 缺失后仍先创建 run | 可恢复但制造可预防的失败任务，降低入口可用性并误导用户 |

## 后果与验证

- `f47c54a` 完成 v40 目录、OpenAPI/generated client、IMAGE_ONLY/Plus 默认与真实 PostgreSQL 合成路径；
  成功 run 为 3 次 Provider reservation，STRUCTURE/REPAIR 为零。
- `f27f86a` 完成 exact capability readiness、创建/retry 前置拒绝及 Web 行为；缺失和 mismatch 都只暴露
  固定 code，0 run/0 reservation/0 Provider。
- `67d46c5` 新增通用 Flash immutable successor，并同步 Registry、API/OpenAPI、generated client 与 Web
  选择器；dated Flash 仍可读取和恢复，但不再允许创建新产品 run。本节点 Provider=0。
- `ba409e9` 的隔离 clean full evidence `20260812-015332-full` 为 9/9 PASS；metadata 精确绑定该 revision，
  `workingTreeDirty=false`，离线汇总的 Provider attempts/reservations 均为 0。
- `6906be1` 的隔离 clean full evidence `20260812-012644-full` 为 9/9 PASS，包含正式 Node 24、真实
  PostgreSQL、runtime canary、独立 evidence verifier 与浏览器审核/Apply；clean Document Vision evidence
  `20260812-013158-document-vision` 为 1/1 PASS、19 lines。两份 metadata 都绑定该 revision 且
  `workingTreeDirty=false`。
- Flash 仍可被用户显式选择用于低成本诊断；其失败继续由 stage-local fixed code、checkpoint 与人工 retry
  处置，不通过放宽 enum、parent、overlap、reading order 或 semantic verifier 获得虚假 Candidate。
- 回退只需以新提交更换产品目录；已创建 run 永远按保存的 Profile snapshot 恢复。本决策没有数据库迁移、
  真实数据或付费模型调用。
