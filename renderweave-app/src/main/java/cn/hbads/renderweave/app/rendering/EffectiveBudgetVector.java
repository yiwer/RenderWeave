package cn.hbads.renderweave.app.rendering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Loads the frozen complete numeric budget vector used by evaluation identity. */
final class EffectiveBudgetVector {

    private static final String RESOURCE = "/renderweave/capacity-budgets-v1.json";

    private EffectiveBudgetVector() {
    }

    static String load() {
        try (var input = EffectiveBudgetVector.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing effective budget vector");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load effective budget vector", failure);
        }
    }
}
