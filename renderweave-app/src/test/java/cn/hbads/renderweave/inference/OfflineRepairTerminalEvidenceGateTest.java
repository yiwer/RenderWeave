package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.eval.visual.quality.ChallengerCapabilityAdmission;
import cn.hbads.renderweave.inference.eval.visual.quality.OfflineRepairTerminalGate;
import cn.hbads.renderweave.inference.eval.visual.quality.OfflineRepairTerminalOutcome;
import cn.hbads.renderweave.inference.eval.visual.quality.OfflineRepairTerminalOutcomeJsonCodec;
import cn.hbads.renderweave.inference.eval.visual.quality.R2R5TriggerDecisionJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicit zero-provider evidence gate for conditional offline ticket outcomes. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_OFFLINE_TERMINAL_GATE", matches = "true")
class OfflineRepairTerminalEvidenceGateTest {
    @Test
    void writesTheCanonicalFailClosedTicketOutcome() throws Exception {
        var ticket = OfflineRepairTerminalOutcome.Ticket.valueOf(required(
                "RENDERWEAVE_OFFLINE_TERMINAL_TICKET"));
        var decisionPath = evidenceFile(
                "RENDERWEAVE_OFFLINE_TERMINAL_DECISION", "vrq07-decision.json", true);
        var outputPath = evidenceFile(
                "RENDERWEAVE_OFFLINE_TERMINAL_OUTCOME", outputName(ticket), false);
        var decisionCodec = new R2R5TriggerDecisionJsonCodec();
        var decision = decisionCodec.read(Files.readAllBytes(decisionPath));
        var outcome = new OfflineRepairTerminalGate().closeR2Challenger(
                ticket,
                decision,
                decisionCodec.decisionIdentity(decision),
                ChallengerCapabilityAdmission.load());
        var outcomeCodec = new OfflineRepairTerminalOutcomeJsonCodec();
        Files.write(outputPath, outcomeCodec.write(outcome),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        assertEquals(ticket, outcomeCodec.read(Files.readAllBytes(outputPath)).ticket());
    }

    private static String outputName(OfflineRepairTerminalOutcome.Ticket ticket) {
        return switch (ticket) {
            case VRQ_08_PP_STRUCTUREV3_DEV_SHADOW -> "vrq08-outcome.json";
            case VRQ_09_TESSERACT_DEV_BASELINE -> "vrq09-outcome.json";
            default -> throw new IllegalArgumentException("OFFLINE_TERMINAL_TICKET_NOT_SUPPORTED");
        };
    }

    private static Path evidenceFile(String environmentName, String expectedName, boolean mustExist)
            throws Exception {
        var repository = repositoryRoot();
        var evidenceRootPath = repository.resolve(".sdlc/evidence").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(evidenceRootPath)
                || !Files.isDirectory(evidenceRootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("OFFLINE_TERMINAL_EVIDENCE_ROOT_INVALID");
        }
        var evidenceRoot = evidenceRootPath.toRealPath();
        var path = Path.of(required(environmentName)).toAbsolutePath().normalize();
        var parentPath = path.getParent();
        if (!path.startsWith(evidenceRootPath) || path.equals(evidenceRootPath)
                || !expectedName.equals(path.getFileName().toString())
                || Files.isSymbolicLink(parentPath)
                || !Files.isDirectory(parentPath, LinkOption.NOFOLLOW_LINKS)
                || !parentPath.toRealPath().startsWith(evidenceRoot)
                || mustExist != Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("OFFLINE_TERMINAL_EVIDENCE_PATH_INVALID");
        }
        return path;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the offline terminal gate");
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
