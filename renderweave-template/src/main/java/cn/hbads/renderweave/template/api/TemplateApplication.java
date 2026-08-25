package cn.hbads.renderweave.template.api;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Authoring interface for the Template aggregate. */
public interface TemplateApplication {

    CreateOutcome create(TemplateInvocationRef invocation, CreateCommand command);

    CatalogOutcome catalog(TemplateInvocationRef invocation, CatalogCommand command);

    CurrentOutcome getCurrent(TemplateInvocationRef invocation, TemplateId templateId);

    RecheckCurrentOutcome recheckCurrent(
            TemplateInvocationRef invocation,
            TemplateId templateId
    );

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

    final class CatalogCommand {
        private final String search;
        private final String cursor;
        private final int limit;

        public CatalogCommand(String search, String cursor, int limit) {
            var normalizedSearch = search == null ? null : search.trim();
            if (normalizedSearch != null && normalizedSearch.isEmpty()) {
                normalizedSearch = null;
            }
            if (normalizedSearch != null && normalizedSearch.length() > 200) {
                throw new IllegalArgumentException("search must be at most 200 characters");
            }
            if (cursor != null && (cursor.isBlank() || cursor.length() > 2048)) {
                throw new IllegalArgumentException(
                        "cursor must be non-blank and at most 2048 characters"
                );
            }
            if (limit < 1 || limit > 50) {
                throw new IllegalArgumentException("limit must be between 1 and 50");
            }
            this.search = normalizedSearch;
            this.cursor = cursor;
            this.limit = limit;
        }

        public String search() {
            return search;
        }

        public String cursor() {
            return cursor;
        }

        public int limit() {
            return limit;
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
        private final String confirmationToken;

        public SaveCommand(
                TemplateId templateId,
                long expectedRevision,
                byte[] rawDesignDslUtf8
        ) {
            this(templateId, expectedRevision, rawDesignDslUtf8, null);
        }

        public SaveCommand(
                TemplateId templateId,
                long expectedRevision,
                byte[] rawDesignDslUtf8,
                String confirmationToken
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
            if (confirmationToken != null
                    && !confirmationToken.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "confirmationToken must be 64 lowercase hexadecimal characters"
                );
            }
            this.confirmationToken = confirmationToken;
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

        public Optional<String> confirmationToken() {
            return Optional.ofNullable(confirmationToken);
        }
    }

    enum Readiness {
        READY,
        INVALID,
        STALE
    }

    enum ProblemCategory {
        DEPENDENCY,
        HARD,
        LIMIT
    }

    enum ProblemSeverity {
        ERROR
    }

    record ValidationProblem(
            String code,
            ProblemCategory category,
            ProblemSeverity severity,
            String canonicalPointer,
            List<String> messageArgs
    ) {
        public ValidationProblem {
            if (code == null || code.isBlank() || code.length() > 128) {
                throw new IllegalArgumentException(
                        "problem code must be non-blank and at most 128 characters"
                );
            }
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(severity, "severity");
            if (canonicalPointer == null || canonicalPointer.length() > 2048) {
                throw new IllegalArgumentException(
                        "canonicalPointer must be present and at most 2048 characters"
                );
            }
            messageArgs = List.copyOf(Objects.requireNonNull(messageArgs, "messageArgs"));
            if (messageArgs.size() > 32 || messageArgs.stream().anyMatch(arg ->
                    arg == null || arg.length() > 512)) {
                throw new IllegalArgumentException("messageArgs exceed the closed problem bound");
            }
        }
    }

    record ValidationReport(
            List<ValidationProblem> problems,
            boolean truncated,
            String fingerprint
    ) {
        public ValidationReport {
            problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
            if (problems.size() > 200) {
                throw new IllegalArgumentException("validation report exceeds 200 problems");
            }
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "problem fingerprint must be 64 lowercase hexadecimal characters"
                );
            }
        }

