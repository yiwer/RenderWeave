package cn.hbads.renderweave.inference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InferenceApplicationConfigurationTest {
    @Test
    void dashScopeSecretSourceUsesOnlyTheExactTokenPlanNames() throws Exception {
        var method = InferenceApplicationConfiguration.class.getDeclaredMethod(
                "dashScopeInferenceProvider",
                ObjectMapper.class, String.class, String.class, String.class
        );
        var parameters = method.getParameters();

        assertEquals("${renderweave.inference.dashscope.base-url}",
                parameters[1].getAnnotation(Value.class).value());
        assertEquals("${DASHSCOPE_TOKEN_API_KEY:}",
                parameters[2].getAnnotation(Value.class).value());
        assertEquals("${DASHSCOPE_TOKEN_API_KEY_FILE:}",
                parameters[3].getAnnotation(Value.class).value());

        try (var input = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(input);
            var configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(configuration.contains("      api-key:"));
            assertFalse(configuration.contains("      api-key-file:"));
        }
    }
}
