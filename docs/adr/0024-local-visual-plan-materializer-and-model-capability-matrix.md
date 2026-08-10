# ADR-0024：本地视觉计划物化与逐模型能力矩阵

- 状态：Accepted
- 日期：2026-08-10
- 关联：AC-VR-003、AC-VR-008、P6/T6-5 N3、ADR-0020、ADR-0022、ADR-0023

## 背景与约束

N2 在同一 12-case sentinel 上证明：三个 Product v4 Profile 都能产出部分 element、entity、relationship
与 binding，但生成式 STRUCTURE 会再次改写已经验证的计划，最终 Candidate pass 均为 0/12。该调用既没有
新的事实来源，又引入拓扑丢失、证据错配、格式失败、超时和额外费用。

同时，`qwen3.7-flash`、`qwen3.7-plus` 与 `qwen3.8-max` 不能继续只靠一个通用请求模板表达能力。
Plus/Flash 有当前官方视觉与结构化输出文档；精确 `qwen3.8-max` 别名只在 N2 live canary 中得到过返回
模型名完全一致的运行事实，不能把其他 Max 型号的公开上限移植给它。

历史 pipeline 1..3、Product v1..v4 Profile 资源与既有 run snapshot 必须保持可读和不可修改；N3 本身不
开放任何 live ledger，也不能让尚未过质量门的 vNext Profile 出现在产品选择器。

## 决策

1. **新增 pipeline 4，但只替换编译阶段。** IMAGE_ONLY 仍按 OBSERVE → HIERARCHY → ELEMENT_BINDING
   三次调用取得严格中间合同；STRUCTURE 由 Java 在本地完成，Provider attempts 与 reservations 均为 0。
   pipeline 4 的 STRUCTURE/REPAIR 若进入通用 Provider 调用入口，必须 fail-closed。
2. **validated plan 是唯一物化输入。** materializer 先重新检查 inventory、hierarchy、binding 的完整性与
   相互一致性，再生成全部 Schema、field、ONE/MANY reference、array 和 image evidence。它不接受模型
   Candidate UUID，也不增加计划外字段、关系、required 或 constraint。
3. **输出是规范化且可重放的。** Schema 按根开始、以 relationship fieldKey 排序的 BFS 输出；field 与
   evidence 使用固定排序和去重；Candidate 局部 UUID 由 runId + SchemaKey/FieldKey 语义路径生成。等价计划
   的列表顺序变化不会改变 Candidate JSON 字节。
4. **不伪造置信度。** 当前视觉阶段没有经校准的逐项置信度合同。所有本地物化的 AI Schema/field 均使用
   `source=AI`、`inferred=true`、`required=false`、`resolution=UNRESOLVED`，confidence 固定为当前人工
   阈值减一。结果必须进入逐项人工审核，不能以“代码生成”冒充“图片事实已确认”。
5. **pipeline 4 不做生成式 repair。** local materializer 的输出若违反 visual plan 或 Candidate validator，
   以稳定 `LIVE_LOCAL_MATERIALIZER_INVALID` 失败；语义不确定项进入 REVIEW_REQUIRED。节省的调用预算留给
   后续只读 semantic verifier 或定位明确的早期阶段 repair。
6. **能力事实与运行 Profile 分开冻结并一一配对。** 新增三个严格 JSON capability 资源，启动时拒绝
   duplicate、unknown、trailing、scalar coercion 与数值越界，并要求 exact model、vision、non-streaming、
   JSON Object、可关闭 thinking、禁 tools/remote media。每个 v5 Profile 必须与同 model capability 匹配。
7. **产品发送上界必须可兑现。** 当前共同上界固定为 10 张图片、最长边 4096、每图最多 16,000,000
   像素、每阶段最多 8192 output tokens、240 秒。图片归一化同时执行边长与像素门；capability 启动校验直接
   对照 `InputNormalizer`/`ImageNormalizer` 常量，避免文档声明与真实请求漂移。Plus/Flash 的产品上界不得
   超过官方 advertised 上界。
8. **精确 Max 能力不外推。** `qwen3.8-max` 标记为 `N2_EXACT_ALIAS_LIVE_ONLY`，advertised image/output
   上限保持 null；只记录精确别名 canary 与运行成功的事实。任何模型响应名不等于 Profile model，沿用现有
   `DASHSCOPE_MODEL_MISMATCH` fail-closed，不做别名降级。
9. **v5 暂不进入产品目录。** 三个 Profile 均为 `EXPERIMENTAL` 且只由 `visualNextProfiles()` 暴露给评测
   控制面；`productLiveProfiles()`、OpenAPI 与 Web 仍保持四个 v4 选择。只有 N5/N7 的消融与最终 policy
   通过后，才以新的独立提交修改产品目录。
10. **兼容字段不代表一次模型调用。** Profile 1.0 仍要求 `promptVersion`，因此 v5 保存 Candidate Prompt 5
    作为 snapshot 兼容信息；pipeline 4 不在 STRUCTURE 或 REPAIR 使用它。真正发送给 Provider 的只有三个
    immutable visual Prompt。现有 checkpoint v2 已完整保存三份 validated plan 与 Candidate，无需数据库
    migration 或历史 checkpoint 重写。

## 备选方案

| 方案 | 优点 | 未选择原因 |
|---|---|---|
| 保留生成式 STRUCTURE，只改 Prompt | 改动最小 | N2 已证明会丢失/发明计划语义，且多一次费用与超时 |
| materializer 自动把高分项标为已确认 | 审核工作更少 | 当前视觉合同没有可信的逐项校准分数，会制造错误安全感 |
| 直接用 v5 替换产品 v4 | 用户可立即体验 | N3 只证明合同和恢复，不证明视觉质量达到发布门 |
| 三模型共享一个 capability | 文件更少 | 会掩盖 Max 精确别名的文档缺口和模型间边界差异 |
| 把 capability 字段追加到历史 Profile 1.0 | run snapshot 自包含 | 会改变所有历史 canonical snapshot；本节点先以不可变资源配对，并由后续 evaluation identity 同时绑定两者 |

## 后果与验证

- 正向：正常路径从四次模型调用降为三次；Candidate 拓扑、ID、数组与 evidence 不再被最后一次生成调用
  改写；逐模型能力假设可定位、可严格加载且不静默回退。
- 代价：所有物化项暂时都要人工处置；v5 仍不可从产品 API 创建；capability 与 Profile 是两个必须同时
  纳入后续 evaluation identity 的不可变输入。
- 验证：byte golden 与列表排列 property；Candidate/visual-plan 双 validator；三个 Profile/capability
  strict loader；图片 4096² → 4000² 像素边界；真实 PostgreSQL 三次 attempt/reservation；STRUCTURE 租约
  过期恢复后零重复 Provider；server/contract gate。N3 全部测试必须清 live gate，Provider attempts=0。
- 回退：删除 v5 注册项即可停止新实验；历史 v1..v4 与既有 run 不受影响。已排队的 v5 run 按其不可变
  Profile snapshot 和既有 checkpoint 恢复；本节点没有数据库 migration 或不可逆外部副作用。
