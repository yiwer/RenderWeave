package cn.hbads.renderweave.template.api;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.util.Objects;
import java.util.OptionalLong;

/** Authoring interface for the Template aggregate. */
public interface TemplateApplication {

    CreateOutcome create(TemplateInvocationRef invocation, CreateCommand command);

    CurrentOutcome getCurrent(TemplateInvocationRef invocation, TemplateId templateId);

    SaveOutcome save(TemplateInvocationRef invocation, SaveCommand command);

    record TemplateInvocationRef(String value) {
        public TemplateInvocationRef {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(
                        "invocation must be non-blank and at most 256 characters"
                );
            }
        }

        public static TemplateInvocationRef serverCreated(String value) {
            return new TemplateInvocationRef(value);
        }
    }

    record TemplateId(String value) {
        public TemplateId {
            if (value == null || value.isBlank() || value.length() > 128) {
                throw new IllegalArgumentException(
                        "templateId must be non-blank and at most 128 characters"
                );
            }
        }

        public static TemplateId of(String value) {
            return new TemplateId(value);
        }
    }

    final class CreateCommand {
        private final StaticSchemaRef staticSchema;
        private final byte[] rawDesignDslUtf8;

        public CreateCommand(StaticSchemaRef staticSchema, byte[] rawDesignDslUtf8) {
            this.staticSchema = Objects.requireNonNull(staticSchema, "staticSchema");
            this.rawDesignDslUtf8 = Objects.requireNonNull(
                    rawDesignDslUtf8,
                    "rawDesignDslUtf8"
            ).clone();
        }

        public StaticSchemaRef staticSchema() {
            return staticSchema;
        }

        public byte[] rawDesignDslUtf8() {
            return rawDesignDslUtf8.clone();
        }
    }

    final class Current {
        private final TemplateId templateId;
        private final long revision;
        private final StaticSchemaRef staticSchema;
        private final byte[] canonicalDesignDslUtf8;
        private final String contentHash;
        private final Readiness readiness;

        public Current(
                TemplateId templateId,
                long revision,
                StaticSchemaRef staticSchema,
                byte[] canonicalDesignDslUtf8,
                String contentHash,
                Readiness readiness
        ) {
            this.templateId = Objects.requireNonNull(templateId, "templateId");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            this.revision = revision;
            this.staticSchema = Objects.requireNonNull(staticSchema, "staticSchema");
            this.canonicalDesignDslUtf8 = Objects.requireNonNull(
                    canonicalDesignDslUtf8,
                    "canonicalDesignDslUtf8"
            ).clone();
            this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
            this.readiness = Objects.requireNonNull(readiness, "readiness");
        }

        public TemplateId templateId() {
            return templateId;
        }

        public long revision() {
            return revision;
        }

        public StaticSchemaRef staticSchema() {
            return staticSchema;
        }

        public byte[] canonicalDesignDslUtf8() {
            return canonicalDesignDslUtf8.clone();
        }

        public String contentHash() {
            return contentHash;
        }

        public Readiness readiness() {
            return readiness;
        }
    }

    final class SaveCommand {
        private final TemplateId templateId;
        private final long expectedRevision;
        private final byte[] rawDesignDslUtf8;

        public SaveCommand(
                TemplateId templateId,
                long expectedRevision,
                byte[] rawDesignDslUtf8
        ) {
            this.templateId = Objects.requireNonNull(templateId, "templateId");
            if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "expectedRevision must be non-negative and have a successor"
                );
            }
            this.expectedRevision = expectedRevision;
            this.rawDesignDslUtf8 = Objects.requireNonNull(
                    rawDesignDslUtf8,
                    "rawDesignDslUtf8"
            ).clone();
        }

        public TemplateId templateId() {
            return templateId;
        }

        public long expectedRevision() {
            return expectedRevision;
        }

        public byte[] rawDesignDslUtf8() {
            return rawDesignDslUtf8.clone();
        }
    }

    enum Readiness {
        READY,
        INVALID,
        STALE
    }

    sealed interface CreateOutcome permits
            CreatedReadable,
            CreatedOpaque,
            CreateDesignRejected,
            CreateStaticSchemaNotFound,
            CreateForbidden,
            CreateAuthorityUnavailable,
            CreatePersistenceUnavailable {
    }

    record CreatedReadable(Current current) implements CreateOutcome {
        public CreatedReadable {
            Objects.requireNonNull(current, "current");
        }
    }

    record CreatedOpaque(TemplateId templateId) implements CreateOutcome {
        public CreatedOpaque {
            Objects.requireNonNull(templateId, "templateId");
        }
    }

    record CreateDesignRejected(DesignDslAuthority.Rejected rejection)
            implements CreateOutcome {
        public CreateDesignRejected {
            Objects.requireNonNull(rejection, "rejection");
        }
    }

    record CreateStaticSchemaNotFound() implements CreateOutcome {
    }

    record CreateForbidden() implements CreateOutcome {
    }

    record CreateAuthorityUnavailable() implements CreateOutcome {
    }

    record CreatePersistenceUnavailable() implements CreateOutcome {
    }

    sealed interface CurrentOutcome permits
            CurrentReadable,
            CurrentNotFound,
            CurrentDeleted,
            CurrentIntegrityMismatch,
            CurrentAuthorityUnavailable,
            CurrentPersistenceUnavailable {
    }

    record CurrentReadable(Current current) implements CurrentOutcome {
        public CurrentReadable {
            Objects.requireNonNull(current, "current");
        }
    }

    record CurrentNotFound() implements CurrentOutcome {
    }

    record CurrentDeleted() implements CurrentOutcome {
    }

    record CurrentIntegrityMismatch() implements CurrentOutcome {
    }

    record CurrentAuthorityUnavailable() implements CurrentOutcome {
    }

    record CurrentPersistenceUnavailable() implements CurrentOutcome {
    }

    sealed interface SaveOutcome permits
            SavedReadable,
            SavedOpaque,
            SaveDesignRejected,
            SaveNotFound,
            SaveForbidden,
            SaveDeleted,
            SaveRevisionConflict,
            SaveIntegrityMismatch,
            SaveAuthorityUnavailable,
            SavePersistenceUnavailable {
    }

    record SavedReadable(Current current) implements SaveOutcome {
        public SavedReadable {
            Objects.requireNonNull(current, "current");
        }
    }

    record SavedOpaque(TemplateId templateId) implements SaveOutcome {
        public SavedOpaque {
            Objects.requireNonNull(templateId, "templateId");
        }
    }

    record SaveDesignRejected(DesignDslAuthority.Rejected rejection) implements SaveOutcome {
        public SaveDesignRejected {
            Objects.requireNonNull(rejection, "rejection");
        }
    }

    record SaveNotFound() implements SaveOutcome {
    }

    record SaveForbidden() implements SaveOutcome {
    }

    record SaveDeleted() implements SaveOutcome {
    }

    record SaveRevisionConflict(OptionalLong currentRevision) implements SaveOutcome {
        public SaveRevisionConflict {
            Objects.requireNonNull(currentRevision, "currentRevision");
        }
    }

    record SaveIntegrityMismatch() implements SaveOutcome {
    }

    record SaveAuthorityUnavailable() implements SaveOutcome {
    }

    record SavePersistenceUnavailable() implements SaveOutcome {
    }

}
