package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.live.R5ProductTransformEvaluation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Explicit opt-in proof that the terminal R5 experiment cannot be rerun. */
@EnabledIfEnvironmentVariable(named = "RENDERWEAVE_RUN_R5_PRODUCT_TRANSFORM", matches = "true")
class R5ProductTransformGateTest {
    @Test
    void refusesTheClosedRouteBeforeAnyAcquisitionOrEvidenceWrite() throws Exception {
        var output = requiredOutput();
        var failure = assertThrows(IllegalStateException.class,
                () -> new R5ProductTransformEvaluation().evaluate(runOrdinal -> {
                    throw new AssertionError("acquisition factory must not be invoked");
                }));

        assertEquals("R5_PRODUCT_TRANSFORM_ROUTE_CLOSED", failure.getMessage());
        assertFalse(Files.exists(output));
    }

    private static Path requiredOutput() throws Exception {
        var requested = required("RENDERWEAVE_R5_PRODUCT_TRANSFORM_EVIDENCE");
        var repository = repositoryRoot();
        var evidenceRootPath = repository.resolve(".sdlc/evidence").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(evidenceRootPath)
                || !Files.isDirectory(evidenceRootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5_PRODUCT_EVIDENCE_ROOT_INVALID");
        }
        var evidenceRoot = evidenceRootPath.toRealPath();
        var output = Path.of(requested).toAbsolutePath().normalize();
        var parent = output.getParent();
        if (!output.startsWith(evidenceRootPath) || output.equals(evidenceRootPath)
                || !"r5-product-transform-evidence.json".equals(output.getFileName().toString())
                || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.toRealPath().startsWith(evidenceRoot)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("R5_PRODUCT_EVIDENCE_PATH_INVALID");
        }
        return output;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the R5 product-transform gate");
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
