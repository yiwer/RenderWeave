package cn.hbads.renderweave.template.spi;

import cn.hbads.renderweave.template.api.TemplateApplication;

import java.util.Objects;

/** Template-owned Host capability seam. Request DTOs never carry these facts. */
public interface OwnerScopeAuthority {

    CreateDecision authorizeCreate(TemplateApplication.TemplateInvocationRef invocation);

    ExistingDecision authorizeExisting(
            TemplateApplication.TemplateInvocationRef invocation,
            OwnerScope storedOwnerScope,
            ExistingOperation operation
    );

    RecheckDecision recheck(RecheckIdentity identity);

    sealed interface CreateDecision permits CreateGranted, CreateDenied, CreateUnavailable {
    }

    record CreateGranted(
            OwnerScope ownerScope,
            RecheckIdentity recheckIdentity,
            Disclosure disclosure
    ) implements CreateDecision {
        public CreateGranted {
            Objects.requireNonNull(ownerScope, "ownerScope");
            Objects.requireNonNull(recheckIdentity, "recheckIdentity");
            Objects.requireNonNull(disclosure, "disclosure");
        }
    }

    record CreateDenied() implements CreateDecision {
    }

    record CreateUnavailable() implements CreateDecision {
    }

    sealed interface ExistingDecision permits
            ExistingGranted,
            ExistingHidden,
            ExistingForbidden,
            ExistingUnavailable {
    }

    record ExistingGranted(
            Disclosure disclosure,
            RecheckIdentity recheckIdentity,
            String actorId
    ) implements ExistingDecision {
        public ExistingGranted {
            Objects.requireNonNull(disclosure, "disclosure");
            Objects.requireNonNull(recheckIdentity, "recheckIdentity");
            if (actorId == null || actorId.isBlank() || actorId.length() > 256) {
                throw new IllegalArgumentException(
                        "actorId must be non-blank and at most 256 characters"
                );
            }
        }
    }

    record ExistingHidden() implements ExistingDecision {
    }

    record ExistingForbidden() implements ExistingDecision {
    }

    record ExistingUnavailable() implements ExistingDecision {
    }

    sealed interface RecheckDecision permits RecheckGranted, RecheckDenied, RecheckUnavailable {
    }

    record RecheckGranted() implements RecheckDecision {
    }

    record RecheckDenied() implements RecheckDecision {
    }

    record RecheckUnavailable() implements RecheckDecision {
    }

    record OwnerScope(String value) {
        public OwnerScope {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(
                        "ownerScope must be non-blank and at most 256 characters"
                );
            }
        }
    }

    record RecheckIdentity(String value) {
        public RecheckIdentity {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(
                        "recheckIdentity must be non-blank and at most 256 characters"
                );
            }
        }
    }

    enum Disclosure {
        READABLE,
        OPAQUE
    }

    enum ExistingOperation {
        READ,
        UPDATE
    }

}
