package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;

final class DesignDslFailureException extends Exception {

    private final DesignDslAuthority.Rejected rejection;

    DesignDslFailureException(DesignDslAuthority.Rejected rejection) {
        this.rejection = rejection;
    }

    DesignDslAuthority.Rejected rejection() {
        return rejection;
    }
}
