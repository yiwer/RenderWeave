package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.asset.api.AssetApplication;
import cn.hbads.renderweave.asset.spi.AssetOwnerScopeAuthority;
import cn.hbads.renderweave.rendering.api.Evaluator.ExternalAssetReadAuthorization;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class RenderingAuthorityAdapterTest {

    private static final RenderInvocationRef INVOCATION =
            RenderInvocationRef.serverCreated("render-authority-test");
    private static final TemplateId TEMPLATE =
            TemplateId.of("00000000-0000-4000-8000-0000000000a1");
    private static final TemplateId CHILD_TEMPLATE =
            TemplateId.of("00000000-0000-4000-8000-0000000000a2");
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
        assertThat(authority.discloseDiagnosticSegment(
                new RenderingAuthority.RecheckIdentity("missing"), TEMPLATE))
                .isEqualTo(RenderingAuthority.DiagnosticSegmentDisclosure.REDACTED);
        assertThat(new FailClosedRendererProfileAuthority().select(
                OutputSelection.defaultPng()))
                .isInstanceOf(RendererProfileAuthority.Unavailable.class);
    }

    @Test
    void formalOutputCanRemainOpaqueButPreviewRequiresReadAndRender() {
        var renderOnly = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.render"),
                activeTemplates("owner-a"),
                assets(new AssetOwnerScopeAuthority.CatalogForbidden()));

        var formal = (RenderingAuthority.Authorized) renderOnly.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.FORMAL_OUTPUT);
        assertThat(formal.disclosure()).isEqualTo(RenderingAuthority.Disclosure.OPAQUE);
        assertThat(formal.externalAssetReadAuthorization())
                .isEqualTo(ExternalAssetReadAuthorization.DENIED);
        assertThat(renderOnly.discloseDiagnosticSegment(formal.recheckIdentity(), TEMPLATE))
                .isEqualTo(RenderingAuthority.DiagnosticSegmentDisclosure.REDACTED);
        assertThat(renderOnly.recheck(formal.recheckIdentity()))
                .isInstanceOf(RenderingAuthority.RecheckGranted.class);
        assertThat(renderOnly.discloseDiagnosticSegment(formal.recheckIdentity(), TEMPLATE))
                .isEqualTo(RenderingAuthority.DiagnosticSegmentDisclosure.REDACTED);
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
                activeTemplates("owner-a"),
                assets(new AssetOwnerScopeAuthority.CatalogForbidden()));
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
                activeTemplates("owner-a"),
                assets(new AssetOwnerScopeAuthority.CatalogForbidden()));
        var granted = (RenderingAuthority.Authorized) preview.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.AUTHORITATIVE_PREVIEW);
        assertThat(granted.disclosure()).isEqualTo(RenderingAuthority.Disclosure.READABLE);
        assertThat(preview.discloseDiagnosticSegment(granted.recheckIdentity(), TEMPLATE))
                .isEqualTo(RenderingAuthority.DiagnosticSegmentDisclosure.READABLE);
        assertThat(preview.discloseDiagnosticSegment(granted.recheckIdentity(), CHILD_TEMPLATE))
                .isEqualTo(RenderingAuthority.DiagnosticSegmentDisclosure.REDACTED);
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
                activeTemplates("owner-a"),
                assets(new AssetOwnerScopeAuthority.CatalogForbidden()));

        assertThatThrownBy(() -> new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.admin"),
                activeTemplates("owner-a"),
                assets(new AssetOwnerScopeAuthority.CatalogForbidden())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown Template single-owner capability");
    }

    @Test
    void rejectedOperationsCannotGrowTheRecheckStoreWithoutBound() {
        var authority = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.render"),
                activeTemplates("owner-a"),
                assets(new AssetOwnerScopeAuthority.CatalogForbidden()));
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
                templates,
                assets(new AssetOwnerScopeAuthority.CatalogForbidden()));

        var granted = (RenderingAuthority.Authorized) authority.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.AUTHORITATIVE_PREVIEW);

        assertThat(authority.recheck(granted.recheckIdentity()))
                .isInstanceOf(RenderingAuthority.RecheckHidden.class);

        var crossScope = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.read", "template.render"),
                activeTemplates("owner-b"),
                assets(new AssetOwnerScopeAuthority.CatalogForbidden()));
        assertThat(crossScope.authorize(
                INVOCATION,
                TEMPLATE,
                RenderPurpose.FORMAL_OUTPUT))
                .isInstanceOf(RenderingAuthority.Hidden.class);
    }

    @Test
    void externalAssetReadDecisionIsScopedAndBoundIntoAuthorizationContext() {
        var grantedAuthority = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.render"),
                activeTemplates("owner-a"),
                assets(new AssetOwnerScopeAuthority.CatalogGranted(
                        new AssetApplication.OwnerScope("owner-a"))));
        var mismatchedAuthority = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.render"),
                activeTemplates("owner-a"),
                assets(new AssetOwnerScopeAuthority.CatalogGranted(
                        new AssetApplication.OwnerScope("owner-b"))));
        var unavailableAuthority = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.render"),
                activeTemplates("owner-a"),
                assets(new AssetOwnerScopeAuthority.CatalogUnavailable()));

        var granted = (RenderingAuthority.Authorized) grantedAuthority.authorize(
                INVOCATION, TEMPLATE, RenderPurpose.FORMAL_OUTPUT);
        var mismatched = (RenderingAuthority.Authorized) mismatchedAuthority.authorize(
                INVOCATION, TEMPLATE, RenderPurpose.FORMAL_OUTPUT);
        var unavailable = (RenderingAuthority.Authorized) unavailableAuthority.authorize(
                INVOCATION, TEMPLATE, RenderPurpose.FORMAL_OUTPUT);

        assertThat(granted.externalAssetReadAuthorization())
                .isEqualTo(ExternalAssetReadAuthorization.GRANTED);
        assertThat(mismatched.externalAssetReadAuthorization())
                .isEqualTo(ExternalAssetReadAuthorization.DENIED);
        assertThat(unavailable.externalAssetReadAuthorization())
                .isEqualTo(ExternalAssetReadAuthorization.UNAVAILABLE);
        assertThat(granted.authorizationContextDigest())
                .isNotEqualTo(mismatched.authorizationContextDigest())
                .isNotEqualTo(unavailable.authorizationContextDigest());
    }

    @Test
    void rootReleaseRecheckDoesNotReauthorizeExternalAssetRead() {
        var assetAuthority = assets(new AssetOwnerScopeAuthority.CatalogGranted(
                new AssetApplication.OwnerScope("owner-a")));
        var authority = new ConfiguredSingleOwnerRenderingAuthority(
                "owner-a",
                Set.of("template.render"),
                activeTemplates("owner-a"),
                assetAuthority);

        var granted = (RenderingAuthority.Authorized) authority.authorize(
                INVOCATION, TEMPLATE, RenderPurpose.FORMAL_OUTPUT);
        assertThat(authority.recheck(granted.recheckIdentity()))
                .isInstanceOf(RenderingAuthority.RecheckGranted.class);

        verify(assetAuthority, times(1)).authorizeCatalog(any());
    }

    private static TemplatePersistence activeTemplates(String ownerScope) {
        var templates = mock(TemplatePersistence.class);
        when(templates.locate(TEMPLATE)).thenReturn(
                located(ownerScope, TemplatePersistence.Lifecycle.ACTIVE));
        return templates;
    }

    private static AssetOwnerScopeAuthority assets(
            AssetOwnerScopeAuthority.CatalogDecision decision
    ) {
        var authority = mock(AssetOwnerScopeAuthority.class);
        when(authority.authorizeCatalog(any())).thenReturn(decision);
        return authority;
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
