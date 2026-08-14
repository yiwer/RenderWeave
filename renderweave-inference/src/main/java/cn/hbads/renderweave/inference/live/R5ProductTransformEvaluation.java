package cn.hbads.renderweave.inference.live;

import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowEvaluation;
import cn.hbads.renderweave.inference.eval.visual.quality.R5ProductTransformEvidence;

import java.util.Objects;

/**
 * Closed historical entry point for the R5 product-transform experiment.
 *
 * <p>The single approved execution did not establish the required independent A2 basis. The
 * successor specification forbids retuning or retrying this route, so production code keeps no
 * executable acquisition path. A different transform experiment requires a new specification and
 * a new entry point.</p>
 */
public final class R5ProductTransformEvaluation {
    public static final String VERSION = "renderweave-r5-product-transform-runner/1.0";

    public Result evaluate(RapidOcrShadowEvaluation.RunSessionFactory factory) {
        Objects.requireNonNull(factory, "factory");
        throw new IllegalStateException("R5_PRODUCT_TRANSFORM_ROUTE_CLOSED");
    }

    public record Result(
            R5ProductTransformEvidence evidence,
            String evidenceIdentity,
            byte[] encodedEvidence
    ) {
        public Result {
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(evidenceIdentity, "evidenceIdentity");
            encodedEvidence = Objects.requireNonNull(encodedEvidence, "encodedEvidence").clone();
        }

        @Override
        public byte[] encodedEvidence() {
            return encodedEvidence.clone();
        }
    }
}
