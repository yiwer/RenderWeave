package cn.hbads.renderweave.rendering.spi;

import cn.hbads.renderweave.rendering.api.Evaluator.OwnerScope;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderInvocationRef;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderPurpose;
import cn.hbads.renderweave.template.api.TemplateApplication.TemplateId;

import java.util.Objects;

/** Rendering-owned Host capability facet; request DTOs never carry these facts. */
public interface RenderingAuthority {

    AuthorizationDecision authorize(
            RenderInvocationRef invocation,
            TemplateId rootTemplateId,
            RenderPurpose purpose
    );

    RecheckDecision recheck(RecheckIdentity identity);

    sealed interface AuthorizationDecision permits Authorized, Hidden, Forbidden, Unavailable {
    }

    record Authorized(
            OwnerScope ownerScope,
            RecheckIdentity recheckIdentity,
            Disclosure disclosure
    ) implements AuthorizationDecision {
        public Authorized {
            Objects.requireNonNull(ownerScope, "ownerScope");
            Objects.requireNonNull(recheckIdentity, "recheckIdentity");
            Objects.requireNonNull(disclosure, "disclosure");
        }
    }

    record Hidden() implements AuthorizationDecision {
    }

    record Forbidden() implements AuthorizationDecision {
    }

    record Unavailable() implements AuthorizationDecision {
    }

    sealed interface RecheckDecision permits
            RecheckGranted,
            RecheckHidden,
            RecheckForbidden,
            RecheckUnavailable {
    }

    record RecheckGranted(Disclosure disclosure) implements RecheckDecision {
        public RecheckGranted {
            Objects.requireNonNull(disclosure, "disclosure");
        }
    }

    record RecheckHidden() implements RecheckDecision {
    }

    record RecheckForbidden() implements RecheckDecision {
    }

    record RecheckUnavailable() implements RecheckDecision {
    }

    record RecheckIdentity(String value) {
        public RecheckIdentity {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(
                        "recheckIdentity must be non-blank and at most 256 characters");
            }
        }
    }

    enum Disclosure {
        READABLE,
        OPAQUE
    }
}
