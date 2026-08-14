package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.RapidOcrShadowReportJsonCodec;
import cn.hbads.renderweave.inference.eval.visual.quality.R3OrderRepeatProbeEvidence;
import cn.hbads.renderweave.inference.eval.visual.quality.R3OrderRepeatProbeEvidenceJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicit zero-provider gate for the fixed VRQ-05 R3 causal probe. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_VRQ05_R3_PROBE", matches = "true")
class R3OrderRepeatProbeGateTest {
    @Test
    void writesCanonicalPayloadSafeR3EvidenceFromTheExactTwoRunReport() throws Exception {
        var reportPath = requiredEvidenceFile(
                "RENDERWEAVE_VRQ05_RAPIDOCR_REPORT", "rapidocr-shadow-report.json", true);
        var outputPath = requiredEvidenceFile(
                "RENDERWEAVE_VRQ05_R3_EVIDENCE", "vrq05-r3-probe-evidence.json", false);
        var report = new RapidOcrShadowReportJsonCodec().read(Files.readAllBytes(reportPath));
        var evidence = R3OrderRepeatProbeEvidence.from(report, true);
        var codec = new R3OrderRepeatProbeEvidenceJsonCodec();
        var encoded = codec.write(evidence);
        Files.write(outputPath, encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        assertEquals(evidence, codec.read(Files.readAllBytes(outputPath), codec.evidenceIdentity(evidence)));
        assertEquals(R3OrderRepeatProbeEvidence.Disposition.MISSING, evidence.disposition());
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
            throw new IllegalArgumentException("VRQ05_EVIDENCE_ROOT_INVALID");
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
            throw new IllegalArgumentException("VRQ05_EVIDENCE_PATH_INVALID");
        }
        return path;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the VRQ-05 evidence gate");
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
