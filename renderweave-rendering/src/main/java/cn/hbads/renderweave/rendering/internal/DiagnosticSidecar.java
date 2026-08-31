package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.RenderingProblem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical request-local diagnostic locator aggregate. Raw bytes never cross the Rendering
 * public Interface and are discarded with the evaluation operation.
 */
final class DiagnosticSidecar {

    static final String SIDECAR_VERSION = "renderweave-diagnostic-sidecar/1.0";
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    record BoundOccurrence(
            String occurrenceId,
            OccurrencePath occurrencePath,
            String sourceNodeId
    ) {
        BoundOccurrence {
            Objects.requireNonNull(occurrenceId, "occurrenceId");
            Objects.requireNonNull(occurrencePath, "occurrencePath");
        }
    }

    sealed interface Projection permits Projected, InvalidProjection {
    }

    record Projected(Optional<String> safeLocation) implements Projection {
        Projected {
            Objects.requireNonNull(safeLocation, "safeLocation");
        }
    }

    enum InvalidProjection implements Projection {
        INSTANCE
    }

    enum TemplateSegmentDisclosure {
        READABLE,
        REDACTED
    }

    enum AssetIdentityDisclosure {
        READABLE,
        REDACTED
    }

    @FunctionalInterface
    interface TemplateSegmentAuthority {
        TemplateSegmentDisclosure disclose(String templateId);
    }

    private sealed interface PathSegment permits RootSegment, TemplateUseSegment,
            RepeatSegment, NodeSegment {
    }

    private record RootSegment(String templateId, long revision) implements PathSegment {
    }

    private record TemplateUseSegment(
            String useId,
            String templateId,
            long revision
    ) implements PathSegment {
    }

    private record RepeatSegment(String loopId, int inputIndex) implements PathSegment {
    }

    private record NodeSegment(Optional<String> nodeId, String role) implements PathSegment {
    }

    private record OccurrenceLocator(List<PathSegment> segments) {
    }

    private record AuthorizedPath(Optional<String> location, boolean sourceNodeVisible) {
    }

    private record ResourceLocator(
            String occurrenceId,
            String rootPropertyId,
            List<String> selectorSegments,
            Optional<String> bindingId,
            Optional<String> definitionId,
            String assetId
    ) {
    }

    private DiagnosticSidecar() {
    }

    static byte[] seal(
            List<BoundOccurrence> occurrences,
            List<Materializer.ResourceEntry> resources,
            RenderingPipelineCapacityGuard.RequestTracker capacity,
            EvaluationStageControl stageControl
    ) {
        Objects.requireNonNull(occurrences, "occurrences");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(capacity, "capacity");
        Objects.requireNonNull(stageControl, "stageControl");

        var occurrenceValues = new ArrayList<CanonicalJson.CanonicalValue>(occurrences.size());
        var occurrenceIdByPath = new HashMap<OccurrencePath, String>(occurrences.size());
        var occurrenceIds = new HashSet<String>(occurrences.size());
        for (var occurrence : occurrences) {
            stageControl.checkpoint();
            if (!occurrenceIds.add(occurrence.occurrenceId())
                    || occurrenceIdByPath.putIfAbsent(
                    occurrence.occurrencePath(), occurrence.occurrenceId()) != null) {
                throw new IllegalStateException("diagnostic occurrence locator collision");
            }
            var members = new java.util.TreeMap<String, CanonicalJson.CanonicalValue>();
            members.put("occurrenceId", CanonicalJson.stringValue(occurrence.occurrenceId()));
            members.put("occurrencePath", occurrence.occurrencePath().canonicalValue());
            if (occurrence.sourceNodeId() != null) {
                members.put("sourceNodeId", CanonicalJson.stringValue(occurrence.sourceNodeId()));
            }
            occurrenceValues.add(CanonicalJson.objectValue(members));
        }

        var resourceValues = new ArrayList<CanonicalJson.CanonicalValue>(resources.size());
        var resourceIds = new HashSet<String>(resources.size());
        for (var resource : resources) {
            stageControl.checkpoint();
            if (!resourceIds.add(resource.resourceId())) {
                throw new IllegalStateException("diagnostic resource locator collision");
            }
            var occurrenceId = occurrenceIdByPath.get(resource.occurrencePath());
            if (occurrenceId == null) {
                throw new IllegalStateException("resource locator has no sealed occurrence");
            }
            var members = new java.util.TreeMap<String, CanonicalJson.CanonicalValue>();
            members.put("assetId", CanonicalJson.stringValue(resource.assetId()));
            members.put("consumerPropertyRef", resource.consumerPropertyRef().canonicalValue());
            members.put("occurrenceId", CanonicalJson.stringValue(occurrenceId));
            members.put("resourceId", CanonicalJson.stringValue(resource.resourceId()));
            if (resource.diagnosticProvenance() != null) {
                members.put("bindingId", CanonicalJson.stringValue(
                        resource.diagnosticProvenance().bindingId()));
                if (resource.diagnosticProvenance().definitionId() != null) {
                    members.put("definitionId", CanonicalJson.stringValue(
                            resource.diagnosticProvenance().definitionId()));
                }
            }
            resourceValues.add(CanonicalJson.objectValue(members));
        }

        var envelope = CanonicalJson.objectValue(Map.of(
                "occurrences", CanonicalJson.arrayValue(occurrenceValues),
                "resources", CanonicalJson.arrayValue(resourceValues),
                "sidecarVersion", CanonicalJson.stringValue(SIDECAR_VERSION)));
        return CanonicalWriter.write(envelope, capacity, stageControl);
    }

