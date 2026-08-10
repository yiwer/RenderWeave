package cn.hbads.renderweave.inference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static void git(Path repository, String... arguments) throws Exception {
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
    }
}
