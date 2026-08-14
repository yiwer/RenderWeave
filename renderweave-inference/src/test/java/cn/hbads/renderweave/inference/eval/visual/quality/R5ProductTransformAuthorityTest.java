package cn.hbads.renderweave.inference.eval.visual.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R5ProductTransformAuthorityTest {
    @Test
    void exactA2FailurePermanentlyStopsTheCurrentSuccessorRoute() {
        var authority = R5ProductTransformAuthority.load();

        assertEquals("R5_PRODUCT_TRANSFORM_NOT_QUALIFIED", authority.disposition());
        assertEquals("LIVE_J1_REQUEST_NOT_ELIGIBLE", authority.freshJ1Disposition());
        assertEquals(4, authority.deterministicCases());
        assertEquals(0, authority.externalProviderUsage().attempts());
        assertEquals(0, authority.apiKeyReads());
        assertFalse(authority.allowsActionImplementation());
        assertFalse(authority.allowsFreshJ1Request());
        assertTrue(authority.authorityIdentity().matches(
                "renderweave-r5-product-transform-authority/1\\.0:[0-9a-f]{64}"));
    }
}
