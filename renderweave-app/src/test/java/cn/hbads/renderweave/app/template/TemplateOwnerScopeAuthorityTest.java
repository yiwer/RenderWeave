package cn.hbads.renderweave.app.template;

import cn.hbads.renderweave.template.api.TemplateApplication;
import cn.hbads.renderweave.template.spi.OwnerScopeAuthority;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateOwnerScopeAuthorityTest {
    private static final TemplateApplication.TemplateInvocationRef INVOCATION =
            TemplateApplication.TemplateInvocationRef.serverCreated("authority-test");

    @Test
    void missingProductionHostAdapterFailsEveryOperationClosed() {
        var authority = new FailClosedOwnerScopeAuthority();

        assertThat(authority.authorizeCreate(INVOCATION))
                .isInstanceOf(OwnerScopeAuthority.CreateUnavailable.class);
        assertThat(authority.authorizeCatalog(INVOCATION))
                .isInstanceOf(OwnerScopeAuthority.CatalogUnavailable.class);
        assertThat(authority.authorizeExisting(
                INVOCATION,
                new OwnerScopeAuthority.OwnerScope("owner"),
                OwnerScopeAuthority.ExistingOperation.READ
        )).isInstanceOf(OwnerScopeAuthority.ExistingUnavailable.class);
        assertThat(authority.recheck(new OwnerScopeAuthority.RecheckIdentity("unissued")))
                .isInstanceOf(OwnerScopeAuthority.RecheckUnavailable.class);
    }

    @Test
    void configuredSingleOwnerKeepsReadIndependentAndRechecksMutationsOnce() {
        var updateOnly = new ConfiguredSingleOwnerScopeAuthority(
                "owner-a",
                Set.of("template.create", "template.update")
        );
        var create = (OwnerScopeAuthority.CreateGranted) updateOnly.authorizeCreate(INVOCATION);
        assertThat(create.disclosure()).isEqualTo(OwnerScopeAuthority.Disclosure.OPAQUE);
        assertThat(updateOnly.recheck(create.recheckIdentity()))
                .isInstanceOf(OwnerScopeAuthority.RecheckGranted.class);
        assertThat(updateOnly.recheck(create.recheckIdentity()))
                .isInstanceOf(OwnerScopeAuthority.RecheckDenied.class);

        assertThat(updateOnly.authorizeExisting(
                INVOCATION,
                new OwnerScopeAuthority.OwnerScope("owner-a"),
                OwnerScopeAuthority.ExistingOperation.READ
        )).isInstanceOf(OwnerScopeAuthority.ExistingHidden.class);
        assertThat(updateOnly.authorizeCatalog(INVOCATION))
                .isInstanceOf(OwnerScopeAuthority.CatalogDenied.class);
        var update = (OwnerScopeAuthority.ExistingGranted) updateOnly.authorizeExisting(
                INVOCATION,
                new OwnerScopeAuthority.OwnerScope("owner-a"),
                OwnerScopeAuthority.ExistingOperation.UPDATE
        );
        assertThat(update.disclosure()).isEqualTo(OwnerScopeAuthority.Disclosure.OPAQUE);
        assertThat(updateOnly.authorizeExisting(
                INVOCATION,
                new OwnerScopeAuthority.OwnerScope("another-owner"),
                OwnerScopeAuthority.ExistingOperation.UPDATE
        )).isInstanceOf(OwnerScopeAuthority.ExistingHidden.class);

        var readOnly = new ConfiguredSingleOwnerScopeAuthority(
                "owner-a",
                Set.of("template.read")
        );
        assertThat(readOnly.authorizeCatalog(INVOCATION))
                .isEqualTo(new OwnerScopeAuthority.CatalogGranted(
                        new OwnerScopeAuthority.OwnerScope("owner-a")
                ));
    }

    @Test
    void rejectedMutationsCannotGrowTheDevelopmentRecheckStoreWithoutBound() {
        var authority = new ConfiguredSingleOwnerScopeAuthority(
                "owner-b",
                Set.of("template.create")
        );
        var oldest = ((OwnerScopeAuthority.CreateGranted) authority.authorizeCreate(INVOCATION))
                .recheckIdentity();
        OwnerScopeAuthority.RecheckIdentity newest = null;
        for (int index = 0; index < 4096; index++) {
            newest = ((OwnerScopeAuthority.CreateGranted) authority.authorizeCreate(INVOCATION))
                    .recheckIdentity();
        }

        assertThat(authority.recheck(oldest))
                .isInstanceOf(OwnerScopeAuthority.RecheckDenied.class);
        assertThat(authority.recheck(newest))
                .isInstanceOf(OwnerScopeAuthority.RecheckGranted.class);
    }
}
