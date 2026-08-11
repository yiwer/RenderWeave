package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateEvidence;
import cn.hbads.renderweave.inference.candidate.CandidateEvidenceKind;
import cn.hbads.renderweave.inference.candidate.CandidateBoundingBox;
import cn.hbads.renderweave.schema.identity.FieldKey;
import cn.hbads.renderweave.schema.identity.SchemaKey;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class VisualAnalysisValidation {
    static final int MAX_ELEMENTS = 128;
    static final int MAX_ENTITIES = 32;
    static final int MAX_RELATIONSHIPS = 31;
    static final int MAX_EVIDENCE_PER_ITEM = 8;
    static final int MAX_TREE_DEPTH = 16;
    private static final Pattern LOCAL_ID = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");
    private static final Pattern ARTIFACT_ID = Pattern.compile("^[a-f0-9]{64}$");

    private VisualAnalysisValidation() { }

    static String localId(String value, String name) {
        if (value == null || !LOCAL_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match ^[a-z][a-z0-9-]{0,62}$");
        }
        return value;
    }

    static String schemaKey(String value) {
        SchemaKey.userProvided(value);
        return value;
    }

    static String fieldKey(String value) {
        FieldKey.of(value);
        return value;
    }

    static String displayName(String value, String name) {
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > 256
                || value.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.CONTROL)) {
            throw new IllegalArgumentException(name + " must be readable bounded text");
        }
        return value;
    }

    static List<String> localIds(List<String> values, String name, int maximum) {
        values = List.copyOf(java.util.Objects.requireNonNull(values, name));
        if (values.isEmpty() || values.size() > maximum) {
            throw new IllegalArgumentException(name + " must contain 1.." + maximum + " items");
        }
        var unique = new HashSet<String>();
        for (var value : values) {
            localId(value, name);
            if (!unique.add(value)) throw new IllegalArgumentException(name + " must be unique");
        }
        return values;
    }

    static List<CandidateEvidence> imageEvidence(List<CandidateEvidence> values, String name) {
        values = List.copyOf(java.util.Objects.requireNonNull(values, name));
        if (values.isEmpty() || values.size() > MAX_EVIDENCE_PER_ITEM) {
            throw new IllegalArgumentException(name + " must contain direct image evidence");
        }
        var unique = new HashSet<CandidateEvidence>();
        for (var evidence : values) {
            if (evidence == null || evidence.kind() != CandidateEvidenceKind.IMAGE
                    || evidence.artifactId() == null
                    || !ARTIFACT_ID.matcher(evidence.artifactId()).matches()
                    || evidence.boundingBox() == null
                    || evidence.sampleIndex() != null || evidence.jsonPointer() != null) {
                throw new IllegalArgumentException(name + " must contain canonical IMAGE evidence");
            }
            canonicalBox(evidence.boundingBox(), name);
            if (!unique.add(evidence)) throw new IllegalArgumentException(name + " must not repeat evidence");
        }
        return values;
    }

    static CandidateBoundingBox canonicalBox(CandidateBoundingBox box, String name) {
        if (box == null || box.left() < 0 || box.top() < 0
                || box.right() > 10_000 || box.bottom() > 10_000
                || box.left() >= box.right() || box.top() >= box.bottom()) {
            throw new IllegalArgumentException(name + " bounding boxes must use 0..10000 coordinates");
        }
        return box;
    }

    static void requireKnownArtifacts(List<CandidateEvidence> evidence, Set<String> artifactIds) {
        for (var item : evidence) {
            if (!artifactIds.contains(item.artifactId())) {
                throw new IllegalArgumentException("Visual evidence references an unknown artifact");
            }
        }
    }
}

