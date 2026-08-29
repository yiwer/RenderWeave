package cn.hbads.renderweave.template.api;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.util.List;
import java.util.Objects;

/**
 * Template-owned render 专用只读 seam（ADR-0044 §1，镜像 T06 给 schema 补
 * {@code StaticSchemaAuthority} 的模式）：把冻结规格 stage 2–3 收口为一次请求级一致闭包。
 *
 * <p>{@link #freezeClosure} 解析 root current，对每份候选 revision 做 exact parse/canonical/
 * contentHash integrity 复核（mismatch 是内部 integrity failure，不降级、不重算、不把 payload
 * 交给 Evaluator），递归冻结 authored TemplateRef 闭包，逐 unique snapshot 做 same-scope/DAG/
 * Profile/无损 lowering-edge 权威重检，并在 current 漂移时有界重试（耗尽
 * {@link ClosureUnstable}）。与 T12b {@code AssetReferenceAuthority}（delete 预检 proof）各自
 * 独立——操作面与消费方不同，但共享同一 snapshot 值类型家族。
 */
public interface TemplateClosureAuthority {

    default ClosureOutcome freezeClosure(
            RenderRequestId renderRequestId,
            TemplateApplication.TemplateId rootTemplateId
    ) {
        return freezeClosure(renderRequestId, rootTemplateId, ClosureControl.unbounded());
    }

    ClosureOutcome freezeClosure(
            RenderRequestId renderRequestId,
            TemplateApplication.TemplateId rootTemplateId,
            ClosureControl control
    );

    /**
     * Rendering-owned cooperative stop signal. Template observes only expiry and deliberately
     * does not own a clock, duration, public stage, limit id, or Rendering problem taxonomy.
     */
    @FunctionalInterface
    interface ClosureControl {
        boolean deadlineExceeded();

        static ClosureControl unbounded() {
            return () -> false;
        }
    }

