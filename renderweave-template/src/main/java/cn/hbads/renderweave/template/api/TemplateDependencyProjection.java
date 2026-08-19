package cn.hbads.renderweave.template.api;

import java.util.List;
import java.util.Objects;

/**
 * Current-only dependency projection of one admitted DesignDSL: every authored AssetRef
 * atom (imageRef/fontRef, including defaults, literals and asset-ref list items) and every
 * TemplateUse logical TemplateRef occurrence, each with its canonical JSON pointer.
 * Historical revisions never participate; the projection is replaced wholesale whenever
 * the Template current changes (CONTEXT "Template dependency projection").
 */
public final class TemplateDependencyProjection {

    private final List<AssetRefAtom> assetAtoms;
    private final List<TemplateUseOccurrence> templateUses;

    public TemplateDependencyProjection(
            List<AssetRefAtom> assetAtoms,
            List<TemplateUseOccurrence> templateUses
    ) {
        this.assetAtoms = List.copyOf(Objects.requireNonNull(assetAtoms, "assetAtoms"));
        this.templateUses = List.copyOf(Objects.requireNonNull(templateUses, "templateUses"));
    }

    public static final TemplateDependencyProjection EMPTY =
            new TemplateDependencyProjection(List.of(), List.of());

    public List<AssetRefAtom> assetAtoms() {
        return assetAtoms;
    }

    public List<TemplateUseOccurrence> templateUses() {
        return templateUses;
    }

    /** One authored AssetRef value; {@code kind} is {@code imageRef} or {@code fontRef}. */
    public record AssetRefAtom(String assetId, String kind, String canonicalPointer) {
        public AssetRefAtom {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(canonicalPointer, "canonicalPointer");
        }
    }

    /** One authored TemplateUse logical TemplateRef; resolves to the target's current. */
    public record TemplateUseOccurrence(String targetTemplateId, String canonicalPointer) {
        public TemplateUseOccurrence {
            Objects.requireNonNull(targetTemplateId, "targetTemplateId");
            Objects.requireNonNull(canonicalPointer, "canonicalPointer");
        }
    }
}
