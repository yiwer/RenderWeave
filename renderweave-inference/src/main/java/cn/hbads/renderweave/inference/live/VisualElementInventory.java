package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.candidate.CandidateEvidence;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

record VisualElementInventory(
        String contractVersion,
        List<VisualElement> elements
) {
    static final String VERSION = "renderweave-visual-elements/1.0";

    VisualElementInventory {
        if (!VERSION.equals(contractVersion)) throw new IllegalArgumentException("Unsupported visual element contract");
        elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
        if (elements.isEmpty() || elements.size() > VisualAnalysisValidation.MAX_ELEMENTS) {
            throw new IllegalArgumentException("Visual element inventory must contain 1..128 items");
        }
        var ids = new HashSet<String>();
        for (var element : elements) {
            if (!ids.add(element.elementId())) throw new IllegalArgumentException("Visual element ids must be unique");
        }
    }

    void requireKnownArtifacts(Set<String> artifactIds) {
        artifactIds = Set.copyOf(Objects.requireNonNull(artifactIds, "artifactIds"));
        if (artifactIds.isEmpty()) throw new IllegalArgumentException("IMAGE_ONLY requires an image artifact");
        for (var element : elements) {
            VisualAnalysisValidation.requireKnownArtifacts(element.evidence(), artifactIds);
        }
    }

    VisualElement requireElement(String elementId) {
        return elements.stream().filter(element -> element.elementId().equals(elementId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Visual plan references an unknown element"));
    }
}

record VisualElement(
        String elementId,
        VisualElementKind kind,
        String proposedKey,
        String displayName,
        VisualMultiplicity multiplicity,
        VisualValueHint valueHint,
        List<CandidateEvidence> evidence
) {
    VisualElement {
        elementId = VisualAnalysisValidation.localId(elementId, "elementId");
        Objects.requireNonNull(kind, "kind");
        proposedKey = VisualAnalysisValidation.fieldKey(proposedKey);
        displayName = VisualAnalysisValidation.displayName(displayName, "displayName");
        Objects.requireNonNull(multiplicity, "multiplicity");
        if ((kind == VisualElementKind.SLOT) != (valueHint != null)) {
            throw new IllegalArgumentException("Only SLOT elements carry a value hint");
        }
        evidence = VisualAnalysisValidation.imageEvidence(evidence, "evidence");
    }
}

enum VisualElementKind {
    SLOT,
    GROUP
}

enum VisualMultiplicity {
    ONE,
    MANY
}

enum VisualValueHint {
    TEXT,
    DECIMAL,
    DATE,
    TIME,
    BOOLEAN,
    UNRESOLVED
}

