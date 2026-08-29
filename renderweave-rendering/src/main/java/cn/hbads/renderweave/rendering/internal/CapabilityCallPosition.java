package cn.hbads.renderweave.rendering.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 冻结 {@code renderweave-capability-call-position/1.0} 的唯一实现。
 *
 * <p>RuntimePath 跟随实际 root / Repeat / TemplateUse 嵌套；DeclarationFrame 把该路径截断到
 * Definition 声明域。调用方只导航路径并请求 canonical bytes，不复制 member ordering、contract
 * mapping 或 declaration-frame 规则。
 */
final class CapabilityCallPosition {

    static final String POSITION_VERSION = "renderweave-capability-call-position/1.0";

    private CapabilityCallPosition() {
    }

    static RuntimePath root(String templateId, long revision) {
        return new RuntimePath(List.of(new Root(templateId, revision)), 1);
    }

    static String contractId(String capability) {
        return switch (capability) {
            case "CLOCK" -> "renderweave-capability-clock/1.0";
            case "RANDOM" -> "renderweave-capability-random/1.0";
            default -> throw new IllegalArgumentException("unknown capability");
        };
    }

    /** Immutable actual runtime path plus the end of the current Template invocation frame. */
    static final class RuntimePath {
        private final List<PathSegment> segments;
        private final int invocationLength;

        private RuntimePath(List<PathSegment> segments, int invocationLength) {
            Objects.requireNonNull(segments, "segments");
            this.segments = List.copyOf(segments);
            this.invocationLength = invocationLength;
            if (this.segments.isEmpty() || !(this.segments.getFirst() instanceof Root)) {
                throw new IllegalArgumentException("path must begin with ROOT");
            }
            if (invocationLength < 1 || invocationLength > this.segments.size()) {
                throw new IllegalArgumentException("invocationLength is outside path");
            }
            for (int index = 1; index < this.segments.size(); index++) {
                if (this.segments.get(index) instanceof Root) {
                    throw new IllegalArgumentException("ROOT must be unique");
                }
            }
        }

        RuntimePath enterRepeat(String loopId, int inputIndex) {
            return new RuntimePath(appending(new Repeat(loopId, inputIndex)), invocationLength);
        }

        RuntimePath enterTemplateUse(
                String useId, String childTemplateId, long childRevision) {
            var next = appending(new TemplateUse(useId, childTemplateId, childRevision));
            return new RuntimePath(next, next.size());
        }

        DeclarationFrame invocationFrame() {
            return new DeclarationFrame(segments.subList(0, invocationLength));
        }

        DeclarationFrame loopFrame(String loopId) {
            requireText(loopId, "loopId");
            for (int index = invocationLength; index < segments.size(); index++) {
                if (segments.get(index) instanceof Repeat repeat
                        && repeat.loopId().equals(loopId)) {
                    return new DeclarationFrame(segments.subList(0, index + 1));
                }
            }
            throw new IllegalArgumentException("loop declaration is not active in this invocation");
        }

        List<String> canonicalSegments() {
            return segments.stream().map(PathSegment::canonical).toList();
        }

        private List<PathSegment> appending(PathSegment segment) {
            var next = new ArrayList<PathSegment>(segments.size() + 1);
            next.addAll(segments);
            next.add(segment);
            return List.copyOf(next);
        }
    }

    /** Exact declaration domain used by Definition memoization and capability position formation. */
    static final class DeclarationFrame {
        private final List<PathSegment> path;

        private DeclarationFrame(List<PathSegment> path) {
            Objects.requireNonNull(path, "path");
            this.path = List.copyOf(path);
            if (this.path.isEmpty() || !(this.path.getFirst() instanceof Root)) {
                throw new IllegalArgumentException("declaration path must begin with ROOT");
            }
        }

        byte[] canonicalBytes(
                String definitionId,
                String inputAlias,
                String capability,
                String operation
        ) {
            requireText(definitionId, "definitionId");
            requireText(inputAlias, "inputAlias");
            requireOperation(capability, operation);
            var pathItems = path.stream().map(PathSegment::canonical).toList();
            var members = new TreeMap<String, String>();
            members.put("positionVersion", CanonicalJson.string(POSITION_VERSION));
            members.put("path", CanonicalJson.array(pathItems));
            members.put("definitionId", CanonicalJson.string(definitionId));
            members.put("inputAlias", CanonicalJson.string(inputAlias));
            members.put("capabilityContractId", CanonicalJson.string(contractId(capability)));
            members.put("operation", CanonicalJson.string(operation));
            return CanonicalJson.object(members).getBytes(StandardCharsets.UTF_8);
        }

        String memoKey() {
            return CanonicalJson.array(path.stream().map(PathSegment::canonical).toList());
        }
    }

    private sealed interface PathSegment permits Root, TemplateUse, Repeat {
        String canonical();
    }

    private record Root(String templateId, long revision) implements PathSegment {
        Root {
            requireText(templateId, "templateId");
            requireNonNegative(revision, "revision");
        }

        @Override
        public String canonical() {
            var members = new TreeMap<String, String>();
            members.put("kind", CanonicalJson.string("ROOT"));
            members.put("templateId", CanonicalJson.string(templateId));
            members.put("revision", Long.toString(revision));
            return CanonicalJson.object(members);
        }
    }

    private record TemplateUse(
            String useId, String templateId, long revision) implements PathSegment {
        TemplateUse {
            requireText(useId, "useId");
            requireText(templateId, "templateId");
            requireNonNegative(revision, "revision");
        }

        @Override
        public String canonical() {
            var members = new TreeMap<String, String>();
            members.put("kind", CanonicalJson.string("TEMPLATE_USE"));
            members.put("useId", CanonicalJson.string(useId));
            members.put("templateId", CanonicalJson.string(templateId));
            members.put("revision", Long.toString(revision));
            return CanonicalJson.object(members);
        }
    }

    private record Repeat(String loopId, int inputIndex) implements PathSegment {
        Repeat {
            requireText(loopId, "loopId");
            requireNonNegative(inputIndex, "inputIndex");
        }

        @Override
        public String canonical() {
            var members = new TreeMap<String, String>();
            members.put("kind", CanonicalJson.string("REPEAT"));
            members.put("loopId", CanonicalJson.string(loopId));
            members.put("inputIndex", Integer.toString(inputIndex));
            return CanonicalJson.object(members);
        }
    }

    private static void requireOperation(String capability, String operation) {
        requireText(capability, "capability");
        requireText(operation, "operation");
        boolean valid = switch (capability) {
            case "CLOCK" -> "UTC_DATE".equals(operation) || "UTC_TIME".equals(operation);
            case "RANDOM" -> "UNIFORM_DECIMAL_0_1".equals(operation);
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("operation does not belong to capability contract");
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