    /**
     * Projects Engine-only opaque locators into an author-safe path. The sidecar is an internal
     * sealed handoff, but this boundary still validates its closed shape so corruption cannot
     * disclose an invented identity.
     */
    static Projection project(
            byte[] canonicalUtf8,
            Optional<String> occurrenceId,
            Optional<String> resourceId,
            TemplateSegmentAuthority segmentAuthority
    ) {
        return project(
                canonicalUtf8,
                occurrenceId,
                resourceId,
                segmentAuthority,
                AssetIdentityDisclosure.REDACTED);
    }

    static Projection project(
            byte[] canonicalUtf8,
            Optional<String> occurrenceId,
            Optional<String> resourceId,
            TemplateSegmentAuthority segmentAuthority,
            AssetIdentityDisclosure assetIdentityDisclosure
    ) {
        Objects.requireNonNull(canonicalUtf8, "canonicalUtf8");
        Objects.requireNonNull(occurrenceId, "occurrenceId");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(segmentAuthority, "segmentAuthority");
        Objects.requireNonNull(assetIdentityDisclosure, "assetIdentityDisclosure");
        if (occurrenceId.isEmpty() && resourceId.isEmpty()) {
            return new Projected(Optional.empty());
        }
        var parsed = RenderJsonParser.parse(
                canonicalUtf8,
                new RenderJsonParser.JsonBudget(
                        "diagnosticSidecar",
                        8_388_608,
                        128,
                        16,
                        25_000,
                        1_000_000,
                        4_096,
                        256));
        if (!(parsed instanceof RenderJsonParser.Parsed document)
                || !(document.value() instanceof RenderJson.ObjectValue root)) {
            return InvalidProjection.INSTANCE;
        }
        try {
            return project(
                    root,
                    occurrenceId,
                    resourceId,
                    segmentAuthority,
                    assetIdentityDisclosure);
        } catch (MalformedSidecar ignored) {
            return InvalidProjection.INSTANCE;
        }
    }

