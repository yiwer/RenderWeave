package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class DesignInputExpressionCapacityAuthorityTest {

    private final DesignInputExpressionCapacityAuthority authority =
            TemplateModule.designInputExpressionCapacityAuthority();

    @Test
    void evaluatesMaxInclusiveRuleAndReturnsItsClosedTerminal() {
        assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Accepted.class,
                evaluate("designDslParser.rawUtf8Bytes", "16777216")
        );

        var rejected = assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Rejected.class,
                evaluate("designDslParser.rawUtf8Bytes", "16777217")
        );

        assertEquals("DESIGN_DSL_LIMIT_EXCEEDED", rejected.terminal().code());
        assertEquals("DESIGN_PARSE", rejected.terminal().contractStage());
        assertEquals("TEMPLATE_CLOSURE", rejected.terminal().publicRenderStage());
        assertEquals("ZERO_WRITE_AND_DOWNSTREAM", rejected.terminal().zeroBoundary());
        assertEquals(
                List.of(
                        "templateWrites=0",
                        "assetWrites=0",
                        "evaluationStarts=0",
                        "renderDocuments=0",
                        "renderOutputs=0"
                ),
                rejected.terminal().downstreamEffects()
        );
    }

    @Test
    void evaluatesEnumExactAndMinExclusiveRules() {
        assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Accepted.class,
                evaluate("renderInput.contentEncoding", "identity")
        );
        assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Rejected.class,
                evaluate("renderInput.contentEncoding", "gzip")
        );
        assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Rejected.class,
                evaluate("geometry.canvasTrimMmPerAxisExclusiveMin", "0")
        );
        assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Accepted.class,
                evaluate("geometry.canvasTrimMmPerAxisExclusiveMin", "0.000001")
        );
    }

    @Test
    void failsClosedForUnknownOrMalformedObservations() {
        var unknown = assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Invalid.class,
                evaluate("unknown.limit", "1")
        );
        assertEquals(
                DesignInputExpressionCapacityAuthority.InvalidReason.UNKNOWN_LIMIT,
                unknown.reason()
        );

        var malformed = assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Invalid.class,
                evaluate("designDslParser.rawUtf8Bytes", "1.5")
        );
        assertEquals(
                DesignInputExpressionCapacityAuthority.InvalidReason.INVALID_OBSERVED_VALUE,
                malformed.reason()
        );
    }

    @Test
    void comparesCanonicalDecimalsInLinearSpaceWithoutNumericReconstruction() {
        var largePositive = "1" + "0".repeat(100_000);
        var tinyPositive = "0." + "0".repeat(100_000) + "1";

        assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Accepted.class,
                evaluate("geometry.canvasTrimMmPerAxisExclusiveMin", tinyPositive)
        );
        assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Rejected.class,
                evaluate("geometry.canvasTrimMmPerAxisMax", largePositive)
        );
        assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Accepted.class,
                evaluate("geometry.canvasTrimMmPerAxisMax", "1000.000")
        );
        assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Rejected.class,
                evaluate("geometry.rotationDegreesMin", "-360.0001")
        );
        assertInstanceOf(
                DesignInputExpressionCapacityAuthority.Accepted.class,
                evaluate("geometry.rotationDegreesMin", "-360.000")
        );
    }

    private DesignInputExpressionCapacityAuthority.Decision evaluate(
            String limitId,
            String observedValue
    ) {
        return authority.evaluate(new DesignInputExpressionCapacityAuthority.Observation(
                limitId,
                observedValue
        ));
    }
}
