package cn.hbads.renderweave.inference.eval.visual.quality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class R5ProductTransformAuthorityTest {
    @Test
    void rejectedA2ClaimPermanentlyStopsTheCurrentSuccessorRoute() {
        var authority = R5ProductTransformAuthority.load();

        assertEquals("renderweave-r5-product-transform-authority/1.1:"
                        + "a6ef7ee0820ea906cb371371d66a8eaef3ba77ac569ae24d6e4935e144ef4475",
                authority.authorityIdentity());
        assertEquals("a31d54125764254c2814ecc5c7114137a3a11b29", authority.repositoryRevision());
        assertEquals("A1_PRODUCER_REPORT_CONSISTENCY_ONLY", authority.acceptedAssurance());
        assertEquals("NOT_ESTABLISHED", authority.a2Disposition());
        assertEquals(java.util.List.of(
                "NORMALIZED_RASTER_INPUT_NOT_PROVEN",
                "PRODUCT_STATIC_ACQUISITION_NOT_PROVEN",
                "INDEPENDENT_LAYERED_METRICS_NOT_REPLAYED",
                "PROVIDER_ZERO_NOT_INDEPENDENTLY_GROUNDED",
                "PER_CASE_HALLUCINATION_NON_INCREASE",
                "PER_CASE_TARGET_IMPROVEMENT"), authority.rejectionReasonCodes());
        assertEquals("R5_PRODUCT_TRANSFORM_NOT_QUALIFIED", authority.disposition());
        assertEquals("LIVE_J1_REQUEST_NOT_ELIGIBLE", authority.freshJ1Disposition());
        assertEquals(4, authority.deterministicCases());
        assertEquals(0, authority.reportedExternalProviderUsage().attempts());
        assertEquals(0, authority.reportedApiKeyReads());
        assertFalse(authority.a2Established());
        assertFalse(authority.allowsTransformRerun());
        assertFalse(authority.allowsActionImplementation());
        assertFalse(authority.allowsFreshJ1Request());
    }
}