    /** Rendering 传入的请求级不透明身份；Template 只做关联，不做生命周期解释。 */
    record RenderRequestId(String value) {
        public RenderRequestId {
            Objects.requireNonNull(value, "value");
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("renderRequestId must be non-blank and at most 256 chars");
            }
        }
    }

    /** render snapshot 携带的 ownerScope 值；来源是 Template 持久化事实，不是请求自报。 */
    record OwnerScope(String value) {
        public OwnerScope {
            Objects.requireNonNull(value, "value");
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("ownerScope must be non-blank and at most 256 chars");
            }
        }
    }

    /**
     * 一个已权威重检的 Template current 的不可变交接值（CONTEXT "TemplateSnapshot"）。
     * 不是新的 Template revision，不含 current 指针、删除状态、编辑会话或持久化模型，
     * 也不得信任 readiness 投影或在一次 Evaluation 中重新读取可变 Template——因此
     * readiness/displayName/row version 一律不进入 snapshot。
     */
    record TemplateSnapshot(
            TemplateApplication.TemplateId templateId,
            long revision,
            OwnerScope ownerScope,
            StaticSchemaRef staticSchema,
            String dslVersion,
            String expressionProfile,
            byte[] canonicalDesignDslUtf8,
            String contentHash
    ) {
        public TemplateSnapshot {
            Objects.requireNonNull(templateId, "templateId");
            Objects.requireNonNull(ownerScope, "ownerScope");
            Objects.requireNonNull(staticSchema, "staticSchema");
            Objects.requireNonNull(dslVersion, "dslVersion");
            Objects.requireNonNull(expressionProfile, "expressionProfile");
            Objects.requireNonNull(canonicalDesignDslUtf8, "canonicalDesignDslUtf8");
            Objects.requireNonNull(contentHash, "contentHash");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            if (canonicalDesignDslUtf8.length == 0) {
                throw new IllegalArgumentException("canonicalDesignDslUtf8 must not be empty");
            }
            canonicalDesignDslUtf8 = canonicalDesignDslUtf8.clone();
        }

        public byte[] canonicalDesignDslUtf8() {
            return canonicalDesignDslUtf8.clone();
        }
    }

    /** 一条 authored TemplateUse 闭包边；diamond snapshot 只列一次，边逐条保留。 */
    record ClosureEdge(
            TemplateApplication.TemplateId parentTemplateId,
            long parentRevision,
            String useId,
            TemplateApplication.TemplateId childTemplateId,
            long childRevision
    ) {
        public ClosureEdge {
            Objects.requireNonNull(parentTemplateId, "parentTemplateId");
            Objects.requireNonNull(useId, "useId");
            Objects.requireNonNull(childTemplateId, "childTemplateId");
            if (parentRevision < 0 || childRevision < 0) {
                throw new IllegalArgumentException("revisions must not be negative");
            }
        }
    }

    /**
     * 一次 Evaluation 对根 Template 及全部 authored 可达 TemplateRef current 形成的一致
     * 请求级 snapshot 集合：相同 templateId 只冻结一次，但每条 use edge 独立保留。
     * snapshots 按 templateId UTF-8 排序；edges 按 parentTemplateId、parentRevision、useId 排序。
     */
    record ClosureSnapshot(
            OwnerScope ownerScope,
            TemplateApplication.TemplateId rootTemplateId,
            long rootRevision,
            List<TemplateSnapshot> snapshots,
            List<ClosureEdge> edges
    ) {
        public ClosureSnapshot {
            Objects.requireNonNull(ownerScope, "ownerScope");
            Objects.requireNonNull(rootTemplateId, "rootTemplateId");
            Objects.requireNonNull(snapshots, "snapshots");
            Objects.requireNonNull(edges, "edges");
            if (rootRevision < 0) {
                throw new IllegalArgumentException("rootRevision must not be negative");
            }
            snapshots = List.copyOf(snapshots);
            edges = List.copyOf(edges);
            if (snapshots.isEmpty()) {
                throw new IllegalArgumentException("closure must contain the root snapshot");
            }
        }
    }

    sealed interface ClosureOutcome
            permits ClosureFrozen, ClosureNotFound, ClosureDeleted, ClosureDependencyInvalid,
                    ClosureIntegrityViolation, ClosureUnstable, ClosureLimitExceeded,
                    ClosureUnavailable, ClosureDeadlineExceeded {
    }

    record ClosureFrozen(ClosureSnapshot closure) implements ClosureOutcome {
        public ClosureFrozen {
            Objects.requireNonNull(closure, "closure");
        }
    }

    /** 根 Template 不存在。 */
    record ClosureNotFound() implements ClosureOutcome {
    }

    /** 根 Template 已 DELETED。 */
    record ClosureDeleted() implements ClosureOutcome {
    }

    /** 闭包依赖被拒绝：目标缺失/DELETED/跨 ownerScope/成环。 */
    record ClosureDependencyInvalid() implements ClosureOutcome {
    }

    /** canonical/contentHash integrity 复核失败：内部违约，对外折叠 RENDER_INTERNAL_ERROR。 */
    record ClosureIntegrityViolation() implements ClosureOutcome {
    }

    /** current 漂移有界重试耗尽。 */
    record ClosureUnstable() implements ClosureOutcome {
    }

    /** 闭包容量拒绝；limitId 取冻结 capacity-budgets closureAndExpansion 键名。 */
    record ClosureLimitExceeded(LimitId limitId) implements ClosureOutcome {
        public ClosureLimitExceeded {
            Objects.requireNonNull(limitId, "limitId");
        }
    }

    record ClosureUnavailable() implements ClosureOutcome {
    }

    /** Rendering-owned cooperative deadline expired while the closure was still freezing. */
    record ClosureDeadlineExceeded() implements ClosureOutcome {
    }

    record LimitId(String value) {
        public LimitId {
            Objects.requireNonNull(value, "value");
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("limitId must be non-blank and at most 256 chars");
            }
        }
    }
}
