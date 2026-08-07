package cn.hbads.renderweave.inference.candidate;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CandidateApplyCapabilityTest {
    private static final Set<String> FORBIDDEN_CAPABILITY_TERMS = Set.of(
            "publish", "update", "delete", "sql", "filesystem", "http"
    );

    @Test
    void inferenceApplicationPortExposesExactlyOneCreateOnlyApplyCapability() {
        var methods = Arrays.stream(CandidateApplyStore.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .toList();

        assertEquals(1, methods.size());
        assertEquals("apply", methods.getFirst().getName());
        assertFalse(forbidden(methods.getFirst().toGenericString()));
    }

    @Test
    void publicApplicationServiceHasNoPublishUpdateDeleteOrInfrastructureEscapeHatch() {
        var publicMethods = Arrays.stream(CandidateApplyService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .toList();

        assertEquals(Set.of("apply"), publicMethods.stream().map(method -> method.getName()).collect(
                java.util.stream.Collectors.toSet()
        ));
        assertFalse(publicMethods.stream().anyMatch(method -> forbidden(method.toGenericString())));
    }

    private static boolean forbidden(String signature) {
        var lower = signature.toLowerCase(java.util.Locale.ROOT);
        return FORBIDDEN_CAPABILITY_TERMS.stream().anyMatch(lower::contains);
    }
}
