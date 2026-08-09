package cn.hbads.renderweave.inference.profile;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferencePromptRegistryTest {
    @Test
    void loadsTheVersionedPromptWithoutSecretsToolsOrMarkdownOutput() {
        var registry = new InferencePromptRegistry();
        var prompt = registry
                .require(InferencePromptRegistry.SCHEMA_CANDIDATE_V1)
                .text();

        assertEquals(
                "b21ffb2a387d6494679118d5f4398856c1a9d500d422403254be1709eb70f9db",
                sha256(prompt)
        );
        assertTrue(prompt.contains("renderweave-candidate/1.0"));
        assertTrue(prompt.contains("Return exactly one JSON object"));
        assertTrue(prompt.contains("untrusted data"));
        assertFalse(prompt.contains("\r"));
        assertFalse(prompt.contains("DASHSCOPE_API_KEY"));
        assertThrows(IllegalArgumentException.class,
                () -> new InferencePromptRegistry().require("unversioned-prompt"));
    }

    @Test
    void promptV2PinsExactFieldIdentityMinimalEvidenceAndJsonTopologyRules() {
        var prompt = new InferencePromptRegistry()
                .require(InferencePromptRegistry.SCHEMA_CANDIDATE_V2)
                .text();

        assertEquals(
                "0a97da2c71b1429704b93ced8fdcb96a7eb2b8dc1f56d88e487dea09e5d8df52",
                sha256(prompt)
        );
        assertTrue(prompt.contains("renderweave-candidate/1.0"));
        assertTrue(prompt.contains("preserve the JSON property name exactly"));
        assertTrue(prompt.contains("a/b"));
        assertTrue(prompt.contains("x~y"));
        assertTrue(prompt.contains("128 UTF-8 bytes"));
        assertTrue(prompt.contains("proposedFieldKey=null"));
        assertTrue(prompt.contains("must never use source=\"USER\""));
        assertTrue(prompt.contains("supplement but never replace the matching JSON evidence"));
        assertTrue(prompt.contains("required=false"));
        assertTrue(prompt.contains("constraints={}"));
        assertTrue(prompt.contains("ARRAY:UNRESOLVED"));
        assertTrue(prompt.contains("ARRAY:CONFLICT"));
        assertTrue(prompt.contains("yyyy-MM-dd"));
        assertTrue(prompt.contains("HH:mm:ss"));
        assertTrue(prompt.contains("repairProblemCodes"));
        assertTrue(prompt.contains("minimal evidence graph"));
        assertFalse(prompt.contains("\r"));
        assertFalse(prompt.contains("DASHSCOPE_API_KEY"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