    private static Projection project(
            RenderJson.ObjectValue root,
            Optional<String> requestedOccurrenceId,
            Optional<String> requestedResourceId,
            TemplateSegmentAuthority segmentAuthority,
            AssetIdentityDisclosure assetIdentityDisclosure
    ) {
        requireMembers(root, Set.of("occurrences", "resources", "sidecarVersion"));
        if (!SIDECAR_VERSION.equals(text(root, "sidecarVersion"))) {
            throw new MalformedSidecar();
        }

        var occurrences = new HashMap<String, OccurrenceLocator>();
        for (var value : array(root, "occurrences").items()) {
            var occurrence = object(value);
            var keys = occurrence.members().keySet();
            if (!keys.equals(Set.of("occurrenceId", "occurrencePath"))
                    && !keys.equals(Set.of("occurrenceId", "occurrencePath", "sourceNodeId"))) {
                throw new MalformedSidecar();
            }
            var id = boundedText(occurrence, "occurrenceId");
            var sourceNodeId = occurrence.members().containsKey("sourceNodeId")
                    ? Optional.of(boundedText(occurrence, "sourceNodeId"))
                    : Optional.<String>empty();
            var locator = occurrenceLocator(
                    object(occurrence.members().get("occurrencePath")), sourceNodeId);
            if (occurrences.putIfAbsent(id, locator) != null) {
                throw new MalformedSidecar();
            }
        }

        var resources = new HashMap<String, ResourceLocator>();
        for (var value : array(root, "resources").items()) {
            var resource = object(value);
            var required = Set.of(
                    "assetId", "consumerPropertyRef", "occurrenceId", "resourceId");
            var allowed = Set.of(
                    "assetId", "bindingId", "consumerPropertyRef", "definitionId",
                    "occurrenceId", "resourceId");
            if (!resource.members().keySet().containsAll(required)
                    || !allowed.containsAll(resource.members().keySet())) {
                throw new MalformedSidecar();
            }
            var consumer = object(resource.members().get("consumerPropertyRef"));
            requireMembers(consumer, Set.of("rootPropertyId", "selectors"));
            var selectorSegments = selectorSegments(array(consumer, "selectors"));
            var locator = new ResourceLocator(
                    boundedText(resource, "occurrenceId"),
                    boundedText(consumer, "rootPropertyId"),
                    selectorSegments,
                    optionalUuid(resource, "bindingId"),
                    optionalUuid(resource, "definitionId"),
                    uuid(resource, "assetId"));
            if (locator.definitionId().isPresent() && locator.bindingId().isEmpty()) {
                throw new MalformedSidecar();
            }
            var id = boundedText(resource, "resourceId");
            if (!occurrences.containsKey(locator.occurrenceId())
                    || resources.putIfAbsent(id, locator) != null) {
                throw new MalformedSidecar();
            }
        }

        Optional<String> effectiveOccurrence = requestedOccurrenceId;
        ResourceLocator resource = null;
        if (requestedResourceId.isPresent()) {
            resource = resources.get(requestedResourceId.orElseThrow());
            if (resource == null
                    || (requestedOccurrenceId.isPresent()
                    && !requestedOccurrenceId.orElseThrow().equals(resource.occurrenceId()))) {
                throw new MalformedSidecar();
            }
            effectiveOccurrence = Optional.of(resource.occurrenceId());
        }
        if (effectiveOccurrence.isEmpty()
                || !occurrences.containsKey(effectiveOccurrence.orElseThrow())) {
            throw new MalformedSidecar();
        }
        var authorizedPath = authorizedPath(
                occurrences.get(effectiveOccurrence.orElseThrow()), segmentAuthority);
        if (authorizedPath.location().isEmpty()) {
            return new Projected(Optional.empty());
        }
        var location = new StringBuilder(authorizedPath.location().orElseThrow());
        if (resource != null && authorizedPath.sourceNodeVisible()) {
            resource.bindingId().ifPresent(bindingId -> location
                    .append("/bindings/").append(escapePointer(bindingId)));
            resource.definitionId().ifPresent(definitionId -> location
                    .append("/definitions/").append(escapePointer(definitionId)));
            location.append("/properties/").append(escapePointer(resource.rootPropertyId()));
            for (var selector : resource.selectorSegments()) {
                location.append('/').append(escapePointer(selector));
            }
            if (assetIdentityDisclosure == AssetIdentityDisclosure.READABLE) {
                location.append("/assets/").append(escapePointer(resource.assetId()));
            }
        }
        if (location.length() > 1024) {
            throw new MalformedSidecar();
        }
        return new Projected(Optional.of(location.toString()));
    }

