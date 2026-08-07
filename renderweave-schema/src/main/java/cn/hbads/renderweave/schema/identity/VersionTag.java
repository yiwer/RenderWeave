package cn.hbads.renderweave.schema.identity;

import java.util.Objects;
import java.util.regex.Pattern;

/** User-authored, opaque version component of a StaticSchema identity. */
public final class VersionTag {

    private static final Pattern SYNTAX = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    private final String value;

    private VersionTag(String value) {
        if (value == null || !SYNTAX.matcher(value).matches()) {
            throw new InvalidVersionTagException(
                    "versionTag must match ^[a-z0-9][a-z0-9._-]{0,63}$"
            );
        }
        this.value = value;
    }

    public static VersionTag of(String value) {
        return new VersionTag(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof VersionTag that && value.equals(that.value);
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
