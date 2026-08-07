package cn.hbads.renderweave.schema.identity;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identity of a Draft and the schema part of a StaticSchema identity. */
public final class SchemaKey {

    private static final Pattern SYNTAX = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    private static final String SYSTEM_PREFIX = "system-";

    private final String value;

    private SchemaKey(String value) {
        this.value = requireSyntax(value);
    }

    public static SchemaKey userProvided(String value) {
        var key = new SchemaKey(value);
        if (key.value.startsWith(SYSTEM_PREFIX)) {
            throw new InvalidSchemaKeyException("schemaKey prefix 'system-' is reserved");
        }
        return key;
    }

    public static SchemaKey systemProvided(String value) {
        var key = new SchemaKey(value);
        if (!key.value.startsWith(SYSTEM_PREFIX)) {
            throw new InvalidSchemaKeyException("system schemaKey must start with 'system-'");
        }
        return key;
    }

    public String value() {
        return value;
    }

    private static String requireSyntax(String value) {
        if (value == null || !SYNTAX.matcher(value).matches()) {
            throw new InvalidSchemaKeyException(
                    "schemaKey must match ^[a-z0-9][a-z0-9-]{0,62}$"
            );
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SchemaKey that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
