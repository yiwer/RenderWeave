package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.api.TemplateClosureAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import cn.hbads.renderweave.template.spi.TemplatePersistence.CurrentLoaded;
import cn.hbads.renderweave.template.spi.TemplatePersistence.CurrentNotFound;
import cn.hbads.renderweave.template.spi.TemplatePersistence.CurrentLoadUnavailable;
import cn.hbads.renderweave.template.spi.TemplatePersistence.Located;
import cn.hbads.renderweave.template.spi.TemplatePersistence.LocateUnavailable;
import cn.hbads.renderweave.template.spi.TemplatePersistence.StoredCurrent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Render 专用闭包冻结（ADR-0044 §1）：root current 解析、每份候选 revision 的 exact
 * parse/canonical/contentHash integrity 复核、递归 TemplateRef 闭包、逐 unique snapshot
 * same-scope/DAG 权威重检、current 漂移有界重试。
 *
 * <p>integrity mismatch 是内部 integrity failure：不降级、不重算、不把 payload 交给 Evaluator。
 * 容量限制取冻结 capacity-budgets {@code closureAndExpansion} 键名作为 limitId。
 */
final class CanonicalTemplateClosureAuthority implements TemplateClosureAuthority {

    private static final int MAX_FREEZE_ATTEMPTS = 3;
    private static final int MAX_UNIQUE_SNAPSHOTS = 64;
    private static final int MAX_AUTHORED_EDGES = 256;
    private static final int MAX_CLOSURE_DEPTH = 16;
    private static final long MAX_CLOSURE_CANONICAL_DESIGN_BYTES = 32L * 1024 * 1024;

    private static final Comparator<String> UTF8_ORDER =
            Comparator.comparing(name -> name.getBytes(StandardCharsets.UTF_8),
                    Arrays::compareUnsigned);

    private final TemplatePersistence persistence;
    private final DesignDslAuthority dslAuthority;

    CanonicalTemplateClosureAuthority(TemplatePersistence persistence) {
        this(persistence, new CanonicalDesignDslAuthority());
    }

