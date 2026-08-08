package cn.hbads.renderweave.inference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveCertificationAuthorizationLocatorTest {
    @TempDir
    Path repository;

    @Test
    void absentSelectorUsesTheOriginalVersionedLedger() {
        assertThat(LiveCertificationAuthorizationLocator.resolve(repository, null))
                .isEqualTo(repository.resolve("plans/live-certification-authorizations")
                        .resolve("p5-certification-20260808.json").toAbsolutePath().normalize());
    }

    @Test
    void explicitSelectorCanChooseAnotherStrictRepositoryLedger() {
        assertThat(LiveCertificationAuthorizationLocator.resolve(
                repository, "p5-certification-plus-20260809"
        )).isEqualTo(repository.resolve("plans/live-certification-authorizations")
                .resolve("p5-certification-plus-20260809.json").toAbsolutePath().normalize());
    }

    @Test
    void selectorCannotEscapeOrAliasTheLedgerDirectory() {
        assertThatThrownBy(() -> LiveCertificationAuthorizationLocator.resolve(repository, "../open"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LIVE_CERTIFICATION_AUTHORIZATION_SELECTOR_INVALID");
        assertThatThrownBy(() -> LiveCertificationAuthorizationLocator.resolve(repository, " plus "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LIVE_CERTIFICATION_AUTHORIZATION_SELECTOR_INVALID");
        assertThatThrownBy(() -> LiveCertificationAuthorizationLocator.resolve(repository, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LIVE_CERTIFICATION_AUTHORIZATION_SELECTOR_INVALID");
    }
}
