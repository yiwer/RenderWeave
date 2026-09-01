package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.RenderingApplication;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderCommand;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderInvocationRef;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderOutcome;

import java.util.Objects;

/** A type-separated application seam that cannot replace the formal Rendering bean. */
final class CandidatePreviewApplication {

    private final RenderingApplication delegate;

    CandidatePreviewApplication(RenderingApplication delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    RenderOutcome render(RenderInvocationRef invocation, RenderCommand command) {
        return delegate.render(invocation, command);
    }
}
