package cn.hbads.renderweave.rendering.api;

/**
 * Evaluation 九值全序阶段（ADR-0044 §8）：认证与容量准入 → root current 解析与 closure 冻结 →
 * 每 unique snapshot 权威重检 → RootDocument 验证与 AdmittedRenderInput 形成 → authored AssetRef
 * 预准入 → CapabilityState 建立 → 惰性 materialization → 原子 seal → Renderer Command。
 *
 * <p>失败问题按所在阶段收口；stage 2–3 同属 {@link #TEMPLATE_CLOSURE}。
 */
public enum EvaluationStage {
    REQUEST_ADMISSION,
    TEMPLATE_CLOSURE,
    INPUT_ADMISSION,
    ASSET_ADMISSION,
    CAPABILITY_STATE,
    MATERIALIZATION,
    ASSET_RESOLUTION,
    DOCUMENT_SEAL,
    ENGINE
}
