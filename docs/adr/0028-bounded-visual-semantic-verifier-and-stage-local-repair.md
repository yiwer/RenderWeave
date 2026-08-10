# ADR-0028：有界视觉语义验证器与最早阶段修复

- 状态：accepted
- 日期：2026-08-11
- 关联：AC-VR-007、AC-VR-009、P6/T6-5 N6、ADR-0022、ADR-0027

## 背景与约束

v8 解决了 OBSERVE 的 JSON array 形状后，v9 在仓库合成 `transit-board-v3` 上稳定通过 OBSERVE，但四次
HIERARCHY 都因 `VISUAL_HIERARCHY_V2_SUPPORT_NOT_GROUP` 或
`VISUAL_HIERARCHY_V2_SUPPORT_ELEMENT_UNKNOWN` 被拒绝。payload-free stage evidence 显示 OBSERVE 实际产出
18 个 SLOT、0 个 GROUP，而 gold 为 10 个 SLOT、3 个 GROUP。此时继续重试 HIERARCHY 无法补回上游遗漏的
GROUP；增加调用次数只会让下游引用不存在或错误类型的元素。

已有严格 record/拓扑 validator 能证明“输出结构合法”，却不能证明 `REPEATED_GROUP` 已被抽象成可形成子
Schema 的 GROUP element。若直接在 materializer 中猜测或补建 GROUP，会把服务端推断伪装成模型证据，也无法
区分真实重复容器与装饰性布局。历史 Profile、Prompt 和 evidence 必须保持不可变。

## 决策

1. 新增版本化 `renderweave-visual-semantic-verifier/1.0`。它只消费已通过严格解码和空间不变量的本地 plan，
   输出有限白名单 issue code 与 `earliestStage`，不保存模型文本、异常 message、动态 id 或原始坐标。
2. OBSERVE 边界先执行四项有界检查：每个 `REPEATED_GROUP` 有 MANY/GROUP element；GROUP 只拥有 GROUP 或
   REPEATED_GROUP region；每个 ITEM 至少有一个 SLOT；GROUP cardinality 与重复 region 一致。失败不写入
   checkpoint，只记录固定 taxonomy，并在 OBSERVE 原地重试。
3. HIERARCHY 边界要求每个 GROUP 恰好支撑一条 relationship、每条 relationship 恰好由一个 GROUP 支撑，
   且 relationship region 与该 GROUP 的 owned region 一致；ELEMENT_BINDING 要求字段绑定到能够覆盖其区域的
   最近实体。两类失败分别路由到 HIERARCHY 与 ELEMENT_BINDING，不回退重做已验证的 OBSERVE。
4. 重试请求继续使用既有 `retryProblemCodes`，只重做最早失败阶段；已成功的更早 checkpoint 保留。问题码按
   stage 有界累积、去重、排序且最多 16 个，避免后一次修复遗忘前一次约束。selected crop 只从已验证
   grounding checkpoint 派生，最多 4 个，不把派生图片或局部 ID 持久化。
5. 新增 immutable v10/v11/v12 Prompt/Profile：v10 固化 OBSERVE 语义检查，v11 增加 hierarchy/binding verifier、
   stage-local crop 和更细 JSON/ownership taxonomy，v12 增加 bounded retry union。历史 v8..v11 的资源与
   snapshot 不改写；三模型 Profile 均保持隐藏。
6. 监控与审核共用 payload-free execution telemetry，只展示阶段/checkpoint、受控区域类别、固定 issue code、
   token/费用/延迟和恢复状态。它不展示动态 region/entity/element ID、原始坐标、OCR、图片、Prompt、
   Provider response 或 chain-of-thought；1024 宽度使用既有响应式布局与 drawer 合同。
7. verifier 只拒绝，绝不自动增加、改名、改类型或重连模型 plan。连续失败、预算用尽或无新假设仍 fail-closed；
   Profile 在完整 stage/eval 门槛前保持 `EXPERIMENTAL` 且不进入产品选择器。

## 备选方案

