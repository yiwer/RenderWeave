package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.RenderingProblem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Capped RenderDocument canonical UTF-8 writer. Bytes are admitted before retention; callers only
 * receive one immutable complete array after the canonical value has been written successfully.
 */
final class RenderDocumentCanonicalWriter implements CanonicalJson.Utf8Sink {

    private static final int CHUNK_BYTES = 64 * 1024;

    private final RenderingPipelineCapacityGuard.RequestTracker capacity;
    private final List<byte[]> fullChunks = new ArrayList<>();
    private byte[] currentChunk = new byte[CHUNK_BYTES];
    private int currentLength;
    private int totalLength;
    private int jsonDepth;

    private RenderDocumentCanonicalWriter(
            RenderingPipelineCapacityGuard.RequestTracker capacity
    ) {
        this.capacity = Objects.requireNonNull(capacity, "capacity");
    }

    static byte[] write(
            CanonicalJson.CanonicalValue value,
            RenderingPipelineCapacityGuard.RequestTracker capacity
    ) {
        Objects.requireNonNull(value, "value");
        var writer = new RenderDocumentCanonicalWriter(capacity);
        value.writeTo(writer);
        return writer.commit();
    }

    @Override
    public void writeUtf8(String canonicalText) {
        var byteLength = utf8Length(canonicalText);
        var problem = capacity.reserve(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_CANONICAL_BYTES,
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

    @Override
    public void beginContainer() {
        var nextDepth = (long) jsonDepth + 1;
        var problem = capacity.observeMaximum(
                RenderingPipelineCapacityGuard.Limit.RENDER_DOCUMENT_JSON_DEPTH,
                nextDepth);
        if (problem.isPresent()) {
            throw new CapacityExceeded(problem.orElseThrow());
        }
        jsonDepth++;
    }

    @Override
    public void endContainer() {
        if (jsonDepth == 0) {
            throw new IllegalStateException("canonical JSON container depth underflow");
        }
        jsonDepth--;
    }

    private static int utf8Length(String value) {
        var length = 0;
        for (int index = 0; index < value.length(); index++) {
            var current = value.charAt(index);
            if (current <= 0x7F) {
                length++;
            } else if (current <= 0x7FF) {
                length += 2;
            } else if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                length += 4;
                index++;
            } else if (Character.isSurrogate(current)) {
                // StandardCharsets.UTF_8 replaces an unpaired surrogate with one ASCII byte.
                length++;
            } else {
                length += 3;
            }
        }
        return length;
    }

    private void append(byte[] encoded) {
        var offset = 0;
        while (offset < encoded.length) {
            if (currentLength == currentChunk.length) {
                fullChunks.add(currentChunk);
                currentChunk = new byte[CHUNK_BYTES];
                currentLength = 0;
            }
            var copied = Math.min(encoded.length - offset, currentChunk.length - currentLength);
            System.arraycopy(encoded, offset, currentChunk, currentLength, copied);
            offset += copied;
            currentLength += copied;
            totalLength += copied;
        }
    }

    private byte[] commit() {
        if (jsonDepth != 0) {
            throw new IllegalStateException("canonical JSON container depth is unbalanced");
        }
        var canonical = new byte[totalLength];
        var offset = 0;
        for (var chunk : fullChunks) {
            System.arraycopy(chunk, 0, canonical, offset, chunk.length);
            offset += chunk.length;
        }
        System.arraycopy(currentChunk, 0, canonical, offset, currentLength);
        return canonical;
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
