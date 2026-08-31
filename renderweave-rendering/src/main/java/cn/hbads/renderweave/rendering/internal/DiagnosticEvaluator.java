package cn.hbads.renderweave.rendering.internal;

import cn.hbads.renderweave.rendering.api.Evaluator;

import java.util.Objects;

/** Internal evaluator facet that hands the request-local sidecar directly to its sole consumer. */
interface DiagnosticEvaluator extends Evaluator {

    EvaluationOutcome evaluate(EvaluationCommand command, SidecarSink sidecarSink);

    @Override
    default EvaluationOutcome evaluate(EvaluationCommand command) {
        return evaluate(command, ignored -> { });
    }

    @FunctionalInterface
    interface SidecarSink {
        void accept(byte[] canonicalUtf8);

        static byte[] retainedCopy(byte[] canonicalUtf8) {
            Objects.requireNonNull(canonicalUtf8, "canonicalUtf8");
            if (canonicalUtf8.length == 0) {
                throw new IllegalArgumentException("diagnostic sidecar must not be empty");
            }
            return canonicalUtf8.clone();
        }
    }
}
