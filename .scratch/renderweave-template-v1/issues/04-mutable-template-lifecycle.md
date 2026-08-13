# 定义可变 Template 生命周期与永久 Schema 合同

Type: grilling
Status: resolved
Claimed by: Codex /root
Blocked by: none

## Question

在“Template 无发布状态、永久绑定一个精确 StaticSchema、保存产生历史 revision、运行默认取 current”的既定方向下，身份、创建/复制、保存、并发、历史/恢复、删除、引用解析与失败语义应如何精确定义？

## Answer

Template 是以全局唯一、不透明 `templateId` 标识的可变聚合，创建后永久绑定一个不可变 ownerScope 与一个精确 StaticSchemaRef。`displayName` 等作者 metadata 属于 DesignDSL；Template 自身只拥有身份、scope/Schema绑定、current、生命周期及派生可用性。生命周期只有 `ACTIVE → DELETED`，没有发布、归档或重新激活；DELETED 是保留全部事实但不可恢复的终态。

创建必须提交结构与全部依赖均合法的完整 DesignDSL，并原子产生 READY revision 0。DesignDSL 导入只进入允许暂时无效的本地 EditorSession，不改变服务端 current。保存提交完整 DesignDSL 与 `expectedRevision`；成功即追加完整不可变 revision 并把 current 推进到最新编号，即使内容 hash 未变化也一样。服务端不接受 JSON Patch、自动 merge、last-write-wins 或持久化无效编辑草稿。

复制必须钉死精确 `{templateId, revision}`，复制完整 DesignDSL，但不复制 revision 历史或来源 lineage；新 Template 从 revision 0 开始。v1复制只能在来源ownerScope内创建，可显式选择另一StaticSchemaRef但不执行字段自动迁移；跨scope搬运留给未来显式export/import及TemplateRef/AssetRef映射，不能借copy静默保留跨scope引用。历史内容恢复只适用于 ACTIVE Template：把精确旧 revision 的 DesignDSL 复制并追加为新 current，不回拨指针。DELETED Template 不能恢复或编辑，但允许只读查看、导出历史，以及把精确历史 revision 复制成新 Template。

校验问题分两类：未知 DSL 版本/member/kind、结构错误、TemplateRef cycle、安全或预算违规等 hard error 永远零写且不能确认绕过；结构合法 DesignDSL 的 StaticSchema field path、AssetRef 或 TemplateRef 等依赖 ERROR，可在保存、复制或 revision restore 中经二阶段确认提交为 INVALID。首次响应返回完整有界问题集和短期 confirmation token；token 绑定操作、DesignDSL hash、来源/目标、依赖快照与问题 fingerprint，确认时任何漂移都要求重新确认，禁止裸 `force=true`。

`TemplateReadiness = READY | INVALID | STALE` 是 current-facing、最终一致的界面投影，不是 Render 授权事实。Template 只保留一份不绑定 revision 的当前 TemplateValidationReport，最近完成的异步检查可以直接覆盖它；因此打开编辑器和每次 Render 请求都必须重新权威检查最新 current。INVALID/STALE 仍可查看、编辑、导入和使用本地草稿画布，也可继续用二阶段确认保存 INVALID revision，但不能成功产生 TemplateSnapshot、权威预览或 RenderOutput。严格创建/正常通过的保存、复制与恢复进入 READY；确认依赖 ERROR 进入 INVALID；依赖变化先置 STALE，再重检为 READY/INVALID。

StaticSchema 永不变化，因此 StaticSchema field path 不需要反向失效索引，只在创建、保存、跨 Schema 复制与恢复时校验。Template Design 只为 ACTIVE current 的 AssetRef 和 TemplateRef 保存事务性 dependency projection；current 改变时整体替换，历史 revision 不进入索引。Asset 或子 Template 变化会经反向索引递归重检父 Template。

DesignDSL 的 TemplateRef 只能按 `templateId` 跟随同ownerScope目标current，不允许authored exact revision引用；精确revision只用于历史、复制与恢复。TemplateRef图必须是DAG，cycle属于不可确认hard error，任何current save都必须与DAG检查原子完成。Evaluation请求开始时把全部authored可达current一次冻结为一致Template closure snapshot；静态剪枝不改变closure membership，解析期间发生任一current漂移就重试或失败，不能产生混合时刻闭包。

删除携带 `expectedRevision`，并在一致性边界检查 ACTIVE 状态及 incoming ACTIVE-current TemplateRef；存在引用即零写并返回引用摘要，不提供 force 或级联删除。删除后永久保留 templateId、StaticSchemaRef、current、全部 revision 与审计事实，不提供 purge 或身份复用。

每次 Template 本地变更都要求 revision、current、current-only dependency projections、readiness 与当前报告全成或全不成。revision conflict、确认漂移、依赖不可用、删除阻塞等失败均零写并返回稳定 code 与有界结构化问题。服务端只提供 best-effort 接口防抖，不提供 command-key 幂等保证；网络超时后的创建/复制重试可能产生重复对象，调用方负责查询与协调。
