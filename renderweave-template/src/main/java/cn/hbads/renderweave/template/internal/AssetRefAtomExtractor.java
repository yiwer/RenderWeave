package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.TemplateDependencyProjection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts the current-only dependency projection from an admitted DesignDSL canonical
 * document (ticket 09 §213, ticket 12 §118): every authored AssetRef atom and every
 * TemplateUse logical TemplateRef occurrence, each with its canonical JSON pointer.
 *
 * <p>The closed contract makes this exact: in an admitted document an object whose member
 * set is exactly {@code {assetId}} with a canonical UUID v4 is always an AssetRef value.
 * Kind (imageRef/fontRef) comes from the hosting member name or the typing valueType
 * (literal sources, mapping operands, custom defaults, asset-ref list items). TemplateUse
 * occurrences come from {@code kind:"templateUse" + templateRef.templateId}. Walk order is
 * the canonical tree order, so the output is deterministic.
 */
final class AssetRefAtomExtractor {

    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );
    private static final Set<String> ASSET_REF_MEMBERS = Set.of("assetId");
    private static final Set<String> ASSET_KIND_MEMBERS = Set.of("imageRef", "fontRef");

    private final StrictJsonParser parser = new StrictJsonParser();
    private final ArrayList<TemplateDependencyProjection.AssetRefAtom> atoms = new ArrayList<>();
    private final ArrayList<TemplateDependencyProjection.TemplateUseOccurrence> uses =
            new ArrayList<>();

    TemplateDependencyProjection extract(byte[] canonicalUtf8) {
        atoms.clear();
        uses.clear();
        try {
            walk(parser.parse(canonicalUtf8), "", null);
        } catch (DesignDslFailureException impossible) {
            // Inputs are already-admitted canonical documents; parse failure is an invariant fault.
            throw new IllegalStateException("admitted canonical DesignDSL failed to parse", impossible);
        }
        return new TemplateDependencyProjection(atoms, uses);
    }

    private void walk(JsonValue value, String pointer, String typedAssetKind) {
        if (value instanceof JsonValue.ObjectValue object) {
            for (var memberName : ASSET_KIND_MEMBERS) {
                if (object.members().get(memberName) instanceof JsonValue.ObjectValue ref
                        && isAssetRef(ref)) {
                    atoms.add(new TemplateDependencyProjection.AssetRefAtom(
                            assetId(ref), memberName, pointer + "/" + memberName));
                }
            }
            var typedKind = typedAssetKind(object);
            if (typedKind != null) {
                for (var memberName : Set.of("value", "defaultValue")) {
                    var memberValue = object.members().get(memberName);
                    if (memberValue instanceof JsonValue.ObjectValue ref && isAssetRef(ref)) {
                        atoms.add(new TemplateDependencyProjection.AssetRefAtom(
                                assetId(ref), typedKind, pointer + "/" + memberName));
                    } else if (memberValue instanceof JsonValue.ArrayValue array) {
                        for (int index = 0; index < array.items().size(); index++) {
                            if (array.items().get(index) instanceof JsonValue.ObjectValue item
                                    && isAssetRef(item)) {
                                atoms.add(new TemplateDependencyProjection.AssetRefAtom(
                                        assetId(item), typedKind,
                                        pointer + "/" + memberName + "/" + index));
                            }
                        }
                    }
                }
            }
            if (object.members().get("kind") instanceof JsonValue.StringValue kind
                    && "templateUse".equals(kind.value())
                    && object.members().get("templateRef") instanceof JsonValue.ObjectValue ref
                    && ref.members().get("templateId") instanceof JsonValue.StringValue target) {
                if (!(object.members().get("useId") instanceof JsonValue.StringValue useId)) {
                    // Admitted TemplateUse nodes always carry a useId (ticket 19 atom);
                    // its absence in an admitted canonical document is an invariant fault.
                    throw new IllegalStateException("admitted templateUse is missing useId");
                }
                uses.add(new TemplateDependencyProjection.TemplateUseOccurrence(
                        target.value(), useId.value(), pointer + "/templateRef/templateId"));
            }
            for (var entry : object.members().entrySet()) {
                walk(entry.getValue(), pointer + "/" + escape(entry.getKey()), null);
            }
        } else if (value instanceof JsonValue.ArrayValue array) {
            for (int index = 0; index < array.items().size(); index++) {
                walk(array.items().get(index), pointer + "/" + index, null);
            }
        }
    }

    /** The asset kind that types this object's {@code value}/{@code defaultValue} members. */
    private String typedAssetKind(JsonValue.ObjectValue object) {
        var valueType = object.members().get("valueType");
        if (valueType instanceof JsonValue.StringValue token
                && ASSET_KIND_MEMBERS.contains(token.value())) {
            return token.value();
        }
        if (valueType instanceof JsonValue.ObjectValue derived
                && derived.members().get("type") instanceof JsonValue.StringValue type
                && "list".equals(type.value())
                && derived.members().get("items") instanceof JsonValue.StringValue items
                && ASSET_KIND_MEMBERS.contains(items.value())) {
            return items.value();
        }
        return null;
    }

    private boolean isAssetRef(JsonValue.ObjectValue object) {
        if (!object.members().keySet().equals(ASSET_REF_MEMBERS)) {
            return false;
        }
        return object.members().get("assetId") instanceof JsonValue.StringValue assetId
                && UUID_V4.matcher(assetId.value()).matches();
    }

    private String assetId(JsonValue.ObjectValue ref) {
        return ((JsonValue.StringValue) ref.members().get("assetId")).value();
    }

    private String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
