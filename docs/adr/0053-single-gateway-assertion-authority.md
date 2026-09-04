# ADR-0053：单一 GatewayAssertion authority 与持久化 mutation replay guard

- 状态：accepted
- 日期：2026-08-18
- 决策来源：production-admission ticket 04、IOPA-P2-01

生产 API 只接受由唯一 gateway 签发的 compact JWS，并把验证封装在单一
`GatewayAssertionAuthority` 接口中；controller 不读取客户端 actor/request header，也不保存完整 token。
首版只接受 `EdDSA/Ed25519` 与 exact `kid` 公钥集合，claims 绑定 issuer、audience、opaque actor/request/jti、
method、path，以及 mutation 的 domain-separated Idempotency-Key SHA-256；`exp-iat` 不超过 60 秒，30 秒仅作为
assertion clock skew，不延长任何业务 deadline。

所有签名、claim、request 与时间检查通过后，mutation jti 必须在 PostgreSQL 中原子 insert 成功才返回验证身份；
重复 jti、存储不可写、检测到超过 30 秒的 UTC rollback 均 fail-closed。读取请求不消费 replay ledger。
gateway→API 与内部 Actuator 的 mTLS 由独立 exact certificate fingerprint 集合约束，公共 nginx 永不代理
Actuator。这样 key rotation 可通过短暂双公钥/双证书集合完成，而不会把 JWS、账号、session 或 RBAC 引入应用。

该 authority 只证明网关身份与 request intent，不构成 `ExternalTransferConfirmation`、Profile Certification、
`ProductionLiveAuthority`、Provider egress 或 Candidate apply/publish 权限。
