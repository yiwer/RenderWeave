package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderInvocationRef;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderPurpose;
import cn.hbads.renderweave.rendering.spi.RenderingAuthority;
import cn.hbads.renderweave.template.api.TemplateApplication.TemplateId;

final class FailClosedRenderingAuthority implements RenderingAuthority {

    @Override
    public AuthorizationDecision authorize(
            RenderInvocationRef invocation,
            TemplateId rootTemplateId,
            RenderPurpose purpose
    ) {
        return new Unavailable();
    }

    @Override
    public RecheckDecision recheck(RecheckIdentity identity) {
        return new RecheckUnavailable();
    }

    @Override
    public DiagnosticSegmentDisclosure discloseDiagnosticSegment(
            RecheckIdentity identity,
            TemplateId templateId
    ) {
        return DiagnosticSegmentDisclosure.REDACTED;
    }
}
