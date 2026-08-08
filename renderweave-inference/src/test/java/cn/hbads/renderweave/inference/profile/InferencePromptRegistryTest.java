package cn.hbads.renderweave.inference.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferencePromptRegistryTest {
    @Test
    void loadsTheVersionedPromptWithoutSecretsToolsOrMarkdownOutput() {
        var prompt = new InferencePromptRegistry()
                .require(InferencePromptRegistry.SCHEMA_CANDIDATE_V1)
                .text();

        assertTrue(prompt.contains("renderweave-candidate/1.0"));
        assertTrue(prompt.contains("Return exactly one JSON object"));
        assertTrue(prompt.contains("untrusted data"));
        assertFalse(prompt.contains("DASHSCOPE_API_KEY"));
        assertThrows(IllegalArgumentException.class,
                () -> new InferencePromptRegistry().require("unversioned-prompt"));
    }
}
