package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.RenderingProblem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical request-local diagnostic locator aggregate. Raw bytes never cross the Rendering
 * public Interface and are discarded with the evaluation operation.
 */
final class DiagnosticSidecar {

    static final String SIDECAR_VERSION = "renderweave-diagnostic-sidecar/1.0";

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
            resourceValues.add(CanonicalJson.objectValue(Map.of(
                    "consumerPropertyRef",
                    resource.consumerPropertyRef().canonicalValue(),
                    "occurrenceId", CanonicalJson.stringValue(occurrenceId),
                    "resourceId", CanonicalJson.stringValue(resource.resourceId()))));
        }

        var envelope = CanonicalJson.objectValue(Map.of(
                "occurrences", CanonicalJson.arrayValue(occurrenceValues),
                "resources", CanonicalJson.arrayValue(resourceValues),
                "sidecarVersion", CanonicalJson.stringValue(SIDECAR_VERSION)));
        return CanonicalWriter.write(envelope, capacity, stageControl);
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
