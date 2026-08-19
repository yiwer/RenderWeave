package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;

final class FailClosedOwnerScopeAuthority implements OwnerScopeAuthority {
    @Override
    public CreateDecision authorizeCreate(TemplateApplication.TemplateInvocationRef invocation) {
        return new CreateUnavailable();
    }

    @Override
    public ExistingDecision authorizeExisting(
            TemplateApplication.TemplateInvocationRef invocation,
            OwnerScope storedOwnerScope,
            ExistingOperation operation
    ) {
        return new ExistingUnavailable();
    }

    @Override
    public RecheckDecision recheck(RecheckIdentity identity) {
        return new RecheckUnavailable();
    }
}
