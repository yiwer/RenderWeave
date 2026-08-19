package cn.hbads.renderweave.template.internal;

import cn.hbads.renderweave.template.api.DesignDslAuthority;
import cn.hbads.renderweave.template.api.TemplateDependencyProjection;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T20 extraction primary: every fixture is admitted (proving it is a valid current-only
 * projection source), then AssetRefAtomExtractor emits the exact atoms and TemplateUse
 * occurrences. Writes the primary report used by the independent Python replay
 * (tools/verify-template-asset-ref-extraction.py).
 */
class AssetRefAtomExtractionTest {

    private static final String RESOURCE =
            "/cn/hbads/renderweave/template/asset-ref-extraction/fixtures.json";

    private final ObjectMapper json = new ObjectMapper();
    private final DesignDslAuthority designs = new CanonicalDesignDslAuthority();
    private final AssetRefAtomExtractor extractor = new AssetRefAtomExtractor();

    @Test
    void extractsExactAtomsFromEveryFixtureAndWritesThePrimaryReport() throws Exception {
        var fixtureBytes = readFixtures();
        var fixtures = json.readTree(fixtureBytes).required("fixtures");
        assertEquals(3, fixtures.size());

        var results = new ArrayList<Map<String, Object>>();
        for (var fixture : fixtures) {
            var id = fixture.required("id").asString();
            var dsl = fixture.required("designDsl").asString().getBytes(StandardCharsets.UTF_8);
            var admission = designs.admit(dsl);
            assertTrue(admission instanceof DesignDslAuthority.Admitted,
                    id + " must admit before extraction");
            var canonical = ((DesignDslAuthority.Admitted) admission).canonicalUtf8();
            var projection = extractor.extract(canonical);
            var atoms = projection.assetAtoms().stream()
                    .sorted(java.util.Comparator.comparing(
                            TemplateDependencyProjection.AssetRefAtom::canonicalPointer))
                    .map(atom -> Map.of(
                            "assetId", atom.assetId(),
                            "kind", atom.kind(),
                            "canonicalPointer", atom.canonicalPointer()
                    ))
                    .toList();
            var uses = projection.templateUses().stream()
                    .sorted(java.util.Comparator.comparing(
                            TemplateDependencyProjection.TemplateUseOccurrence::canonicalPointer))
                    .map(use -> Map.of(
                            "targetTemplateId", use.targetTemplateId(),
                            "canonicalPointer", use.canonicalPointer()
                    ))
                    .toList();
            var result = new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("assetAtoms", atoms);
            result.put("templateUses", uses);
            results.add(result);
        }

        assertEquals(2, ((List<?>) results.get(0).get("assetAtoms")).size());
        assertEquals(0, ((List<?>) results.get(0).get("templateUses")).size());
        assertEquals(5, ((List<?>) results.get(1).get("assetAtoms")).size());
        assertEquals(0, ((List<?>) results.get(1).get("templateUses")).size());
        assertEquals(1, ((List<?>) results.get(2).get("assetAtoms")).size());
        assertEquals(1, ((List<?>) results.get(2).get("templateUses")).size());

        writeReport(fixtureBytes, results);
    }

    private void writeReport(byte[] fixtureBytes, List<Map<String, Object>> results)
            throws IOException {
        var configured = System.getProperty("renderweave.template.assetRefReport", "").trim();
        if (configured.isEmpty()) {
            return;
        }
        var reportPath = Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(reportPath.getParent());
        var report = new LinkedHashMap<String, Object>();
        report.put("reportVersion", "renderweave-template-asset-ref-primary/1");
        report.put("engine", "java-primary");
        report.put("fixturesSha256", sha256(fixtureBytes));
        report.put("fixtures", results);
        json.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
    }

    private byte[] readFixtures() throws IOException {
        try (var input = AssetRefAtomExtractionTest.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing fixture resource " + RESOURCE);
            }
            return input.readAllBytes();
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
