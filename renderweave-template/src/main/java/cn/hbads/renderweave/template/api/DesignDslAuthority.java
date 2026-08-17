package cn.hbads.renderweave.template.api;

import java.util.Objects;
import java.util.Optional;

public interface DesignDslAuthority {

    Admission admit(byte[] rawUtf8);

    sealed interface Admission permits Admitted, Rejected {
    }

    final class Admitted implements Admission {
        private final byte[] canonicalUtf8;
        private final String contentHash;

        public Admitted(byte[] canonicalUtf8, String contentHash) {
            this.canonicalUtf8 = Objects.requireNonNull(canonicalUtf8, "canonicalUtf8").clone();
            this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
        }

        public byte[] canonicalUtf8() {
            return canonicalUtf8.clone();
        }

        public String contentHash() {
            return contentHash;
        }
    }

    record Rejected(
            FailureCode code,
            FailureStage stage,
            String pointer,
            Optional<Limit> limit
    ) implements Admission {
        public Rejected {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(pointer, "pointer");
            limit = Objects.requireNonNull(limit, "limit");
        }
    }

    enum FailureCode {
        DESIGN_UTF8_INVALID,
        DESIGN_JSON_INVALID,
        DESIGN_DUPLICATE_MEMBER,
        DESIGN_DSL_LIMIT_EXCEEDED,
        DESIGN_VERSION_UNSUPPORTED,
        DESIGN_MEMBER_UNKNOWN,
        DESIGN_STRUCTURE_INVALID,
        DESIGN_VALUE_INVALID,
        DESIGN_KERNEL_SCOPE_UNSUPPORTED
    }

    enum FailureStage {
        DESIGN_PARSE,
        DESIGN_SEMANTIC_VALIDATION,
        DESIGN_CANONICAL_COUNT
    }

    enum Limit {
        RAW_UTF8_BYTES("designDslParser.rawUtf8Bytes"),
        CANONICAL_BYTES("designDslParser.canonicalBytes"),
        JSON_DEPTH("designDslParser.jsonDepth"),
        OBJECT_MEMBERS("designDslParser.objectMembers"),
        ARRAY_ITEMS("designDslParser.arrayItems"),
        TOTAL_VALUES_AND_CONTAINERS("designDslParser.totalValuesAndContainers"),
        STRING_UTF8_BYTES("designDslParser.stringUtf8Bytes"),
        MEMBER_NAME_UTF8_BYTES("designDslParser.memberNameUtf8Bytes"),
        NUMBER_TOKEN_BYTES("designDslParser.numberTokenBytes");

        private final String id;

        Limit(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }
}
