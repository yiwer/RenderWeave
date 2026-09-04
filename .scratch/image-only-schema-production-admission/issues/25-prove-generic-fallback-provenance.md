# 25 — IOPA-P1-R10：证明 generic fallback provenance 全覆盖

**What to build:** 让执行者无需查看任何历史或新模型 payload，即可用 synthetic fixtures 对票 24 批准的 generic fallback 边界做完整、可重复的 Provider-zero 分类：每个已知 mixed-field 输入得到稳定的 bounded provenance，每个真正未知输入保持 unclassified，合法输入仍进入既有观察流程。

**Blocked by:** 24 — IOPA-P1-R09：冻结 v49 mixed-region fallback recovery contract。

**Status:** resolved

- [x] 从 v48 generic wrapper 的全部确定性 escape seam 建立穷举矩阵，覆盖 region collection/entry、七类 field family、同时多字段失败、evidence/view conversion、constructor 与不可归类异常。
- [x] 每个获批 known-mixed fixture 稳定产生批准的 primary code 与精确 bounded detail set；fixture 顺序、异常 message 或运行环境变化不得改变分类。
- [x] unclassified fixture 只产生批准的 unclassified primary code，不猜测 detail、不复用 known-mixed correction 权限。
- [x] 合法 region 输出仍通过 OBSERVE 并进入既有 HIERARCHY/BINDING/Candidate 路径，分类扩展不改变业务结果。
- [x] 相同 synthetic 输入在 v48 仍产生 immutable legacy generic 行为；v47 及更早 Profile、Prompt、pipeline snapshots 同样保持不变。
- [x] 分类结果只允许固定 code、detail-set cardinality 和计数；不得记录字段值、坐标、局部 ID、异常 cause/message、图片、Prompt、RootDocument 或模型 JSON。
- [x] 用独立 verifier 重算 taxonomy coverage、closed-enum completeness、stable ordering 与历史 identity/digest，任何遗漏或额外 code fail closed。
- [x] 局部 contract gate 与 payload scan 通过，Provider attempts/reservations/cost/API-key reads=0，OPEN authorization=0。

## Evidence

- 生命周期：`automated_verified/resolved`。这只关闭 Provider-zero provenance 证明，不创建 v49 Profile、不授予
  retry/live/certification 权限，也不改变 v48 或更早资源。
- 新增 successor-only `VisualRegionFallbackClassifier` 与 13 个 payload-free fixtures：3 个 known-mixed、2 个
  unclassified，并覆盖合法、七类单字段与 mixed 2..7 canonical detail。v48 对相同 synthetic mixed 输入继续返回
  legacy `VISUAL_GROUNDING_REGION_INVALID`。
- A1 gate：`.sdlc/evidence/20260818-101738-image-only-v49-provenance/`，result=`PASS`；独立 verifier 重算
  taxonomy/closed enum/order、v46/v47/v48 canonical hashes 与历史 terminal/J1/live-summary digests，确认
  `v49ProfileCreated=false`、`openAuthorizationCount=0`。
- Java focused contracts 46/46 通过；fast gate `.sdlc/evidence/20260818-102357-fast/` 通过。验证期间
  Provider attempts/reservations/cost/API-key reads=0，Candidate applied=false，StaticSchema published=false。
- 历史文件 `plans/live-canary-authorizations/20260817-deepseek-ocr-spike.json` 存在已冻结的重复 top-level
  `status=OPEN→CLOSED`；verifier 只对白名单中的 exact path + SHA-256
  `f061fd6d6a7065756d55da20a63bb28d7bf0156d787f3b859559ba8ea95b7d5f` 接受该单一遗留异常，其他 duplicate key
  全部 fail closed，且最终状态只能为 CLOSED。
