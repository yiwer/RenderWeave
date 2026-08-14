package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.quality.ChallengerCapabilityAdmission;
import cn.hbads.renderweave.inference.eval.visual.quality.FrozenQualityEvidencePackJsonCodec;
import cn.hbads.renderweave.inference.eval.visual.quality.OfflineQualityDecisionAssembler;
import cn.hbads.renderweave.inference.eval.visual.quality.R2R5TriggerDecision;
import cn.hbads.renderweave.inference.eval.visual.quality.R2R5TriggerDecisionJsonCodec;
import cn.hbads.renderweave.inference.eval.visual.quality.R3OrderRepeatProbeEvidenceJsonCodec;
import cn.hbads.renderweave.inference.eval.visual.quality.R5OracleProbeEvidenceJsonCodec;
import cn.hbads.renderweave.inference.eval.visual.quality.RapidOcrCausalEvidencePackJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicit zero-provider gate for the sole VRQ-07 offline decision seam. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_VRQ07_OFFLINE_DECISION", matches = "true")
class OfflineQualityDecisionGateTest {
    @Test
    void writesTheCanonicalEvidencePackAndStopToSpecR5Decision() throws Exception {
        var rapidPath = requiredEvidenceFile(
                "RENDERWEAVE_VRQ07_RAPIDOCR_CAUSAL", "vrq04-causal-evidence.json", true);
        var r3Path = requiredEvidenceFile(
                "RENDERWEAVE_VRQ07_R3_EVIDENCE", "vrq05-r3-probe-evidence.json", true);
        var r5Path = requiredEvidenceFile(
                "RENDERWEAVE_VRQ07_R5_EVIDENCE", "vrq06-r5-oracle-evidence.json", true);
        var rapidA2Path = requiredEvidenceFile(
                "RENDERWEAVE_VRQ07_RAPIDOCR_A2", "vrq04-causal-a2.json", true);
        var r3A2Path = requiredEvidenceFile(
                "RENDERWEAVE_VRQ07_R3_A2", "vrq05-r3-probe-a2.json", true);
        var r5A2Path = requiredEvidenceFile(
                "RENDERWEAVE_VRQ07_R5_A2", "vrq06-r5-oracle-a2.json", true);
        var packPath = requiredEvidenceFile(
                "RENDERWEAVE_VRQ07_EVIDENCE_PACK", "vrq07-evidence-pack.json", false);
        var decisionPath = requiredEvidenceFile(
                "RENDERWEAVE_VRQ07_DECISION", "vrq07-decision.json", false);
        var rapidBytes = Files.readAllBytes(rapidPath);
        var r3Bytes = Files.readAllBytes(r3Path);
        var r5Bytes = Files.readAllBytes(r5Path);
        var rapidCodec = new RapidOcrCausalEvidencePackJsonCodec();
        var r3Codec = new R3OrderRepeatProbeEvidenceJsonCodec();
        var r5Codec = new R5OracleProbeEvidenceJsonCodec();
        var rapid = rapidCodec.read(rapidBytes);
        var r3 = r3Codec.read(r3Bytes);
        var r5 = r5Codec.read(r5Bytes);
        var expectedRevision = required("RENDERWEAVE_VRQ07_EXPECTED_REVISION");
        var bundle = new OfflineQualityDecisionAssembler().assemble(
                rapid,
                ChallengerCapabilityAdmission.load(),
                r3,
                r5,
                new OfflineQualityDecisionAssembler.ComponentEvidenceAuthority(
                        rapidBytes, Files.readAllBytes(rapidA2Path),
                        r3Bytes, Files.readAllBytes(r3A2Path),
                        r5Bytes, Files.readAllBytes(r5A2Path),
                        expectedRevision));
        Files.write(packPath, bundle.encodedEvidencePack(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.write(decisionPath, bundle.encodedDecision(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        assertEquals(R2R5TriggerDecision.OverallDisposition.STOP_TO_SPEC_R5,
                bundle.decision().overallDisposition());
        assertEquals(bundle.evidencePackIdentity(), new FrozenQualityEvidencePackJsonCodec()
                .evidencePackIdentity(new FrozenQualityEvidencePackJsonCodec().read(
                        Files.readAllBytes(packPath), bundle.evidencePackIdentity())));
        assertEquals(bundle.decisionIdentity(), new R2R5TriggerDecisionJsonCodec()
                .decisionIdentity(new R2R5TriggerDecisionJsonCodec().read(
                        Files.readAllBytes(decisionPath), bundle.decisionIdentity())));
    }

    private static Path requiredEvidenceFile(
            String environmentName,
            String expectedName,
            boolean mustExist
    ) throws Exception {
        var requested = required(environmentName);
        var repository = repositoryRoot();
        var evidenceRootPath = repository.resolve(".sdlc/evidence").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(evidenceRootPath)
                || !Files.isDirectory(evidenceRootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("VRQ07_EVIDENCE_ROOT_INVALID");
        }
        var evidenceRoot = evidenceRootPath.toRealPath();
        var path = Path.of(requested).toAbsolutePath().normalize();
        var parentPath = path.getParent();
        if (!path.startsWith(evidenceRootPath) || path.equals(evidenceRootPath)
                || !expectedName.equals(path.getFileName().toString())
                || Files.isSymbolicLink(parentPath)
                || !Files.isDirectory(parentPath, LinkOption.NOFOLLOW_LINKS)
                || !parentPath.toRealPath().startsWith(evidenceRoot)
                || mustExist != Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("VRQ07_EVIDENCE_PATH_INVALID");
        }
        return path;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the VRQ-07 evidence gate");
        }
        return value;
    }

    private static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("renderweave-inference"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("REPOSITORY_ROOT_NOT_FOUND");
    }
}
