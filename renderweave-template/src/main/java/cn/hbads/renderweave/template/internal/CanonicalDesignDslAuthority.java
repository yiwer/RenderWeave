package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class CanonicalDesignDslAuthority implements DesignDslAuthority {

    private static final byte[] HASH_DOMAIN =
            "renderweave-design-content/1\0".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> ROOT_MEMBERS = Set.of(
            "dslVersion", "expressionProfile", "displayName", "description",
            "definitions", "designRoot"
    );
    private static final Set<String> CANVAS_MEMBERS = Set.of(
            "nodeId", "kind", "displayName", "widthMm", "heightMm", "backgroundColor",
            "bleed", "bindings", "children"
    );
    private static final Set<String> BLEED_MEMBERS = Set.of(
            "topMm", "rightMm", "bottomMm", "leftMm"
    );
    private static final List<String> BLEED_MEMBER_ORDER = List.of(
            "topMm", "rightMm", "bottomMm", "leftMm"
    );
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    );
    private static final Pattern RGBA = Pattern.compile("^#[0-9A-F]{8}$");

    private final StrictJsonParser parser = new StrictJsonParser();
    private final CanonicalJsonWriter writer = new CanonicalJsonWriter();

    @Override
    public Admission admit(byte[] rawUtf8) {
        try {
            var parsed = parser.parse(rawUtf8);
            var normalized = validateAndNormalize(parsed);
            var canonical = writer.write(normalized);
            return new Admitted(canonical, contentHash(canonical));
        } catch (CanonicalJsonWriter.CanonicalLimitException limit) {
            return new Rejected(
                    FailureCode.DESIGN_DSL_LIMIT_EXCEEDED,
                    FailureStage.DESIGN_CANONICAL_COUNT,
                    "",
                    Optional.of(Limit.CANONICAL_BYTES)
            );
        } catch (DesignDslFailureException failure) {
            return failure.rejection();
        }
    }

    private JsonValue validateAndNormalize(JsonValue parsed) throws DesignDslFailureException {
        rejectNull(parsed, "");
        var root = object(parsed, "");
        rejectUnknown(root, ROOT_MEMBERS, "");
        exactVersion(root, "dslVersion", "renderweave-design/1.0", "/dslVersion");
        exactVersion(
                root,
                "expressionProfile",
                "renderweave-expression/1.0",
                "/expressionProfile"
        );
        var displayName = metadata(root, "displayName", 128, false, "/displayName");
        var definitions = array(required(root, "definitions", "/definitions"), "/definitions");
        if (!definitions.items().isEmpty()) {
            throw failure(
                    FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED,
                    "/definitions"
            );
        }

        var canvas = object(required(root, "designRoot", "/designRoot"), "/designRoot");
        rejectUnknown(canvas, CANVAS_MEMBERS, "/designRoot");
        var kind = string(required(canvas, "kind", "/designRoot/kind"), "/designRoot/kind");
        if (!"canvas".equals(kind)) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, "/designRoot/kind");
        }
        var nodeId = string(required(canvas, "nodeId", "/designRoot/nodeId"),
                "/designRoot/nodeId");
        if (!UUID_V4.matcher(nodeId).matches()) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, "/designRoot/nodeId");
        }
        positiveDecimal(canvas, "widthMm", "/designRoot/widthMm");
        positiveDecimal(canvas, "heightMm", "/designRoot/heightMm");
        if (canvas.members().containsKey("backgroundColor")) {
            var color = string(
                    canvas.members().get("backgroundColor"),
                    "/designRoot/backgroundColor"
            );
            if (!RGBA.matcher(color).matches()) {
                throw failure(
                        FailureCode.DESIGN_VALUE_INVALID,
                        "/designRoot/backgroundColor"
                );
            }
        }
        if (canvas.members().containsKey("bleed")) {
            var bleed = object(canvas.members().get("bleed"), "/designRoot/bleed");
            rejectUnknown(bleed, BLEED_MEMBERS, "/designRoot/bleed");
            for (var member : BLEED_MEMBER_ORDER) {
                nonNegativeDecimal(
                        bleed,
                        member,
                        "/designRoot/bleed/" + member
                );
            }
        }
        var bindings = array(required(canvas, "bindings", "/designRoot/bindings"),
                "/designRoot/bindings");
        var children = array(required(canvas, "children", "/designRoot/children"),
                "/designRoot/children");
        if (!bindings.items().isEmpty()) {
            throw failure(FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED, "/designRoot/bindings");
        }
        if (!children.items().isEmpty()) {
            throw failure(FailureCode.DESIGN_KERNEL_SCOPE_UNSUPPORTED, "/designRoot/children");
        }

        var normalizedCanvas = new LinkedHashMap<>(canvas.members());
        if (canvas.members().containsKey("displayName")) {
            normalizedCanvas.put(
                    "displayName",
                    new JsonValue.StringValue(metadata(
                            canvas, "displayName", 128, false, "/designRoot/displayName"
                    ))
            );
        }
        var normalizedRoot = new LinkedHashMap<>(root.members());
        normalizedRoot.put("displayName", new JsonValue.StringValue(displayName));
        if (root.members().containsKey("description")) {
            var description = metadata(root, "description", 2048, true, "/description");
            if (description.isEmpty()) {
                normalizedRoot.remove("description");
            } else {
                normalizedRoot.put("description", new JsonValue.StringValue(description));
            }
        }
        normalizedRoot.put("designRoot", new JsonValue.ObjectValue(normalizedCanvas));
        return new JsonValue.ObjectValue(normalizedRoot);
    }

    private void rejectNull(JsonValue value, String pointer) throws DesignDslFailureException {
        switch (value) {
            case JsonValue.NullValue ignored ->
                    throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            case JsonValue.ObjectValue object -> {
                for (var entry : object.members().entrySet()) {
                    rejectNull(entry.getValue(), pointer + "/" + escape(entry.getKey()));
                }
            }
            case JsonValue.ArrayValue array -> {
                for (int index = 0; index < array.items().size(); index++) {
                    rejectNull(array.items().get(index), pointer + "/" + index);
                }
            }
            default -> {
            }
        }
    }

    private void rejectUnknown(
            JsonValue.ObjectValue object,
            Set<String> allowed,
            String pointer
    ) throws DesignDslFailureException {
        for (var name : object.members().keySet()) {
            if (!allowed.contains(name)) {
                throw failure(FailureCode.DESIGN_MEMBER_UNKNOWN, pointer + "/" + escape(name));
            }
        }
    }

    private void exactVersion(
            JsonValue.ObjectValue object,
            String name,
            String expected,
            String pointer
    ) throws DesignDslFailureException {
        var actual = string(required(object, name, pointer), pointer);
        if (!expected.equals(actual)) {
            throw failure(FailureCode.DESIGN_VERSION_UNSUPPORTED, pointer);
        }
    }

    private String metadata(
            JsonValue.ObjectValue object,
            String name,
            int maximumCodePoints,
            boolean blankMayDisappear,
            String pointer
    ) throws DesignDslFailureException {
        var value = string(required(object, name, pointer), pointer).trim();
        var length = value.codePointCount(0, value.length());
        if ((!blankMayDisappear && length == 0) || length > maximumCodePoints) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
        return value;
    }

    private void positiveDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = required(object, name, pointer);
        if (!(value instanceof JsonValue.NumberValue number)) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        try {
            if (new BigDecimal(number.token()).signum() <= 0) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            }
        } catch (NumberFormatException exception) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private void nonNegativeDecimal(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = required(object, name, pointer);
        if (!(value instanceof JsonValue.NumberValue number)) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        try {
            if (new BigDecimal(number.token()).signum() < 0) {
                throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
            }
        } catch (NumberFormatException exception) {
            throw failure(FailureCode.DESIGN_VALUE_INVALID, pointer);
        }
    }

    private JsonValue required(
            JsonValue.ObjectValue object,
            String name,
            String pointer
    ) throws DesignDslFailureException {
        var value = object.members().get(name);
        if (value == null) {
            throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
        }
        return value;
    }

    private JsonValue.ObjectValue object(JsonValue value, String pointer)
            throws DesignDslFailureException {
        if (value instanceof JsonValue.ObjectValue object) {
            return object;
        }
        throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
    }

    private JsonValue.ArrayValue array(JsonValue value, String pointer)
            throws DesignDslFailureException {
        if (value instanceof JsonValue.ArrayValue array) {
            return array;
        }
        throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
    }

    private String string(JsonValue value, String pointer) throws DesignDslFailureException {
        if (value instanceof JsonValue.StringValue string) {
            return string.value();
        }
        throw failure(FailureCode.DESIGN_STRUCTURE_INVALID, pointer);
    }

    private DesignDslFailureException failure(FailureCode code, String pointer) {
        return new DesignDslFailureException(new Rejected(
                code,
                FailureStage.DESIGN_SEMANTIC_VALIDATION,
                pointer,
                Optional.empty()
        ));
    }

    private String contentHash(byte[] canonical) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(HASH_DOMAIN);
            digest.update(canonical);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
