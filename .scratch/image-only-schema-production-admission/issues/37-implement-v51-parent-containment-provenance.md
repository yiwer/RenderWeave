# 37 — IOPA-P1-R22：实现 v51 parent-containment provenance classifier

**What to build:** 实现票 36 的 payload-free classifier、terminal rejection envelope、compatibility tests 与独立 verifier。

**Blocked by:** 36 — IOPA-P1-R21。

**Status:** resolved

- [x] 分类 ITEM/non-ITEM zero/ambiguous candidates、atomic rollback 与 unclassified，输出稳定 allowlist。
- [x] v51 classified primary non-retryable，scripted Provider 证明只 1 call、无第 2 permit。
- [x] v50 及更早行为/bytes/digests 不变。
- [x] payload scan、focused inference、Testcontainers 与 dedicated gate 全绿，Provider usage=0。

## Evidence

- A1：`.sdlc/evidence/20260818-142150-image-only-v51-successor/`
- 结果：61 inference tests + 3 Testcontainers PostgreSQL tests，0 failures/errors。
- implementation identity：`renderweave-image-only-v51-implementation/1.0:45f3e9d6b25a0fbe9788fd7714adecaabc800a9279c2e37d73e76f850df887f1`
