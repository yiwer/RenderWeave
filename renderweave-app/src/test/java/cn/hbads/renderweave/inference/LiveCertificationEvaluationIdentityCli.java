package cn.hbads.renderweave.inference;

import java.nio.file.Path;

/** Emits only the safe repository digest used to freeze a proposed certification ledger. */
public final class LiveCertificationEvaluationIdentityCli {
    private LiveCertificationEvaluationIdentityCli() { }

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected repository root and authorization file");
        }
        System.out.println(new LiveCertificationEvaluationIdentity(
                Path.of(arguments[0]), Path.of(arguments[1])
        ).current());
    }
}
