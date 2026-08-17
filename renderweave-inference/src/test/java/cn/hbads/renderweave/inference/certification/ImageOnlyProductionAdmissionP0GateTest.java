package cn.hbads.renderweave.inference.certification;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageOnlyProductionAdmissionP0GateTest {
    @Test
    void completeP0DryRunIsDeterministicPayloadSafeAndProviderZero() {
        var first = new ImageOnlyCertificationP0Evidence().generate();
        var second = new ImageOnlyCertificationP0Evidence().generate();
        var codec = new ImageOnlyCertificationP0ReportJsonCodec();
        var firstEnvelope = codec.envelope(first);
        var secondEnvelope = codec.envelope(second);

        assertEquals(firstEnvelope, secondEnvelope);
        assertEquals("22f561c88b30fabbf3ba660bcfe203fb570975f770ff122f2ce1c7216454ac0c",
                first.profile().canonicalSha256());
        assertEquals(12, first.profile().maximumTotalCalls());
        assertEquals(6_000_000L, first.profile().maximumRunCostMicrosCny());
        assertTrue(first.profile().hiddenFromProductCatalog());
        assertEquals(60, first.layeredR1().caseCount());
        assertEquals(58, first.layeredR1().metricCount());
        assertEquals(5, first.dryRun().canary().acceptedCases());
        assertEquals(18, first.dryRun().dev().acceptedCases());
        assertEquals(54, first.dryRun().finalStage().acceptedCases());
        assertTrue(first.dryRun().canary().passed());
        assertTrue(first.dryRun().dev().passed());
        assertTrue(first.dryRun().finalStage().passed());
        assertFalse(first.dryRun().negativeCanary().passed());
        assertFalse(first.dryRun().invalidKeyCanary().passed());
        assertEquals(4, first.dryRun().invalidKeyCanary().acceptedCases());
        assertTrue(first.dryRun().canary().verdicts().stream()
                .allMatch(item -> !item.keyShapes().isEmpty()));
        assertEquals(0, first.authorization().openAuthorizationCount());
        assertEquals(48, first.authorization().maximumWindowHours());
        assertEquals(1_000_000L, first.authorization().maximumModelTokens());
        assertEquals(0, first.externalProvider().attempts());
        assertEquals(0, first.externalProvider().reservations());
        assertEquals(0, first.externalProvider().costMicrosCny());
        assertEquals(0, first.externalProvider().apiKeyReads());
        var json = new String(codec.write(firstEnvelope), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(json.contains("DASHSCOPE_API_KEY"));
        assertFalse(json.contains("image/png"));
        assertFalse(json.contains("prompt"));
    }

    @Test
    void writesGateReportOnlyToANewEvidenceFile() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("RENDERWEAVE_RUN_IMAGE_ONLY_P0_GATE")));
        var outputValue = System.getenv("RENDERWEAVE_IMAGE_ONLY_P0_REPORT");
        if (outputValue == null || outputValue.isBlank()) {
            throw new IllegalArgumentException("IMAGE_ONLY_P0_REPORT_PATH_REQUIRED");
        }
        var repository = repositoryRoot();
        var evidenceRoot = repository.resolve(".sdlc").resolve("evidence").toRealPath();
        var output = Path.of(outputValue).toAbsolutePath().normalize();
        var parent = output.getParent();
        if (parent == null || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.toRealPath().startsWith(evidenceRoot)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("IMAGE_ONLY_P0_REPORT_PATH_INVALID");
        }
        var codec = new ImageOnlyCertificationP0ReportJsonCodec();
        Files.write(output, codec.write(codec.envelope(
                        new ImageOnlyCertificationP0Evidence().generate())),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        assertTrue(Files.size(output) > 0);
    }

    private static Path repositoryRoot() {
        var current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("renderweave-inference"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("REPOSITORY_ROOT_NOT_FOUND");
    }
}
