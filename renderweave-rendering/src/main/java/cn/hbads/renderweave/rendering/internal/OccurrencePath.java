package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.asset.api.AssetAcceptanceAuthority.AssetKind;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Request-local full occurrence locator. Invocation segments are shared with the exact capability
 * call-position authority; this type solely owns the final node/role segment and resource locator
 * framing. It is never a RenderDocument or public API value.
 */
final class OccurrencePath {

    static final String PATH_VERSION = "renderweave-occurrence-path/1.0";
    private static final String RESOURCE_IDENTITY_DOMAIN =
            "renderweave-resource-occurrence/1\0";

    enum Role {
        SOURCE_NODE("source-node", true),
        REPEAT_CONTAINER("repeat-container", true),
        REPEAT_ITEM("repeat-item", false),
        CONDITIONAL_FRAME("conditional-frame", true),
        TEMPLATE_USE_VIEWPORT("template-use-viewport", true),
        CANVAS_BACKGROUND("canvas-background", true);

        private final String wire;
        private final boolean sourceNodeRequired;

        Role(String wire, boolean sourceNodeRequired) {
            this.wire = wire;
            this.sourceNodeRequired = sourceNodeRequired;
        }

        String wire() {
            return wire;
        }
    }

    private final List<String> invocationSegments;
    private final String sourceNodeId;
    private final Role role;

    private OccurrencePath(
            List<String> invocationSegments,
            String sourceNodeId,
            Role role
    ) {
        this.invocationSegments = List.copyOf(invocationSegments);
        if (this.invocationSegments.isEmpty()) {
            throw new IllegalArgumentException("occurrence path requires a root invocation");
        }
        this.role = Objects.requireNonNull(role, "role");
        if (role.sourceNodeRequired) {
            requireText(sourceNodeId, "sourceNodeId");
        } else if (sourceNodeId != null) {
            throw new IllegalArgumentException("synthetic role must not invent sourceNodeId");
        }
        this.sourceNodeId = sourceNodeId;
    }

    static OccurrencePath sourceNode(
            CapabilityCallPosition.RuntimePath runtimePath,
            String sourceNodeId,
            Role role
    ) {
        Objects.requireNonNull(runtimePath, "runtimePath");
        if (!role.sourceNodeRequired) {
            throw new IllegalArgumentException("role has no source node");
        }
        return new OccurrencePath(runtimePath.canonicalSegments(), sourceNodeId, role);
    }

    static OccurrencePath synthetic(
            CapabilityCallPosition.RuntimePath runtimePath,
            Role role
    ) {
        Objects.requireNonNull(runtimePath, "runtimePath");
        if (role.sourceNodeRequired) {
            throw new IllegalArgumentException("role requires a source node");
        }
        return new OccurrencePath(runtimePath.canonicalSegments(), null, role);
    }

    /** Compatibility constructor for direct Sealer unit fixtures, never used by Materializer. */
    static OccurrencePath testing(String token, String kind) {
        requireText(token == null || token.isBlank() ? "test-root" : token, "token");
        var source = token == null || token.isBlank() ? "test-root" : token;
        var role = switch (kind) {
            case "canvas" -> Role.CANVAS_BACKGROUND;
            case "compositionViewport" -> Role.TEMPLATE_USE_VIEWPORT;
            default -> Role.SOURCE_NODE;
        };
        return sourceNode(
                CapabilityCallPosition.root("test-template", 1), source, role);
    }

    Role role() {
        return role;
    }

    String sourceNodeId() {
        return sourceNodeId;
    }

    String canonicalJson() {
        return CanonicalJson.encode(canonicalValue());
    }

    CanonicalJson.CanonicalValue canonicalValue() {
        var segments = new ArrayList<CanonicalJson.CanonicalValue>(
                invocationSegments.size() + 1);
        for (var segment : invocationSegments) {
            segments.add(encoded(segment));
        }
        var node = new java.util.TreeMap<String, CanonicalJson.CanonicalValue>();
        node.put("kind", CanonicalJson.stringValue("NODE"));
        if (sourceNodeId != null) {
            node.put("nodeId", CanonicalJson.stringValue(sourceNodeId));
        }
        node.put("role", CanonicalJson.stringValue(role.wire));
        segments.add(CanonicalJson.objectValue(node));
        return CanonicalJson.objectValue(Map.of(
                "pathVersion", CanonicalJson.stringValue(PATH_VERSION),
                "segments", CanonicalJson.arrayValue(segments)));
    }

    byte[] resourceIdentityBytes(
            ConsumerPropertyRef consumerPropertyRef,
            AssetKind expectedKind
    ) {
        Objects.requireNonNull(consumerPropertyRef, "consumerPropertyRef");
        Objects.requireNonNull(expectedKind, "expectedKind");
        var locator = CanonicalJson.objectValue(Map.of(
                "consumerPropertyRef", consumerPropertyRef.canonicalValue(),
                "expectedKind", CanonicalJson.stringValue(expectedKind.name()),
                "occurrencePath", canonicalValue()));
        var canonical = CanonicalJson.encode(locator).getBytes(StandardCharsets.UTF_8);
        var domain = RESOURCE_IDENTITY_DOMAIN.getBytes(StandardCharsets.UTF_8);
        var framed = new byte[domain.length + canonical.length];
        System.arraycopy(domain, 0, framed, 0, domain.length);
        System.arraycopy(canonical, 0, framed, domain.length, canonical.length);
        return framed;
    }

    private static CanonicalJson.CanonicalValue encoded(String canonicalJson) {
        return sink -> sink.writeUtf8(canonicalJson);
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OccurrencePath path)) {
            return false;
        }
        return invocationSegments.equals(path.invocationSegments)
                && Objects.equals(sourceNodeId, path.sourceNodeId)
                && role == path.role;
    }

    @Override
    public int hashCode() {
        return Objects.hash(invocationSegments, sourceNodeId, role);
    }

    @Override
    public String toString() {
        return canonicalJson();
    }
}
