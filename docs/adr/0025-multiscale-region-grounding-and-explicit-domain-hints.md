# ADR-0025：多尺度 Region Grounding 与显式领域 Hint Pack

- 状态：Accepted
- 日期：2026-08-10
- 关联：AC-VR-004、AC-VR-006、P6/T6-5 N4、ADR-0020、ADR-0022、ADR-0024

## 背景与约束

pipeline 4 已消除生成式 Candidate 编译，但 v1 视觉合同仍让模型直接在整张规范化图片上输出 element、
hierarchy 与 binding。它没有可验证的 region forest，也不能说明重复项目、父子容器和字段归属是否与图片
空间关系一致；原 Prompt 还把站牌术语写入所有图片，使菜单、价签、表单等通用场景带有领域先验偏置。

复杂长图需要 overview 保留整体关系，也需要 tile/crop 保留小字。Provider 只接受图片序列而不理解本地
artifact 坐标，因此模型输出的局部 view bbox 必须在信任边界内映射回原规范化 artifact 的 0..10000
坐标。view 图片、view id 和转换过程不能泄漏进 Candidate evidence 或长期 checkpoint。

历史 Prompt/Profile/pipeline/checkpoint 必须继续读取；N4 不开放 live ledger，不把未经真实质量评测的新
Profile 暴露给产品创建 API。

## 决策

1. **新增 pipeline 4.1 与只读 v6 实验 Profile。** 每个 Flash/Plus/Max 模型各有 GENERIC 与
   TRANSIT_BOARD 两份 Profile identity，共六份不可变资源。它们仍执行三次 Provider stage，STRUCTURE
   继续本地物化且零 Provider；产品目录仍固定为 v4。
2. **多尺度 view 由本地确定性规划。** 每个 source 先生成最长边 768 的 overview，再按需要加入显式
   targeted crop，剩余额度以 source round-robin 加入最长边 1400 的 tile。单次最多 10 views、总编码
   字节最多 30 MiB；overview 是必需项。所有 view 仅在内存生成，使用确定性 PNG 和内容 SHA-256。
3. **转换必须可逆且代码可证伪。** task v4 将有序 `viewCatalog` 与每个 view 的 source artifact、source
   ordinal、原图 bbox、尺寸和 kind 一并发送。模型只能返回 `viewId + view-relative bbox`；decoder 先验证
   0..10000 边界，再用整数有理数的 floor/ceil 变换映射到原 artifact。未知 view、越界或退化 bbox 直接
   以稳定合同错误拒绝，禁止通过 clamp 洗白非法输入。
4. **Provider 费用按实际 view 像素预留。** `ProviderImage` 新增可选的已验证宽高。新 view 按官方
   `ceil(width*height/1024)+2` 视觉 token 公式进入不可逆调用前的费用上界；历史无尺寸合成请求仍保留
   4096² 的保守上界。任何一条路径都不能在调用后才发现低预留。
5. **visual grounding/2.0 先观察 region forest，再抽象 element。** 每个 artifact 恰有一个全图 ROOT；
   SECTION/GROUP、REPEATED_GROUP 与 ITEM 表达空间层级、multiplicity、readingOrder 与 repeatGroupId。
   element 明确拥有 1..8 个 region，直接 evidence 必须落在其拥有区域内。
6. **空间不变量在服务端重新计算。** decoder 拒绝重复/未知/尾随/coercion JSON，并检查：父级存在、全图
   可达、无环、深度不超过 16、子 bbox 被父 bbox 包含、同级不重叠、readingOrder 连续且符合 top-left
   规范序、重复组只含同 identity ITEM、每个 element 完整且唯一归属。多图 ROOT 顺序必须与 source
   artifact 顺序一致。
7. **hierarchy/2.0 增加实体/关系的 region ownership。** 根实体拥有全部 ROOT；关系 region 必须位于
   parent ownership 内并包含 child ownership；MANY 关系必须落在 REPEATED_GROUP。bindings/2.0 只允许
   SLOT 绑定到包含其 element region 的实体。旧 hierarchy/bindings v1 仍由历史 pipeline 原样读取。
8. **checkpoint 升级为 3.0，view 不持久化。** checkpoint 新增 canonical `groundingPlan` 与
   `entityRegionPlan`，只保存原 artifact evidence。1.0 与 2.0 在内存迁移并只写 3.0；stage crash/retry
   从原 blob 重新确定性生成 view。Task 升级为 4.0 并绑定 view plan 与 hint identity。
9. **通用 Core Prompt 与领域 Hint 分离。** 三个 v2 stage core 只描述 JSON、空间、重复、实体和字段
   规则，不包含 bus/station/route/stop/fare 或公交/站牌/线路/站点/温馨词表。GENERIC pack 明确不引入
   领域拓扑；TRANSIT_BOARD pack 仅提供可验证的搜索提示，不能覆盖 evidence、region 或 JSON 合同。
   Profile 必须显式绑定一个 pack，运行中不能自动猜测或静默切换。
10. **N4 只建立可证伪的感知合同，不宣称质量达标。** OCR/layout、语义 verifier、targeted repair、
    Profile 默认选择和真实模型质量分别留给 N5–N7。六个 v6 Profile 均保持 `EXPERIMENTAL`。

## 备选方案

| 方案 | 优点 | 未选择原因 |
|---|---|---|
| 只提高原图分辨率 | 实现简单 | 小字与全局层级竞争同一图像预算，仍无局部坐标转换与 region 合同 |
| 模型直接返回原图坐标 | task 更短 | tile/crop 中模型无法可靠反推原图坐标，容易产生看似合法的错位 evidence |
| 持久化所有 view 图片 | 恢复直接 | 放大 Blob/隐私面，违反中间视觉载荷不长期保存的边界 |
| 通用 Prompt 内保留站牌规则 | 对单一示例可能更强 | 会污染菜单、价签等域并让评测无法区分通用能力和领域先验 |
| 自动识别领域并选择 Hint | 用户少一步 | 自动路由本身需要独立可评测模型与错误语义；当前会形成未版本化隐式决策 |
| 允许 region 轻微重叠 | 对模型更宽容 | “轻微”缺乏稳定阈值，且会让 sibling ownership 与 reading order 无法确定性复核 |

## 后果与验证

- 正向：overview/detail 同时可用；所有模型 bbox 最终统一为原 artifact 坐标；层级压扁、错位 evidence、
  orphan/cycle、重复组和错误 binding 均可被代码拒绝；通用与领域先验可独立评测。
- 代价：单阶段可发送更多图片，增加视觉 token、CPU 与内存；严格无重叠合同可能拒绝边界模糊输出；
  v6 在 N5/N7 live 之前不能替代产品 v4。
- 验证：view 决定性与坐标 property；10-view/30-MiB/费用上界；unknown/越界/包含/重叠/顺序/孤儿/环/
  repeat/ownership 对抗负例；checkpoint 1.0/2.0→3.0；GENERIC 词表去偏；真实 PostgreSQL 三阶段、三次
  reservation、STRUCTURE 零调用以及 checkpoint/Candidate 无 view id。
- 回退：移除 v6 registry 项即可停止新实验；v1..v5 Profile/Prompt 和 checkpoint 仍可读。pipeline 4.1
  没有 migration 或外部调用，源码可按节点 commit revert；已生成的 v6 checkpoint 需保留 3.0 reader。