    private static OccurrenceLocator occurrenceLocator(
            RenderJson.ObjectValue path,
            Optional<String> sourceNodeId
    ) {
        requireMembers(path, Set.of("pathVersion", "segments"));
        if (!OccurrencePath.PATH_VERSION.equals(text(path, "pathVersion"))) {
            throw new MalformedSidecar();
        }
        var values = array(path, "segments").items();
        if (values.size() < 2 || values.size() > 32) {
            throw new MalformedSidecar();
        }
        var segments = new ArrayList<PathSegment>(values.size());
        for (int index = 0; index < values.size(); index++) {
            var segment = object(values.get(index));
            var kind = text(segment, "kind");
            var parsed = switch (kind) {
                case "ROOT" -> {
                    requireMembers(segment, Set.of("kind", "revision", "templateId"));
                    if (index != 0) {
                        throw new MalformedSidecar();
                    }
                    yield new RootSegment(
                            boundedText(segment, "templateId"),
                            nonnegativeLong(segment, "revision"));
                }
                case "TEMPLATE_USE" -> {
                    requireMembers(segment, Set.of(
                            "kind", "revision", "templateId", "useId"));
                    if (index == 0 || index == values.size() - 1) {
                        throw new MalformedSidecar();
                    }
                    yield new TemplateUseSegment(
                            boundedText(segment, "useId"),
                            boundedText(segment, "templateId"),
                            nonnegativeLong(segment, "revision"));
                }
                case "REPEAT" -> {
                    requireMembers(segment, Set.of("inputIndex", "kind", "loopId"));
                    if (index == 0 || index == values.size() - 1) {
                        throw new MalformedSidecar();
                    }
                    yield new RepeatSegment(
                            boundedText(segment, "loopId"),
                            nonnegativeInt(segment, "inputIndex"));
                }
                case "NODE" -> {
                    if (index != values.size() - 1) {
                        throw new MalformedSidecar();
                    }
                    var keys = segment.members().keySet();
                    if (!keys.equals(Set.of("kind", "role"))
                            && !keys.equals(Set.of("kind", "nodeId", "role"))) {
                        throw new MalformedSidecar();
                    }
                    var role = boundedText(segment, "role");
                    var nodeId = segment.members().containsKey("nodeId")
                            ? Optional.of(boundedText(segment, "nodeId"))
                            : Optional.<String>empty();
                    var sourceRequired = switch (role) {
                        case "source-node", "repeat-container", "conditional-frame",
                                "template-use-viewport", "canvas-background" -> true;
                        case "repeat-item" -> false;
                        default -> throw new MalformedSidecar();
                    };
                    if (sourceRequired != nodeId.isPresent() || !nodeId.equals(sourceNodeId)) {
                        throw new MalformedSidecar();
                    }
                    yield new NodeSegment(nodeId, role);
                }
                default -> throw new MalformedSidecar();
            };
            segments.add(parsed);
        }
        if (!(segments.getFirst() instanceof RootSegment)
                || !(segments.getLast() instanceof NodeSegment)) {
            throw new MalformedSidecar();
        }
        return new OccurrenceLocator(List.copyOf(segments));
    }

