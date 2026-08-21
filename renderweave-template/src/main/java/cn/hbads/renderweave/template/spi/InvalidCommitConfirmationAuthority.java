package cn.hbads.renderweave.template.spi;

import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.template.api.TemplateApplication;

import java.time.Instant;
import java.util.Objects;

/** Issues and verifies opaque invalid-commit confirmations against exact fresh claims. */
public interface InvalidCommitConfirmationAuthority {

    IssueOutcome issue(Claims claims);

    VerifyOutcome verify(String confirmationToken, Claims expectedClaims);

    record Claims(
            Operation operation,
            String actorId,
            OwnerScopeAuthority.OwnerScope ownerScope,
            TemplateApplication.TemplateId templateId,
            long expectedRevision,
            StaticSchemaRef staticSchema,
            String contentHash,
            String problemFingerprint,
            String dependencySnapshotFingerprint
    ) {
        public Claims {
            Objects.requireNonNull(operation, "operation");
            if (actorId == null || actorId.isBlank() || actorId.length() > 256) {
                throw new IllegalArgumentException("actorId must be non-blank and bounded");
            }
            Objects.requireNonNull(ownerScope, "ownerScope");
            Objects.requireNonNull(templateId, "templateId");
            if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "expectedRevision must be non-negative and have a successor"
                );
            }
            Objects.requireNonNull(staticSchema, "staticSchema");
            if (contentHash == null || !contentHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("contentHash must use the sha256 wire format");
            }
            requireDigest(problemFingerprint, "problemFingerprint");
            requireDigest(dependencySnapshotFingerprint, "dependencySnapshotFingerprint");
        }
    }

    enum Operation {
        SAVE
    }

    sealed interface IssueOutcome permits Issued, IssueUnavailable {
    }

    record Issued(String confirmationToken, Instant expiresAt) implements IssueOutcome {
        public Issued {
            if (confirmationToken == null
                    || !confirmationToken.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "confirmationToken must be 64 lowercase hexadecimal characters"
                );
            }
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    record IssueUnavailable() implements IssueOutcome {
    }

    sealed interface VerifyOutcome permits
            Verified,
            Invalid,
            Expired,
            Stale,
            VerifyUnavailable {
    }

    record Verified() implements VerifyOutcome {
    }

    record Invalid() implements VerifyOutcome {
    }

    record Expired() implements VerifyOutcome {
    }

    record Stale() implements VerifyOutcome {
    }

    record VerifyUnavailable() implements VerifyOutcome {
    }

    private static void requireDigest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be 64 lowercase hexadecimal characters"
            );
        }
    }
}
