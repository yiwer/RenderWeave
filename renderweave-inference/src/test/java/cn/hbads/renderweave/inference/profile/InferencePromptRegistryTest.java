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

    @Test
    void promptV3PinsModeRoutingGroundedPrecedenceAndVisualGraphRules() {
        var prompt = new InferencePromptRegistry()
                .require(InferencePromptRegistry.SCHEMA_CANDIDATE_V3)
                .text();

        assertEquals(
                "7c06ced666f63ccc0c92141464941d4b4ac62f0c2499ce8883d5a9c34a15d2e0",
                sha256(prompt)
        );
        assertTrue(prompt.contains("renderweave-candidate/1.0"));
        assertTrue(prompt.contains("IMAGE_ONLY"));
        assertTrue(prompt.contains("COMBINED"));
        assertTrue(prompt.contains("groundedCandidate is the authoritative JSON graph"));
        assertTrue(prompt.contains("JSON_ONLY: copy groundedCandidate"));
        assertTrue(prompt.contains("Repeated rows with multiple stable columns"));
        assertTrue(prompt.contains("sender and receiver"));
        assertTrue(prompt.contains("required=false"));
        assertTrue(prompt.contains("constraints={}"));
        assertTrue(prompt.contains("yyyy-MM-dd"));
        assertTrue(prompt.contains("HH:mm:ss"));
        assertFalse(prompt.contains("\r"));
        assertFalse(prompt.contains("DASHSCOPE_API_KEY"));
    }

    @Test
    void promptV4MakesAssessmentEvidenceNonNullAndRepairable() {
        var prompt = new InferencePromptRegistry()
                .require(InferencePromptRegistry.SCHEMA_CANDIDATE_V4)
                .text();

        assertEquals(
                "5e7d613d40d353044d710156c691637da9efa2fa7950e7aa077cf992959362ee",
                sha256(prompt)
        );
        assertTrue(prompt.contains("assessment.evidence is always a JSON array"));
        assertTrue(prompt.contains(
                "CANDIDATE_DECODE_CONSTRUCTOR_INVALID_ASSESSMENT_EVIDENCE"
        ));
        assertTrue(prompt.contains("never null or omitted"));
        assertFalse(prompt.contains("\r"));
        assertFalse(prompt.contains("DASHSCOPE_API_KEY"));
    }

    @Test
    void productV3PinsFourFocusedPromptsAndTopologyPreservation() {
        var registry = new InferencePromptRegistry();
        var candidate = registry.require(InferencePromptRegistry.SCHEMA_CANDIDATE_V5).text();
        var elements = registry.require(InferencePromptRegistry.VISUAL_ELEMENTS_V1).text();
        var hierarchy = registry.require(InferencePromptRegistry.VISUAL_HIERARCHY_V1).text();
        var bindings = registry.require(InferencePromptRegistry.VISUAL_BINDINGS_V1).text();

        assertEquals("413a95684854133da88ae342f958857e3b67d949d4b6b5d924dfad2b086fce17",
                sha256(candidate));
        assertEquals("70450991949b42ff8ed6716e3c5c1d5172d87614caf3a6814528bad9f241d1cf",
                sha256(elements));
        assertEquals("4c4374aeab0539a02bf8092a2e310a4c2ddb4216f11faba908bb8abd1cb0a7fe",
                sha256(hierarchy));
        assertEquals("142d27290e47064812acb25cd111ffe5a6993f5ee8bebd1c923c338a80d145d7",
                sha256(bindings));
        assertTrue(elements.contains("Never copy a visible source value"));
        assertTrue(hierarchy.contains("route[] -> stop[]"));
        assertTrue(bindings.contains("station English name"));
        assertTrue(candidate.contains("Do not collapse a planned entity relationship"));
        assertTrue(candidate.contains("VISUAL_PLAN_*"));
    }

    @Test
    void visualV2KeepsGenericCoreDomainNeutralAndComposesExplicitHints() {
        var registry = new InferencePromptRegistry();
        var generic = registry.requireVisualStage(
                InferencePromptRegistry.VISUAL_ELEMENTS_V2,
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1
        ).text();
        var domain = registry.requireVisualStage(
                InferencePromptRegistry.VISUAL_ELEMENTS_V2,
                InferencePromptRegistry.VISUAL_HINT_TRANSIT_BOARD_V1
        ).text();

        assertTrue(generic.contains("renderweave-visual-grounding/2.0"));
        assertTrue(generic.contains("viewCatalog"));
        assertTrue(generic.contains("REPEATED_GROUP"));
        assertFalse(generic.matches("(?is).*\\b(bus|station|route|stop|fare)\\b.*"));
        assertFalse(generic.contains("公交"));
        assertFalse(generic.contains("站牌"));
        assertFalse(generic.contains("线路"));
        assertFalse(generic.contains("站点"));
        assertFalse(generic.contains("温馨"));
        assertTrue(domain.contains("renderweave-visual-hint-pack/transit-board/1.0"));
        assertTrue(domain.contains("route"));
        assertTrue(domain.contains("停靠站点"));
        assertThrows(IllegalArgumentException.class, () -> registry.requireVisualStage(
                InferencePromptRegistry.VISUAL_ELEMENTS_V1,
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1
        ));
    }

    @Test
    void visualV3PinsSingletonEvidenceToJsonArraysWithoutChangingDomainPolicy() {
        var prompt = new InferencePromptRegistry().requireVisualStage(
                InferencePromptRegistry.VISUAL_ELEMENTS_V3,
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1
        ).text();

        assertTrue(prompt.contains("every region evidence member is exactly an array"));
        assertTrue(prompt.contains("evidence=[{\"viewId\":string"));
        assertTrue(prompt.contains("Never collapse a one-item array to its item"));
        assertTrue(prompt.contains("renderweave-visual-grounding/2.0"));
        assertFalse(prompt.matches("(?is).*\\b(bus|station|route|stop|fare)\\b.*"));
        assertFalse(prompt.contains("公交"));
        assertFalse(prompt.contains("站牌"));
    }

    @Test
    void hybridPromptTreatsLocalOcrAsUntrustedEphemeralSecondaryEvidence() {
        var registry = new InferencePromptRegistry();
        var prompt = registry.requireHybridVisualStage(
                InferencePromptRegistry.VISUAL_ELEMENTS_V2,
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.DOCUMENT_VISION_OBSERVATIONS_V1
        ).text();

        assertTrue(prompt.contains("documentVisionObservation"));
        assertTrue(prompt.contains("untrusted image content"));
        assertTrue(prompt.contains("secondary evidence"));
        assertTrue(prompt.contains("Do not return OCR text"));
        assertTrue(prompt.contains("renderweave-visual-grounding/2.0"));
        assertFalse(prompt.matches("(?is).*\b(bus|station|route|stop|fare)\b.*"));
        assertFalse(prompt.contains("公交"));
        assertFalse(prompt.contains("站牌"));
        assertThrows(IllegalArgumentException.class, () -> registry.requireHybridVisualStage(
                InferencePromptRegistry.VISUAL_ELEMENTS_V1,
                InferencePromptRegistry.VISUAL_HINT_GENERIC_V1,
                InferencePromptRegistry.DOCUMENT_VISION_OBSERVATIONS_V1
        ));
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