    private static AuthorizedPath authorizedPath(
            OccurrenceLocator occurrence,
            TemplateSegmentAuthority authority
    ) {
        var location = new StringBuilder();
        var sourceNodeVisible = false;
        for (var segment : occurrence.segments()) {
            switch (segment) {
                case RootSegment root -> {
                    if (authority.disclose(root.templateId()) != TemplateSegmentDisclosure.READABLE) {
                        return new AuthorizedPath(Optional.empty(), false);
                    }
                    location.append("/templates/")
                            .append(escapePointer(root.templateId()))
                            .append("/revisions/")
                            .append(root.revision());
                }
                case TemplateUseSegment use -> {
                    location.append("/uses/").append(escapePointer(use.useId()));
                    if (authority.disclose(use.templateId())
                            != TemplateSegmentDisclosure.READABLE) {
                        return boundedAuthorizedPath(location, false);
                    }
                    location.append("/templates/")
                            .append(escapePointer(use.templateId()))
                            .append("/revisions/")
                            .append(use.revision());
                }
                case RepeatSegment repeat -> location.append("/repeats/")
                        .append(escapePointer(repeat.loopId()))
                        .append("/items/")
                        .append(repeat.inputIndex());
                case NodeSegment node -> {
                    if (node.nodeId().isPresent()) {
                        location.append("/nodes/")
                                .append(escapePointer(node.nodeId().orElseThrow()));
                        sourceNodeVisible = true;
                    }
                }
            }
            if (location.length() > 1024) {
                throw new MalformedSidecar();
            }
        }
        return boundedAuthorizedPath(location, sourceNodeVisible);
    }

    private static AuthorizedPath boundedAuthorizedPath(
            StringBuilder location,
            boolean sourceNodeVisible
    ) {
        if (location.isEmpty() || location.length() > 1024) {
            throw new MalformedSidecar();
        }
        return new AuthorizedPath(Optional.of(location.toString()), sourceNodeVisible);
    }

    private static List<String> selectorSegments(RenderJson.ArrayValue selectors) {
        if (selectors.items().size() > 2) {
            throw new MalformedSidecar();
        }
        var result = new ArrayList<String>(selectors.items().size());
        var kinds = new HashSet<String>();
        for (var value : selectors.items()) {
            var selector = object(value);
            var kind = text(selector, "kind");
            if (!kinds.add(kind)) {
                throw new MalformedSidecar();
            }
            switch (kind) {
                case "INDEX" -> {
                    requireMembers(selector, Set.of("index", "kind"));
                    var index = selector.members().get("index");
                    if (!(index instanceof RenderJson.NumberValue number)) {
                        throw new MalformedSidecar();
                    }
                    try {
                        var parsed = Integer.parseInt(number.rawToken());
                        if (parsed < 0 || !Integer.toString(parsed).equals(number.rawToken())) {
                            throw new MalformedSidecar();
                        }
                        result.add(Integer.toString(parsed));
                    } catch (NumberFormatException invalid) {
                        throw new MalformedSidecar();
                    }
                }
                case "MEMBER" -> {
                    requireMembers(selector, Set.of("kind", "memberId"));
                    result.add(boundedText(selector, "memberId"));
                }
                default -> throw new MalformedSidecar();
            }
        }
        return List.copyOf(result);
    }

    private static RenderJson.ObjectValue object(RenderJson value) {
        if (value instanceof RenderJson.ObjectValue object) {
            return object;
        }
        throw new MalformedSidecar();
    }

    private static RenderJson.ArrayValue array(RenderJson.ObjectValue object, String member) {
        var value = object.members().get(member);
        if (value instanceof RenderJson.ArrayValue array) {
            return array;
        }
        throw new MalformedSidecar();
    }

    private static String text(RenderJson.ObjectValue object, String member) {
        var value = object.members().get(member);
        if (value instanceof RenderJson.StringValue text) {
            return text.value();
        }
        throw new MalformedSidecar();
    }

    private static String boundedText(RenderJson.ObjectValue object, String member) {
        var value = text(object, member);
        if (value.isBlank() || value.length() > 256) {
            throw new MalformedSidecar();
        }
        return value;
    }

    private static String uuid(RenderJson.ObjectValue object, String member) {
        var value = boundedText(object, member);
        if (!UUID_V4.matcher(value).matches()) {
            throw new MalformedSidecar();
        }
        return value;
    }

    private static Optional<String> optionalUuid(
            RenderJson.ObjectValue object,
            String member
    ) {
        return object.members().containsKey(member)
                ? Optional.of(uuid(object, member))
                : Optional.empty();
    }

