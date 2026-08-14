package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class R5ProductTransformEvaluationTest {
    @Test
    void permanentlyRefusesToRerunTheFailedProductTransformRoute() {
        var acquisitions = new AtomicInteger();
        var failure = assertThrows(IllegalStateException.class,
                () -> new R5ProductTransformEvaluation().evaluate(runOrdinal -> {
                    acquisitions.incrementAndGet();
                    return null;
                }));

        assertEquals("R5_PRODUCT_TRANSFORM_ROUTE_CLOSED", failure.getMessage());
        assertEquals(0, acquisitions.get());
    }
}