| 方案 | 优点 | 风险 | 结论 |
|---|---|---|---|
| 只强化 HIERARCHY prompt | 改动小 | 上游没有 GROUP 时不可解，已连续四次实证 | 不采用 |
| materializer 自动把重复 region 变成 GROUP | 零额外调用 | 伪造语义证据、掩盖模型遗漏 | 不采用 |
| 下游失败后整条 pipeline 重跑 | 可能碰巧成功 | 重复费用、丢失已验证阶段、难恢复 | 不采用 |
| 最早阶段 verifier + stage-local retry | 定位清晰、预算可控、可恢复 | 需为每阶段维护明确 invariant | 采用 |

## N6 实施与受控实证

- `4290227` 完成 observation/hierarchy/binding bounded verifier、earliest-stage retry、已成功 checkpoint 保留、
  selected crop 与 PostgreSQL crash/retry/cancel 回归；`f0ebe77` 完成 owner/slot 级固定 taxonomy 和最多 16 个
  stage-local retry code union；`d5afadf` 完成监控/审核 telemetry、1024 keyboard/axe 与 real-PG E2E。
- Flash 对同一个仓库合成 `transit-board-v3` 依次执行 v10、v11、v12 单 case smoke。三次均遵守
  PROPOSED → 负探针 → OPEN → CLOSED，分别 5 attempts / 32,086 tokens、5 / 32,897、5 / 36,267；独立
  verifier 均 PASS、0 abandoned、payload scan PASS。
- 三次 smoke 的 15 个 attempts 全部停在 OBSERVE。v12 已把错误从 enum 推进到 parent-kind/cardinality，但
  最后仍以 `VISUAL_GROUNDING_PARENT_KIND_INVALID` 结束，没有触达 HIERARCHY/BINDING。A2 只证明授权、预算、
  taxonomy 和 evidence 闭环，不证明三阶段模型质量。
- Flash Goal 累计 71 attempts、393,034/500,000 exposed tokens、¥0.169035；Plus 保持
  485,886/500,000 且不再调用，Max 保持 428,816/500,000。三阶段入口门未满足，因此没有调用 Max；三份
  ledger 终态均为 `CLOSED`。
- exact-clean `de97131` full gate A1 全绿，证据为 `.sdlc/evidence/20260811-020246-full`；不存在 A3，最终
  业务/视觉 J1 与 N7 final eval 仍未满足。

## 后果与验证

- 正向：扁平化在 OBSERVE 即被捕获；失败码直接指出修复阶段；HIERARCHY 不再承担补造上游元素的职责；
  操作员可以在不接触载荷的情况下读到阶段、区域类别、问题、费用和恢复状态。
- 代价：更严格的语义门可能降低一次通过率；Prompt 增加少量输入 token；通用视觉中的装饰性重复需要 corpus
  负例防止过度建模。
- 验证：missing GROUP、cardinality、empty ITEM、invalid GROUP ownership 单元负例；同阶段重试与上游
  checkpoint 保留的 PostgreSQL 回归；payload-free taxonomy；v10 小 canary 后独立 evidence 重算。
- 回退：停止创建 v10 run 即可；历史 v8/v9 snapshot 继续可读。若某类重复被证实为非数据语义，应以新的
  verifier/prompt version 和 corpus gold 调整，不放宽既有版本。

## N7 hierarchy 结构修复增量

Plus v12 的单 case reachability 首次通过 OBSERVE 后暴露两个新事实：`VISUAL_HIERARCHY_V2_ENTITY_INVALID`
把 entity ID、schema key、display name、support list 四类确定性失败压成一个码；同时既有 crop selector 对
所有 `VISUAL_HIERARCHY*` 重试都附加 GROUP crop，即使错误只需修 JSON 结构。随后一次 Provider 400 没有
可安全归因的响应载荷，因此不把它宣称为 crop 导致，只消除本地可证明的不必要请求扩张。

`98ba3d0` 将 entity/relationship 字段失败拆成固定 payload-free taxonomy；结构错误只携带已验证 checkpoint
与精确问题码，不再追加 crop，只有 `VISUAL_SEMANTIC_HIERARCHY_*` / `VISUAL_SEMANTIC_BINDING_*` 才派生
视觉 crop。immutable hierarchy prompt v5 与三模型 product-v14 Profile 固化相同修复合同，仍全部隐藏且
`EXPERIMENTAL`。单元、worker/real-PG 集成、exact-clean server 与 fast A1 已通过；该增量没有 Provider
调用，也不改变 Max 的三阶段入口门。