        public boolean confirmable() {
            return !truncated
                    && !problems.isEmpty()
                    && problems.stream().allMatch(problem ->
                    problem.category() == ProblemCategory.DEPENDENCY);
        }
    }

    record InvalidCommitConfirmationOffer(
            String confirmationToken,
            Instant expiresAt,
            String proposedContentHash,
            ValidationReport report
    ) {
        public InvalidCommitConfirmationOffer {
            if (confirmationToken == null
                    || !confirmationToken.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "confirmationToken must be 64 lowercase hexadecimal characters"
                );
            }
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (proposedContentHash == null
                    || !proposedContentHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "proposedContentHash must use the sha256 wire format"
                );
            }
            Objects.requireNonNull(report, "report");
            if (!report.confirmable()) {
                throw new IllegalArgumentException("offer report must be confirmable");
            }
        }
    }

    sealed interface CreateOutcome permits
            CreatedReadable,
            CreatedOpaque,
            CreateDesignRejected,
            CreateDependencyRejected,
            CreateStaticSchemaNotFound,
            CreateForbidden,
            CreateAuthorityUnavailable,
            CreateDependencyUnavailable,
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

    record CreateDependencyRejected(ValidationReport report) implements CreateOutcome {
        public CreateDependencyRejected {
            Objects.requireNonNull(report, "report");
        }
    }

    record CreateStaticSchemaNotFound() implements CreateOutcome {
    }

    record CreateForbidden() implements CreateOutcome {
    }

    record CreateAuthorityUnavailable() implements CreateOutcome {
    }

    record CreateDependencyUnavailable() implements CreateOutcome {
    }

    record CreatePersistenceUnavailable() implements CreateOutcome {
    }

    record CatalogEntry(
            TemplateId templateId,
            String displayName,
            StaticSchemaRef staticSchema,
            long revision,
            Readiness readiness,
            Instant updatedAt
    ) {
        public CatalogEntry {
            Objects.requireNonNull(templateId, "templateId");
            if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
                throw new IllegalArgumentException(
                        "displayName must be non-blank and at most 200 characters"
                );
            }
            Objects.requireNonNull(staticSchema, "staticSchema");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            Objects.requireNonNull(readiness, "readiness");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    sealed interface CatalogOutcome permits
            CatalogPage,
            CatalogForbidden,
            CatalogInvalidCursor,
            CatalogAuthorityUnavailable,
            CatalogPersistenceUnavailable {
    }

    record CatalogPage(List<CatalogEntry> entries, Optional<String> nextCursor)
            implements CatalogOutcome {
        public CatalogPage {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        }
    }

    record CatalogForbidden() implements CatalogOutcome {
    }

    record CatalogInvalidCursor() implements CatalogOutcome {
    }

    record CatalogAuthorityUnavailable() implements CatalogOutcome {
    }

    record CatalogPersistenceUnavailable() implements CatalogOutcome {
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

    sealed interface RecheckCurrentOutcome permits
            CurrentRechecked,
            RecheckCurrentNotFound,
            RecheckCurrentDeleted,
            RecheckCurrentIntegrityMismatch,
            RecheckCurrentAuthorityUnavailable,
            RecheckCurrentDependencyUnavailable,
            RecheckCurrentPersistenceUnavailable,
            RecheckCurrentDrifted {
    }

    record CurrentRechecked(Current current) implements RecheckCurrentOutcome {
        public CurrentRechecked {
            Objects.requireNonNull(current, "current");
        }
    }

    record RecheckCurrentNotFound() implements RecheckCurrentOutcome {
    }

    record RecheckCurrentDeleted() implements RecheckCurrentOutcome {
    }

    record RecheckCurrentIntegrityMismatch() implements RecheckCurrentOutcome {
    }

    record RecheckCurrentAuthorityUnavailable() implements RecheckCurrentOutcome {
    }

    record RecheckCurrentDependencyUnavailable() implements RecheckCurrentOutcome {
    }

    record RecheckCurrentPersistenceUnavailable() implements RecheckCurrentOutcome {
    }

    record RecheckCurrentDrifted() implements RecheckCurrentOutcome {
    }

    sealed interface SaveOutcome permits
            SavedReadable,
            SavedOpaque,
            SaveDesignRejected,
            SaveConfirmationRequired,
            SaveDependencyRejected,
            SaveConfirmationInvalid,
            SaveConfirmationExpired,
            SaveConfirmationStale,
            SaveNotFound,
            SaveForbidden,
            SaveDeleted,
            SaveRevisionConflict,
            SaveIntegrityMismatch,
            SaveAuthorityUnavailable,
            SaveDependencyUnavailable,
            SaveConfirmationUnavailable,
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

    record SaveConfirmationRequired(InvalidCommitConfirmationOffer offer)
            implements SaveOutcome {
        public SaveConfirmationRequired {
            Objects.requireNonNull(offer, "offer");
        }
    }

    record SaveDependencyRejected(ValidationReport report) implements SaveOutcome {
        public SaveDependencyRejected {
            Objects.requireNonNull(report, "report");
        }
    }

    record SaveConfirmationInvalid() implements SaveOutcome {
    }

    record SaveConfirmationExpired() implements SaveOutcome {
    }

    record SaveConfirmationStale(Optional<InvalidCommitConfirmationOffer> replacement)
            implements SaveOutcome {
        public SaveConfirmationStale {
            Objects.requireNonNull(replacement, "replacement");
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

    record SaveDependencyUnavailable() implements SaveOutcome {
    }

    record SaveConfirmationUnavailable() implements SaveOutcome {
    }

    record SavePersistenceUnavailable() implements SaveOutcome {
    }

}
