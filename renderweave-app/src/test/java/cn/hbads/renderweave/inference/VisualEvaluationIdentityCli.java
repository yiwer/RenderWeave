package cn.hbads.renderweave.inference;

import java.nio.file.Path;
import java.util.Arrays;

/** Emits only the safe repository digest used to freeze visual evaluation ledgers. */
public final class VisualEvaluationIdentityCli {
    private VisualEvaluationIdentityCli() { }

    public static void main(String[] arguments) {
        if (arguments.length < 2) {
            throw new IllegalArgumentException("Expected repository root and one or more authorization ledgers");
        }
        System.out.println(new VisualEvaluationIdentity(
                Path.of(arguments[0]), Arrays.stream(arguments).skip(1).map(Path::of).toList()
        ).current());
    }
}
