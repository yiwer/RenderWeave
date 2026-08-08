package cn.hbads.renderweave.inference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveCertificationEvaluationIdentityTest {
    @TempDir
    Path repository;

    @Test
    void authorizationLifecycleIsExcludedButAnyEvaluationInputDriftFailsClosed() throws Exception {
        git("init");
        var source = repository.resolve("src/evaluator.txt");
        var authorization = repository.resolve("plans/authorization.json");
        Files.createDirectories(source.getParent());
        Files.createDirectories(authorization.getParent());
        Files.writeString(source, "evaluator-v1");
        Files.writeString(authorization, "PROPOSED");
        git("add", "--", ".");

        var identity = new LiveCertificationEvaluationIdentity(repository, authorization);
        var approvedIdentity = identity.current();
        assertThat(approvedIdentity).startsWith(
                LiveCertificationEvaluationIdentity.VERSION + ":"
        );

        Files.writeString(authorization, "OPEN");
        identity.requireCurrent(approvedIdentity);

        Files.writeString(source, "evaluator-v2");
        assertThatThrownBy(() -> identity.requireCurrent(approvedIdentity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LIVE_CERTIFICATION_EVALUATION_IDENTITY_MISMATCH");
    }

    @Test
    void untrackedFilesCannotSilentlyJoinAMultiBatchEvaluation() throws Exception {
        git("init");
        var source = repository.resolve("src/evaluator.txt");
        var authorization = repository.resolve("plans/authorization.json");
        Files.createDirectories(source.getParent());
        Files.createDirectories(authorization.getParent());
        Files.writeString(source, "evaluator-v1");
        Files.writeString(authorization, "PROPOSED");
        git("add", "--", ".");
        var identity = new LiveCertificationEvaluationIdentity(repository, authorization);

        Files.writeString(repository.resolve("untracked-input.txt"), "drift");

        assertThatThrownBy(identity::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LIVE_CERTIFICATION_REPOSITORY_HAS_UNTRACKED_FILES");
    }

    @Test
    void authorizationMustBeAVersionedTrackedLedgerBeforeItCanBeExcluded() throws Exception {
        git("init");
        var source = repository.resolve("src/evaluator.txt");
        var authorization = repository.resolve("plans/authorization.json");
        Files.createDirectories(source.getParent());
        Files.createDirectories(authorization.getParent());
        Files.writeString(source, "evaluator-v1");
        git("add", "--", "src/evaluator.txt");
        Files.writeString(authorization, "OPEN");

        var identity = new LiveCertificationEvaluationIdentity(repository, authorization);

        assertThatThrownBy(identity::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LIVE_CERTIFICATION_AUTHORIZATION_NOT_TRACKED");
    }

    private void git(String... arguments) throws IOException, InterruptedException {
        var command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        var process = new ProcessBuilder(command)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start();
        var output = process.getInputStream().readAllBytes();
        var exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git test setup failed: " + new String(output));
        }
    }
}
