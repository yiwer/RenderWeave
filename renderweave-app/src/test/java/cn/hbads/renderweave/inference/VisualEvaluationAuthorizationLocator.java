package cn.hbads.renderweave.inference;

import java.nio.file.Path;
import java.util.Map;

/** Fixed tracked ledger selector; arbitrary paths are intentionally unsupported. */
final class VisualEvaluationAuthorizationLocator {
    static final String SELECTOR_ENVIRONMENT_VARIABLE = "RENDERWEAVE_VISUAL_EVALUATION_AUTHORIZATION";
    static final Map<String, String> LEDGERS = Map.of(
            "qwen38-max", ".sdlc/live/visual-evaluation-qwen38-max.json",
            "qwen37-plus", ".sdlc/live/visual-evaluation-qwen37-plus.json",
            "qwen37-flash", ".sdlc/live/visual-evaluation-qwen37-flash.json"
    );

    private VisualEvaluationAuthorizationLocator() { }

    static Path resolve(Path repositoryRoot) {
        return resolve(repositoryRoot, System.getenv(SELECTOR_ENVIRONMENT_VARIABLE));
    }

    static Path resolve(Path repositoryRoot, String selector) {
        var relative = selector == null ? null : LEDGERS.get(selector);
        if (relative == null) {
            throw new IllegalStateException("VISUAL_EVALUATION_AUTHORIZATION_SELECTOR_INVALID");
        }
        return repositoryRoot.toAbsolutePath().normalize().resolve(relative).normalize();
    }

    static java.util.List<Path> all(Path repositoryRoot) {
        var root = repositoryRoot.toAbsolutePath().normalize();
        return LEDGERS.values().stream().sorted().map(root::resolve).toList();
    }
}
