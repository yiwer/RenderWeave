package cn.hbads.renderweave.rendering.internal;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Closed locator for one concrete AssetRef property occurrence. Its shape is shared with the
 * frozen TargetPropertyRef grammar, without requiring a BindingPolicy.
 */
record ConsumerPropertyRef(String rootPropertyId, List<Selector> selectors) {

    sealed interface Selector permits IndexSelector, MemberSelector {
        CanonicalJson.CanonicalValue canonicalValue();
    }

    record IndexSelector(int index) implements Selector {
        IndexSelector {
            if (index < 0) {
                throw new IllegalArgumentException("consumer property index must be nonnegative");
            }
        }

        @Override
        public CanonicalJson.CanonicalValue canonicalValue() {
            return CanonicalJson.objectValue(Map.of(
                    "index", CanonicalJson.decimalValue(BigDecimal.valueOf(index)),
                    "kind", CanonicalJson.stringValue("INDEX")));
        }
    }

    record MemberSelector(String memberId) implements Selector {
        MemberSelector {
            requireText(memberId, "memberId");
        }

        @Override
        public CanonicalJson.CanonicalValue canonicalValue() {
            return CanonicalJson.objectValue(Map.of(
                    "kind", CanonicalJson.stringValue("MEMBER"),
                    "memberId", CanonicalJson.stringValue(memberId)));
        }
    }

    ConsumerPropertyRef {
        requireText(rootPropertyId, "rootPropertyId");
        selectors = List.copyOf(Objects.requireNonNull(selectors, "selectors"));
        if (selectors.size() > 2) {
            throw new IllegalArgumentException("consumer property selectors exceed two");
        }
        var selectorKinds = new HashSet<Class<?>>();
        for (var selector : selectors) {
            Objects.requireNonNull(selector, "selector");
            if (!selectorKinds.add(selector.getClass())) {
                throw new IllegalArgumentException("consumer property selector kind repeats");
            }
        }
    }

    static ConsumerPropertyRef root(String rootPropertyId) {
        return new ConsumerPropertyRef(rootPropertyId, List.of());
    }

    static ConsumerPropertyRef of(String rootPropertyId, List<Selector> selectors) {
        return new ConsumerPropertyRef(rootPropertyId, selectors);
    }

    CanonicalJson.CanonicalValue canonicalValue() {
        return CanonicalJson.objectValue(Map.of(
                "rootPropertyId", CanonicalJson.stringValue(rootPropertyId),
                "selectors", CanonicalJson.arrayValue(
                        selectors.stream().map(Selector::canonicalValue).toList())));
    }

    String canonicalJson() {
        return CanonicalJson.encode(canonicalValue());
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