    private static int nonnegativeInt(RenderJson.ObjectValue object, String member) {
        var value = nonnegativeLong(object, member);
        if (value > Integer.MAX_VALUE) {
            throw new MalformedSidecar();
        }
        return (int) value;
    }

    private static long nonnegativeLong(RenderJson.ObjectValue object, String member) {
        var value = object.members().get(member);
        if (!(value instanceof RenderJson.NumberValue number)) {
            throw new MalformedSidecar();
        }
        try {
            var parsed = Long.parseLong(number.rawToken());
            if (parsed < 0 || !Long.toString(parsed).equals(number.rawToken())) {
                throw new MalformedSidecar();
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new MalformedSidecar();
        }
    }

    private static void requireMembers(RenderJson.ObjectValue object, Set<String> expected) {
        if (!object.members().keySet().equals(expected)) {
            throw new MalformedSidecar();
        }
    }

    private static String escapePointer(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static final class MalformedSidecar extends RuntimeException {
    }

    /** Bytes are reserved before retention; a failed prefix is unreachable to the caller. */
    private static final class CanonicalWriter implements CanonicalJson.Utf8Sink {
        private static final int CHUNK_BYTES = 64 * 1024;

        private final RenderingPipelineCapacityGuard.RequestTracker capacity;
        private final EvaluationStageControl stageControl;
        private final List<byte[]> fullChunks = new ArrayList<>();
        private byte[] currentChunk = new byte[CHUNK_BYTES];
        private int currentLength;
        private int totalLength;

        private CanonicalWriter(
                RenderingPipelineCapacityGuard.RequestTracker capacity,
                EvaluationStageControl stageControl
        ) {
            this.capacity = capacity;
            this.stageControl = stageControl;
        }

        static byte[] write(
                CanonicalJson.CanonicalValue value,
                RenderingPipelineCapacityGuard.RequestTracker capacity,
                EvaluationStageControl stageControl
        ) {
            var writer = new CanonicalWriter(capacity, stageControl);
            value.writeTo(writer);
            return writer.commit();
        }

        @Override
        public void writeUtf8(String canonicalText) {
            stageControl.checkpoint();
            var byteLength = CanonicalJson.utf8Length(canonicalText);
            var problem = capacity.reserve(
                    RenderingPipelineCapacityGuard.Limit.DIAGNOSTICS_SIDECAR_BYTES,
                    byteLength);
            if (problem.isPresent()) {
                throw new CapacityExceeded(problem.orElseThrow());
            }
            var encoded = canonicalText.getBytes(StandardCharsets.UTF_8);
            if (encoded.length != byteLength) {
                throw new IllegalStateException("UTF-8 byte count drift");
            }
            append(encoded);
        }

        private void append(byte[] encoded) {
            var offset = 0;
            while (offset < encoded.length) {
                if (currentLength == currentChunk.length) {
                    fullChunks.add(currentChunk);
                    currentChunk = new byte[CHUNK_BYTES];
                    currentLength = 0;
                }
                var copied = Math.min(
                        encoded.length - offset, currentChunk.length - currentLength);
                System.arraycopy(encoded, offset, currentChunk, currentLength, copied);
                offset += copied;
                currentLength += copied;
                totalLength += copied;
            }
        }

        private byte[] commit() {
            var canonical = new byte[totalLength];
            var offset = 0;
            for (var chunk : fullChunks) {
                System.arraycopy(chunk, 0, canonical, offset, chunk.length);
                offset += chunk.length;
            }
            System.arraycopy(currentChunk, 0, canonical, offset, currentLength);
            return canonical;
        }
    }

    static final class CapacityExceeded extends RuntimeException {
        private final RenderingProblem problem;

        CapacityExceeded(RenderingProblem problem) {
            this.problem = Objects.requireNonNull(problem, "problem");
        }

        RenderingProblem problem() {
            return problem;
        }
    }
}
