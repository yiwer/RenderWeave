package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.rendering.api.Evaluator.OutputSelection;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderInvocationRef;
import cn.hbads.renderweave.rendering.api.RenderingApplication.RenderPurpose;
import cn.hbads.renderweave.rendering.spi.RendererProfileAuthority;
import cn.hbads.renderweave.rendering.spi.RenderingAuthority;
import cn.hbads.renderweave.schema.definition.StaticSchemaRef;
import cn.hbads.renderweave.schema.identity.SchemaKey;
import cn.hbads.renderweave.schema.identity.VersionTag;
import cn.hbads.renderweave.template.api.TemplateApplication.TemplateId;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import cn.hbads.renderweave.template.spi.TemplatePersistence;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RenderingAuthorityAdapterTest {

    private static final RenderInvocationRef INVOCATION =
            RenderInvocationRef.serverCreated("render-authority-test");
    private static final TemplateId TEMPLATE =
            TemplateId.of("00000000-0000-4000-8000-0000000000a1");
    private static final StaticSchemaRef SCHEMA = new StaticSchemaRef(
            SchemaKey.systemProvided("system-empty"),
            VersionTag.of("v1"));

    @Test
    void missingHostAndProfileAdaptersFailClosed() {
        var authority = new FailClosedRenderingAuthority();

        assertThat(authority.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.FORMAL_OUTPUT))
                .isInstanceOf(RenderingAuthority.Unavailable.class);
        assertThat(authority.recheck(new RenderingAuthority.RecheckIdentity("missing")))
                .isInstanceOf(RenderingAuthority.RecheckUnavailable.class);
        assertThat(new FailClosedRendererProfileAuthority().select(
                OutputSelection.defaultPng()))
                .isInstanceOf(RendererProfileAuthority.Unavailable.class);
    }

    @Test
    void formalOutputCanRemainOpaqueButPreviewRequiresReadAndRender() {
        var renderOnly = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.render"),
                activeTemplates("owner-a"));

        var formal = (RenderingAuthority.Authorized) renderOnly.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.FORMAL_OUTPUT);
        assertThat(formal.disclosure()).isEqualTo(RenderingAuthority.Disclosure.OPAQUE);
        assertThat(renderOnly.recheck(formal.recheckIdentity()))
                .isInstanceOf(RenderingAuthority.RecheckGranted.class);
        assertThat(renderOnly.recheck(formal.recheckIdentity()))
                .isInstanceOf(RenderingAuthority.RecheckUnavailable.class);
        assertThat(renderOnly.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.AUTHORITATIVE_PREVIEW))
                .isInstanceOf(RenderingAuthority.Hidden.class);

        var readOnly = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.read"),
                activeTemplates("owner-a"));
        assertThat(readOnly.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.FORMAL_OUTPUT))
                .isInstanceOf(RenderingAuthority.Forbidden.class);
        assertThat(readOnly.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.AUTHORITATIVE_PREVIEW))
                .isInstanceOf(RenderingAuthority.Forbidden.class);

        var preview = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.read", "template.render"),
                activeTemplates("owner-a"));
        var granted = (RenderingAuthority.Authorized) preview.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.AUTHORITATIVE_PREVIEW);
        assertThat(granted.disclosure()).isEqualTo(RenderingAuthority.Disclosure.READABLE);
    }

    @Test
    void developmentAdapterAcceptsOnlyTheClosedTemplateCapabilityCatalog() {
        new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of(
                        "template.read",
                        "template.create",
                        "template.update",
                        "template.delete",
                        "template.render"),
                activeTemplates("owner-a"));

        assertThatThrownBy(() -> new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.admin"),
                activeTemplates("owner-a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown Template single-owner capability");
    }

    @Test
    void rejectedOperationsCannotGrowTheRecheckStoreWithoutBound() {
        var authority = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.render"),
                activeTemplates("owner-a"));
        var oldest = ((RenderingAuthority.Authorized) authority.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.FORMAL_OUTPUT)).recheckIdentity();
        RenderingAuthority.RecheckIdentity newest = null;
        for (int index = 0; index < 4096; index++) {
            newest = ((RenderingAuthority.Authorized) authority.authorize(
                    INVOCATION,
                    TEMPLATE,
                    RenderPurpose.FORMAL_OUTPUT)).recheckIdentity();
        }

        assertThat(authority.recheck(oldest))
                .isInstanceOf(RenderingAuthority.RecheckUnavailable.class);
        assertThat(authority.recheck(newest))
                .isInstanceOf(RenderingAuthority.RecheckGranted.class);
    }

    @Test
    void targetScopeLifecycleAndRecheckDriftStayHidden() {
        var templates = mock(TemplatePersistence.class);
        when(templates.locate(TEMPLATE))
                .thenReturn(located("owner-a", TemplatePersistence.Lifecycle.ACTIVE))
                .thenReturn(located("owner-a", TemplatePersistence.Lifecycle.DELETED));
        var authority = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.read", "template.render"),
                templates);

        var granted = (RenderingAuthority.Authorized) authority.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.AUTHORITATIVE_PREVIEW);

        assertThat(authority.recheck(granted.recheckIdentity()))
                .isInstanceOf(RenderingAuthority.RecheckHidden.class);

        var crossScope = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.read", "template.render"),
                activeTemplates("owner-b"));
        assertThat(crossScope.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.FORMAL_OUTPUT))
                .isInstanceOf(RenderingAuthority.Hidden.class);
    }

    private static TemplatePersistence activeTemplates(String ownerScope) {
        var templates = mock(TemplatePersistence.class);
        when(templates.locate(TEMPLATE)).thenReturn(
                located(ownerScope, TemplatePersistence.Lifecycle.ACTIVE));
        return templates;
    }

    private static TemplatePersistence.Located located(
            String ownerScope,
            TemplatePersistence.Lifecycle lifecycle
    ) {
        return new TemplatePersistence.Located(new TemplatePersistence.TemplateMetadata(
                TEMPLATE,
                new OwnerScopeAuthority.OwnerScope(ownerScope),
                SCHEMA,
                0,
                lifecycle));
    }
}