    CanonicalTemplateClosureAuthority(TemplatePersistence persistence, DesignDslAuthority dslAuthority) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.dslAuthority = Objects.requireNonNull(dslAuthority, "dslAuthority");
    }

    @Override
    public ClosureOutcome freezeClosure(
            RenderRequestId renderRequestId,
            TemplateApplication.TemplateId rootTemplateId,
            ClosureControl control
    ) {
        Objects.requireNonNull(renderRequestId, "renderRequestId");
        Objects.requireNonNull(rootTemplateId, "rootTemplateId");
        Objects.requireNonNull(control, "control");
        if (control.deadlineExceeded()) {
            return new ClosureDeadlineExceeded();
        }
        for (int attempt = 0; attempt < MAX_FREEZE_ATTEMPTS; attempt++) {
            if (control.deadlineExceeded()) {
                return new ClosureDeadlineExceeded();
            }
            var step = tryFreeze(rootTemplateId, control);
            if (control.deadlineExceeded()) {
                return new ClosureDeadlineExceeded();
            }
            if (!(step instanceof Drifted)) {
                return ((Done) step).outcome();
            }
        }
        if (control.deadlineExceeded()) {
            return new ClosureDeadlineExceeded();
        }
        return new ClosureUnstable();
    }

    private sealed interface FreezeStep permits Done, Drifted {
    }

    private record Done(ClosureOutcome outcome) implements FreezeStep {
    }

    private record Drifted() implements FreezeStep {
    }

    private FreezeStep tryFreeze(
            TemplateApplication.TemplateId rootTemplateId,
            ClosureControl control
    ) {
        var deadline = deadlineExceeded(control);
        if (deadline != null) {
            return deadline;
        }
        var snapshots = new TreeMap<String, TemplateSnapshot>(UTF8_ORDER);
        var edges = new ArrayList<ClosureEdge>();
        var inPath = new LinkedHashSet<String>();
        var budget = new ClosureBudget();

        var visitFailure = visit(
                rootTemplateId, 1, snapshots, edges, inPath, budget, control);
        deadline = deadlineExceeded(control);
        if (deadline != null) {
            return deadline;
        }
        if (visitFailure != null) {
            return visitFailure;
        }

        var rootSnapshot = snapshots.get(rootTemplateId.value());
        for (var snapshot : snapshots.values()) {
            deadline = deadlineExceeded(control);
            if (deadline != null) {
                return deadline;
            }
            if (!snapshot.ownerScope().equals(rootSnapshot.ownerScope())) {
                return new Done(new ClosureDependencyInvalid());
            }
        }

        for (var snapshot : snapshots.values()) {
            deadline = deadlineExceeded(control);
            if (deadline != null) {
                return deadline;
            }
            var located = persistence.locate(snapshot.templateId());
            deadline = deadlineExceeded(control);
            if (deadline != null) {
                return deadline;
            }
            if (located instanceof LocateUnavailable) {
                return new Done(new ClosureUnavailable());
            }
            if (!(located instanceof Located found)
                    || found.metadata().lifecycle() == TemplatePersistence.Lifecycle.DELETED
                    || found.metadata().currentRevision() != snapshot.revision()) {
                return new Drifted();
            }
        }

        deadline = deadlineExceeded(control);
        if (deadline != null) {
            return deadline;
        }
        var sortedEdges = edges.stream()
                .sorted(Comparator
                        .comparing((ClosureEdge edge) -> edge.parentTemplateId().value(), UTF8_ORDER)
                        .thenComparing(ClosureEdge::parentRevision)
                        .thenComparing(edge -> edge.useId(), UTF8_ORDER))
                .toList();
        deadline = deadlineExceeded(control);
        if (deadline != null) {
            return deadline;
        }
        return new Done(new ClosureFrozen(new ClosureSnapshot(
                rootSnapshot.ownerScope(),
                rootTemplateId,
                rootSnapshot.revision(),
                List.copyOf(snapshots.values()),
                sortedEdges
        )));
    }

    /** Returns {@code null} on success, otherwise the terminal freeze step. */
    private FreezeStep visit(
            TemplateApplication.TemplateId templateId,
            int depth,
            TreeMap<String, TemplateSnapshot> snapshots,
            ArrayList<ClosureEdge> edges,
            LinkedHashSet<String> inPath,
            ClosureBudget budget,
            ClosureControl control
    ) {
        var deadline = deadlineExceeded(control);
        if (deadline != null) {
            return deadline;
        }
        if (depth > MAX_CLOSURE_DEPTH) {
            return new Done(new ClosureLimitExceeded(new LimitId("closureDepth")));
        }
        if (inPath.contains(templateId.value())) {
            // Back edge onto the current DFS path: authored TemplateUse graph must be a DAG.
            return new Done(new ClosureDependencyInvalid());
        }
        if (snapshots.containsKey(templateId.value())) {
            // Completed diamond target: freeze once, edges stay per authored use.
            return null;
        }

        var loaded = persistence.loadCurrent(templateId);
        deadline = deadlineExceeded(control);
        if (deadline != null) {
            return deadline;
        }
        boolean root = depth == 1;
        if (loaded instanceof CurrentNotFound) {
            return new Done(root ? new ClosureNotFound() : new ClosureDependencyInvalid());
        }
        if (loaded instanceof CurrentLoadUnavailable) {
            return new Done(new ClosureUnavailable());
        }
        var stored = ((CurrentLoaded) loaded).current();
        if (stored.metadata().lifecycle() == TemplatePersistence.Lifecycle.DELETED) {
            return new Done(root ? new ClosureDeleted() : new ClosureDependencyInvalid());
        }

        var integrityFailure = verifyIntegrity(stored);
        deadline = deadlineExceeded(control);
        if (deadline != null) {
            return deadline;
        }
        if (integrityFailure != null) {
            return integrityFailure;
        }

        var canonicalDesignDslUtf8 = stored.canonicalDesignDslUtf8();
        var versions = readVersions(canonicalDesignDslUtf8);
        deadline = deadlineExceeded(control);
        if (deadline != null) {
            return deadline;
        }
        if (versions == null) {
            return new Done(new ClosureIntegrityViolation());
        }
        if (!budget.reserveCanonicalDesignBytes(canonicalDesignDslUtf8.length)) {
            return new Done(new ClosureLimitExceeded(
                    new LimitId("closureCanonicalDesignBytes")
            ));
        }

        var snapshot = new TemplateSnapshot(
                templateId,
                stored.metadata().currentRevision(),
                new OwnerScope(stored.metadata().ownerScope().value()),
                stored.metadata().staticSchema(),
                versions.dslVersion(),
                versions.expressionProfile(),
                canonicalDesignDslUtf8,
                stored.contentHash()
        );
        inPath.add(templateId.value());
        snapshots.put(templateId.value(), snapshot);
        if (snapshots.size() > MAX_UNIQUE_SNAPSHOTS) {
            return new Done(new ClosureLimitExceeded(new LimitId("uniqueTemplateSnapshots")));
        }

        var projection = new AssetRefAtomExtractor().extract(canonicalDesignDslUtf8);
        deadline = deadlineExceeded(control);
        if (deadline != null) {
            return deadline;
        }
        for (var use : projection.templateUses()) {
            deadline = deadlineExceeded(control);
            if (deadline != null) {
                return deadline;
            }
            var childId = new TemplateApplication.TemplateId(use.targetTemplateId());
            var childFailure = visit(
                    childId, depth + 1, snapshots, edges, inPath, budget, control);
            deadline = deadlineExceeded(control);
            if (deadline != null) {
                return deadline;
            }
            if (childFailure != null) {
                return childFailure;
            }
            var child = snapshots.get(childId.value());
            edges.add(new ClosureEdge(
                    templateId,
                    snapshot.revision(),
                    use.useId(),
                    childId,
                    child.revision()
            ));
            if (edges.size() > MAX_AUTHORED_EDGES) {
                return new Done(new ClosureLimitExceeded(new LimitId("authoredTemplateRefEdges")));
            }
        }
        inPath.remove(templateId.value());
        deadline = deadlineExceeded(control);
        if (deadline != null) {
            return deadline;
        }
        return null;
    }

    private static Done deadlineExceeded(ClosureControl control) {
        return control.deadlineExceeded()
                ? new Done(new ClosureDeadlineExceeded())
                : null;
    }

    private static final class ClosureBudget {
        private long canonicalDesignBytes;

        private boolean reserveCanonicalDesignBytes(int nextSnapshotBytes) {
            if (canonicalDesignBytes
                    > MAX_CLOSURE_CANONICAL_DESIGN_BYTES - nextSnapshotBytes) {
                return false;
            }
            canonicalDesignBytes += nextSnapshotBytes;
            return true;
        }
    }

    /**
     * Exact parse/canonical/contentHash integrity 复核：对持久化 canonical bytes 重新执行
     * admission，要求 canonical 输出 byte-identical 且 domain-separated contentHash 相等。
     */
    private Done verifyIntegrity(StoredCurrent stored) {
        var admission = dslAuthority.admit(stored.canonicalDesignDslUtf8());
        if (!(admission instanceof DesignDslAuthority.Admitted admitted)
                || !Arrays.equals(admitted.canonicalUtf8(), stored.canonicalDesignDslUtf8())
                || !admitted.contentHash().equals(stored.contentHash())) {
            return new Done(new ClosureIntegrityViolation());
        }
        return null;
    }

    private record Versions(String dslVersion, String expressionProfile) {
    }

    private Versions readVersions(byte[] canonicalUtf8) {
        try {
            var parsed = new StrictJsonParser().parse(canonicalUtf8);
            if (!(parsed instanceof JsonValue.ObjectValue root)) {
                return null;
            }
            if (!(root.members().get("dslVersion") instanceof JsonValue.StringValue dslVersion)
                    || !(root.members().get("expressionProfile")
                            instanceof JsonValue.StringValue expressionProfile)) {
                return null;
            }
            return new Versions(dslVersion.value(), expressionProfile.value());
        } catch (DesignDslFailureException impossible) {
            return null;
        }
    }
}
