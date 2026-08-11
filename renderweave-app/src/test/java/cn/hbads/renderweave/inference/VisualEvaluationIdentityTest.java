package cn.hbads.renderweave.inference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualEvaluationIdentityTest {
    @Test
    void identityExcludesOnlyTrackedLedgersAndRejectsAnyDirtyOrUntrackedInput(@TempDir Path repository)
            throws Exception {
        git(repository, "init");
        git(repository, "config", "user.email", "visual-eval@example.test");
        git(repository, "config", "user.name", "Visual Eval Test");
        var input = repository.resolve("input.txt");
        var max = repository.resolve("max.json");
        var plus = repository.resolve("plus.json");
        var flash = repository.resolve("flash.json");
        Files.writeString(input, "input-v1");
        Files.writeString(max, "PROPOSED");
        Files.writeString(plus, "PROPOSED");
        Files.writeString(flash, "PROPOSED");
        git(repository, "add", ".");
        git(repository, "commit", "-m", "initial");
        var identity = new VisualEvaluationIdentity(repository, List.of(max, plus, flash));
        var first = identity.current();

        Files.writeString(max, "OPEN");
        assertThrows(IllegalStateException.class, identity::current);
        git(repository, "add", "max.json");
        git(repository, "commit", "-m", "open max");
        assertEquals(first, identity.current());

        Files.writeString(input, "input-v2");
        assertThrows(IllegalStateException.class, identity::current);
        git(repository, "add", "input.txt");
        git(repository, "commit", "-m", "change input");
        assertNotEquals(first, identity.current());

        Files.writeString(repository.resolve("untracked.txt"), "unsafe");
        assertThrows(IllegalStateException.class, identity::current);
    }

    @Test
    void untrackedAuthorizationCannotBeExcluded(@TempDir Path repository) throws Exception {
        git(repository, "init");
        git(repository, "config", "user.email", "visual-eval@example.test");
        git(repository, "config", "user.name", "Visual Eval Test");
        Files.writeString(repository.resolve("input.txt"), "input");
        git(repository, "add", "input.txt");
        git(repository, "commit", "-m", "initial");
        var ledger = repository.resolve("ledger.json");
        Files.writeString(ledger, "PROPOSED");

        var identity = new VisualEvaluationIdentity(repository, List.of(ledger));
        assertThrows(IllegalStateException.class, identity::current);
    }

    @Test
    void versionTwoUsesCanonicalGitBlobsWhileVersionOneRemainsReadOnlyCompatible(
            @TempDir Path directory
    ) throws Exception {
        var seed = directory.resolve("seed");
        Files.createDirectories(seed);
        git(seed, "init");
        git(seed, "config", "user.email", "visual-eval@example.test");
        git(seed, "config", "user.name", "Visual Eval Test");
        git(seed, "config", "core.filemode", "false");
        git(seed, "config", "core.autocrlf", "false");
        var input = seed.resolve("input.txt");
        var max = seed.resolve("max.json");
        var plus = seed.resolve("plus.json");
        var flash = seed.resolve("flash.json");
        Files.writeString(input, "line-1\nline-2\n");
        Files.writeString(max, "PROPOSED");
        Files.writeString(plus, "PROPOSED");
        Files.writeString(flash, "PROPOSED");
        git(seed, "add", ".");
        git(seed, "commit", "-m", "initial");

        git(directory, "-c", "core.autocrlf=false", "clone", seed.toString(), "lf");
        git(directory, "-c", "core.autocrlf=true", "clone", seed.toString(), "crlf");
        var lf = directory.resolve("lf");
        var crlf = directory.resolve("crlf");
        git(lf, "config", "core.autocrlf", "false");
        git(crlf, "config", "core.autocrlf", "true");
        assertEquals("", git(lf, "status", "--porcelain=v1"));
        assertEquals("", git(crlf, "status", "--porcelain=v1"));
        assertTrue(Files.readString(crlf.resolve("input.txt")).contains("\r\n"));

        var lfIdentity = new VisualEvaluationIdentity(lf, List.of(
                lf.resolve("max.json"), lf.resolve("plus.json"), lf.resolve("flash.json")));
        var crlfIdentity = new VisualEvaluationIdentity(crlf, List.of(
                crlf.resolve("max.json"), crlf.resolve("plus.json"), crlf.resolve("flash.json")));
        var canonical = lfIdentity.current();
        var legacyLf = lfIdentity.current(VisualEvaluationIdentity.LEGACY_VERSION);
        var legacyCrlf = crlfIdentity.current(VisualEvaluationIdentity.LEGACY_VERSION);
        assertEquals(canonical, crlfIdentity.current());
        assertTrue(canonical.startsWith(VisualEvaluationIdentity.VERSION + ":"));
        assertTrue(legacyLf.startsWith(VisualEvaluationIdentity.LEGACY_VERSION + ":"));
        assertNotEquals(legacyLf, legacyCrlf);
        lfIdentity.requireCurrent(canonical);
        crlfIdentity.requireCurrent(legacyCrlf);
        assertThrows(IllegalArgumentException.class,
                () -> lfIdentity.current("renderweave-visual-evaluation-tree-sha256/3"));
    }

    @Test
    void versionTwoBindsCommittedRegularFileMode(@TempDir Path repository) throws Exception {
        git(repository, "init");
        git(repository, "config", "user.email", "visual-eval@example.test");
        git(repository, "config", "user.name", "Visual Eval Test");
        git(repository, "config", "core.filemode", "false");
        var input = repository.resolve("input.txt");
        var max = repository.resolve("max.json");
        var plus = repository.resolve("plus.json");
        var flash = repository.resolve("flash.json");
        Files.writeString(input, "input");
        Files.writeString(max, "PROPOSED");
        Files.writeString(plus, "PROPOSED");
        Files.writeString(flash, "PROPOSED");
        git(repository, "add", ".");
        git(repository, "commit", "-m", "initial");
        var identity = new VisualEvaluationIdentity(repository, List.of(max, plus, flash));
        var before = identity.current();

        git(repository, "update-index", "--chmod=+x", "input.txt");
        git(repository, "commit", "-m", "make input executable");

        assertNotEquals(before, identity.current());
    }

    @Test
    void versionTwoRejectsIndexFlagsThatHideWorkingTreeDrift(@TempDir Path repository)
            throws Exception {
        git(repository, "init");
        git(repository, "config", "user.email", "visual-eval@example.test");
        git(repository, "config", "user.name", "Visual Eval Test");
        var input = repository.resolve("input.txt");
        var max = repository.resolve("max.json");
        var plus = repository.resolve("plus.json");
        var flash = repository.resolve("flash.json");
        Files.writeString(input, "input");
        Files.writeString(max, "PROPOSED");
        Files.writeString(plus, "PROPOSED");
        Files.writeString(flash, "PROPOSED");
        git(repository, "add", ".");
        git(repository, "commit", "-m", "initial");
        var identity = new VisualEvaluationIdentity(repository, List.of(max, plus, flash));

        git(repository, "update-index", "--assume-unchanged", "input.txt");
        Files.writeString(input, "hidden-drift");

        assertThrows(IllegalStateException.class, identity::current);
    }

    private static String git(Path repository, String... arguments) throws Exception {
        var command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        var builder = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true);
        List.of("DASHSCOPE_API_KEY", "DASHSCOPE_API_KEY_FILE", "RENDERWEAVE_RUN_LIVE_CANARY",
                "RENDERWEAVE_RUN_LIVE_CERTIFICATION", "RENDERWEAVE_RUN_VISUAL_EVALUATION")
                .forEach(key -> builder.environment().remove(key));
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output.strip();
    }
}
