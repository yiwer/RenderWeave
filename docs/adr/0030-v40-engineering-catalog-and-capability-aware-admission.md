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

1. 新 live run 的产品目录只包含三份冻结的 product-v40 Profile，顺序为 Plus、Max、Flash pinned snapshot。
   三者都只支持 `IMAGE_ONLY`，继续标记 `EXPERIMENTAL`；历史 product-v1..v4 资源和 run snapshot 保持可读、
   可恢复、不可修改，但不再接纳为新产品 run。
2. Web 默认选择 Plus，Max 明确面向高难度嵌套结构，Flash 明确标为低成本 smoke 且不推荐复杂站牌。这个顺序
   是工程能力路由，不是质量认证或自动模型升级；切换模型仍由用户显式完成。
3. pipeline 4.27、Prompt 10 和三份 v40 Profile 保持逐字节不可变。本次只改变产品目录、OpenAPI、Web 默认值
   与 admission，不在旧 Profile 身后热修算法。后续若改变模型合同或 bounded normalization，必须创建新版本。
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
- Flash 仍可被用户显式选择用于低成本诊断；其失败继续由 stage-local fixed code、checkpoint 与人工 retry
  处置，不通过放宽 enum、parent、overlap、reading order 或 semantic verifier 获得虚假 Candidate。
- 回退只需以新提交更换产品目录；已创建 run 永远按保存的 Profile snapshot 恢复。本决策没有数据库迁移、
  真实数据或付费模型调用。
