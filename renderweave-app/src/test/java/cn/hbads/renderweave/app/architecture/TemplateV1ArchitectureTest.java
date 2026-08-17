package cn.hbads.renderweave.app.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateV1ArchitectureTest {

    private static final String INTERNAL_GROUP = "cn.hbads";
    private static final String APP = "renderweave-app";
    private static final List<String> TARGET_MODULES = List.of(
            "renderweave-schema",
            "renderweave-validation",
            "renderweave-inference",
            "renderweave-asset",
            "renderweave-template",
            "renderweave-rendering",
            APP
    );
    private static final Set<String> BASELINE_MODULES = Set.of(
            "renderweave-schema",
            "renderweave-validation",
            "renderweave-inference",
            APP
    );
    private static final Set<String> STAGED_MODULES = Set.of(
            "renderweave-asset",
            "renderweave-template",
            "renderweave-rendering"
    );
    private static final Map<String, Set<String>> ALLOWED_INTERNAL_DEPENDENCIES = Map.ofEntries(
            Map.entry("renderweave-schema", Set.of()),
            Map.entry("renderweave-validation", Set.of("renderweave-schema")),
            Map.entry("renderweave-inference", Set.of(
                    "renderweave-schema", "renderweave-validation"
            )),
            Map.entry("renderweave-asset", Set.of()),
            Map.entry("renderweave-template", Set.of(
                    "renderweave-schema", "renderweave-asset"
            )),
            Map.entry("renderweave-rendering", Set.of(
                    "renderweave-schema",
                    "renderweave-validation",
                    "renderweave-asset",
                    "renderweave-template"
            )),
            Map.entry(APP, Set.of(
                    "renderweave-schema",
                    "renderweave-validation",
                    "renderweave-inference",
                    "renderweave-asset",
                    "renderweave-template",
                    "renderweave-rendering"
            ))
    );
    private static final Map<String, Set<String>> BASELINE_EXACT_DEPENDENCIES = Map.of(
            "renderweave-schema", Set.of(),
            "renderweave-validation", Set.of("renderweave-schema"),
            "renderweave-inference", Set.of("renderweave-schema", "renderweave-validation"),
            APP, Set.of("renderweave-schema", "renderweave-validation", "renderweave-inference")
    );
    private static final Map<String, String> STAGED_PACKAGE_ROOTS = Map.of(
            "renderweave-asset", "cn.hbads.renderweave.asset",
            "renderweave-template", "cn.hbads.renderweave.template",
            "renderweave-rendering", "cn.hbads.renderweave.rendering"
    );
    private static final Set<String> FORBIDDEN_DOMAIN_REFERENCES = Set.of(
            "cn.hbads.renderweave.app",
            "org.springframework",
            "jakarta.servlet",
            "jakarta.persistence",
            "javax.persistence",
            "java.sql",
            "javax.sql",
            "java.lang.foreign",
            "com.sun.jna",
            "jnr.ffi"
    );
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;"
    );
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_][A-Za-z0-9_.]*)(?:\\.\\*)?\\s*;"
    );
    private static final Pattern DUMPING_GROUND_PACKAGE = Pattern.compile(
            "(^|\\.)(common|shared)(\\.|$)"
    );
    private static final Pattern PROCESS_BUILDER = Pattern.compile("\\bProcessBuilder\\b");
    private static final Pattern NATIVE_METHOD = Pattern.compile(
            "(?m)^\\s*(?:(?:public|protected|private|static|final|synchronized|abstract|strictfp)\\s+)*"
                    + "native\\s+[A-Za-z_$][A-Za-z0-9_$<>\\[\\].?]*\\s+"
                    + "[A-Za-z_$][A-Za-z0-9_$]*\\s*\\("
    );

    @Test
    void stagedReactorUsesOnlyFrozenCompileDirections() throws Exception {
        var root = repositoryRoot();
        var reactor = readReactor(root);

        assertTrue(
                reactor.modules().keySet().containsAll(BASELINE_MODULES),
                () -> "Missing established modules: " + difference(
                        BASELINE_MODULES, reactor.modules().keySet()
                )
        );
        assertTrue(
                Set.copyOf(TARGET_MODULES).containsAll(reactor.modules().keySet()),
                () -> "Unowned reactor modules: " + difference(
                        reactor.modules().keySet(), Set.copyOf(TARGET_MODULES)
                )
        );
        assertEquals(APP, reactor.order().getLast(), "renderweave-app must remain the leaf module");

        var actualGraph = new LinkedHashMap<String, Set<String>>();
        int compileEdgeCount = 0;
        for (var moduleName : reactor.order()) {
            var module = reactor.modules().get(moduleName);
            var internalDependencies = new LinkedHashSet<String>();
            for (var dependency : module.dependencies()) {
                if (!TARGET_MODULES.contains(dependency.artifactId())) {
                    continue;
                }
                assertEquals(
                        INTERNAL_GROUP,
                        dependency.groupId(),
                        () -> moduleName + " uses the target artifact name from a foreign group: "
                                + dependency.artifactId()
                );
                assertTrue(
                        reactor.modules().containsKey(dependency.artifactId()),
                        () -> moduleName + " depends on a target module absent from this reactor: "
                                + dependency.artifactId()
                );
                assertEquals(
                        "compile",
                        dependency.scope(),
                        () -> moduleName + " must not bypass the graph with a non-compile internal edge: "
                                + dependency
                );
                assertFalse(
                        dependency.optional(),
                        () -> moduleName + " must not make an internal edge optional: " + dependency
                );
                assertTrue(
                        internalDependencies.add(dependency.artifactId()),
                        () -> moduleName + " declares duplicate internal dependency "
                                + dependency.artifactId()
                );
            }

            var allowed = ALLOWED_INTERNAL_DEPENDENCIES.get(moduleName);
            assertTrue(
                    allowed.containsAll(internalDependencies),
                    () -> moduleName + " has forbidden internal edges "
                            + difference(internalDependencies, allowed)
            );
            if (BASELINE_MODULES.contains(moduleName)) {
                var baselineEdges = intersection(internalDependencies, BASELINE_MODULES);
                assertEquals(
                        BASELINE_EXACT_DEPENDENCIES.get(moduleName),
                        baselineEdges,
                        () -> moduleName + " changed its established compile graph"
                );
            }
            actualGraph.put(moduleName, Set.copyOf(internalDependencies));
            compileEdgeCount += internalDependencies.size();
        }

        assertTrue(compileEdgeCount >= 6, "the graph check must exercise established real edges");
        assertDoesNotThrow(() -> verifyAcyclic(actualGraph));
        assertTopologicalReactorOrder(reactor.order(), actualGraph);

        for (var stagedModule : STAGED_MODULES) {
            if (!reactor.modules().containsKey(stagedModule)) {
                continue;
            }
            assertTrue(
                    countJavaSources(root.resolve(stagedModule).resolve("src/main/java")) > 0,
                    () -> stagedModule + " must enter the reactor with real production code"
            );
            assertTrue(
                    countJavaSources(root.resolve(stagedModule).resolve("src/test/java")) > 0,
                    () -> stagedModule + " must enter the reactor with executable tests"
            );
        }
    }

    @Test
    void productionPackagesHaveOneArtifactOwnerAndNoDumpingGround() throws Exception {
        var root = repositoryRoot();
        var reactor = readReactor(root);
        var sources = readProductionSources(root, reactor.order());

        assertTrue(sources.size() > 50, "package ownership must inspect the real production tree");
        var owners = packageOwners(sources);
        assertTrue(owners.size() > 20, "package ownership must inspect real packages");
        verifyUniquePackageOwners(owners);

        for (var source : sources) {
            assertFalse(
                    DUMPING_GROUND_PACKAGE.matcher(source.packageName()).find(),
                    () -> "generic dumping-ground package is forbidden: " + source
            );
            if (STAGED_PACKAGE_ROOTS.containsKey(source.module())) {
                var ownedRoot = STAGED_PACKAGE_ROOTS.get(source.module());
                assertTrue(
                        source.packageName().equals(ownedRoot)
                                || source.packageName().startsWith(ownedRoot + "."),
                        () -> source.module() + " source is outside its owned package root: " + source
                );
            }
            if (source.module().equals(APP)) {
                for (var domainRoot : STAGED_PACKAGE_ROOTS.values()) {
                    assertFalse(
                            source.packageName().equals(domainRoot)
                                    || source.packageName().startsWith(domainRoot + "."),
                            () -> "app Adapter must not occupy a domain package: " + source
                    );
                }
            }
            verifyStagedCrossModuleImports(source);
        }
    }

    @Test
    void domainSourcesCannotOwnFrameworkOrNativeProcessAdapters() throws Exception {
        var root = repositoryRoot();
        var reactor = readReactor(root);
        var domainModules = reactor.order().stream()
                .filter(module -> !module.equals(APP))
                .toList();
        var sources = readProductionSources(root, domainModules);

        assertTrue(sources.size() > 30, "capability isolation must inspect real domain sources");
        verifyDomainCapabilityIsolation(sources);
    }

    @Test
    void guardsRejectSyntheticCycleSplitPackageAndNativeAdapter() {
        var cyclic = Map.of(
                "renderweave-asset", Set.of("renderweave-template"),
                "renderweave-template", Set.of("renderweave-asset")
        );
        assertThrows(ArchitectureViolation.class, () -> verifyAcyclic(cyclic));

        var split = Map.of(
                "cn.hbads.renderweave.validation",
                Set.of("renderweave-validation", APP)
        );
        assertThrows(ArchitectureViolation.class, () -> verifyUniquePackageOwners(split));

        var nativeAdapter = new SourceUnit(
                "renderweave-rendering",
                Path.of("SyntheticNativeRenderer.java"),
                "cn.hbads.renderweave.rendering.internal",
                """
                        package cn.hbads.renderweave.rendering.internal;
                        final class SyntheticNativeRenderer {
                            native void execute();
                        }
                        """
        );
        assertThrows(
                ArchitectureViolation.class,
                () -> verifyDomainCapabilityIsolation(List.of(nativeAdapter))
        );
    }

    private static Reactor readReactor(Path root) throws Exception {
        var rootDocument = parseXml(root.resolve("pom.xml"));
        var modulesElement = directChild(rootDocument.getDocumentElement(), "modules")
                .orElseThrow(() -> new ArchitectureViolation("root POM has no modules"));
        var order = directChildren(modulesElement, "module").stream()
                .map(Element::getTextContent)
                .map(String::trim)
                .toList();
        var modules = new LinkedHashMap<String, MavenModule>();
        for (var moduleName : order) {
            var pom = root.resolve(moduleName).resolve("pom.xml");
            var document = parseXml(pom);
            var project = document.getDocumentElement();
            var artifactId = directChildText(project, "artifactId")
                    .orElseThrow(() -> new ArchitectureViolation(pom + " has no artifactId"));
            if (!artifactId.equals(moduleName)) {
                throw new ArchitectureViolation(
                        "reactor directory " + moduleName + " owns artifact " + artifactId
                );
            }
            var dependencies = new ArrayList<MavenDependency>();
            directChild(project, "dependencies").ifPresent(container -> {
                for (var dependency : directChildren(container, "dependency")) {
                    dependencies.add(new MavenDependency(
                            directChildText(dependency, "groupId").orElse(""),
                            directChildText(dependency, "artifactId").orElse(""),
                            directChildText(dependency, "scope").orElse("compile"),
                            directChildText(dependency, "optional")
                                    .map(Boolean::parseBoolean)
                                    .orElse(false)
                    ));
                }
            });
            modules.put(moduleName, new MavenModule(moduleName, List.copyOf(dependencies)));
        }
        return new Reactor(List.copyOf(order), Map.copyOf(modules));
    }

    private static Document parseXml(Path path) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (InputStream input = Files.newInputStream(path)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private static Optional<Element> directChild(Element parent, String localName) {
        for (var child : directChildren(parent, localName)) {
            return Optional.of(child);
        }
        return Optional.empty();
    }

    private static Optional<String> directChildText(Element parent, String localName) {
        return directChild(parent, localName)
                .map(Element::getTextContent)
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    private static List<Element> directChildren(Element parent, String localName) {
        var result = new ArrayList<Element>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && localName.equals(nodeName(element))) {
                result.add(element);
            }
        }
        return result;
    }

    private static String nodeName(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static List<SourceUnit> readProductionSources(Path root, List<String> modules)
            throws IOException {
        var sources = new ArrayList<SourceUnit>();
        for (var module : modules) {
            var sourceRoot = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                files = paths.filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList();
            }
            for (var path : files) {
                var content = Files.readString(path, StandardCharsets.UTF_8);
                var matcher = PACKAGE_DECLARATION.matcher(content);
                if (!matcher.find()) {
                    throw new ArchitectureViolation("production source has no package: " + path);
                }
                sources.add(new SourceUnit(module, path, matcher.group(1), content));
            }
        }
        return List.copyOf(sources);
    }

    private static long countJavaSources(Path sourceRoot) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(path -> path.toString().endsWith(".java")).count();
        }
    }

    private static Map<String, Set<String>> packageOwners(List<SourceUnit> sources) {
        var mutable = new LinkedHashMap<String, Set<String>>();
        for (var source : sources) {
            mutable.computeIfAbsent(source.packageName(), ignored -> new LinkedHashSet<>())
                    .add(source.module());
        }
        var result = new LinkedHashMap<String, Set<String>>();
        mutable.forEach((packageName, modules) -> result.put(packageName, Set.copyOf(modules)));
        return Map.copyOf(result);
    }

    private static void verifyUniquePackageOwners(Map<String, Set<String>> owners) {
        for (var entry : owners.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw new ArchitectureViolation(
                        "split production package " + entry.getKey() + " owned by " + entry.getValue()
                );
            }
        }
    }

    private static void verifyStagedCrossModuleImports(SourceUnit source) {
        if (!STAGED_MODULES.contains(source.module())) {
            return;
        }
        var ownRoot = STAGED_PACKAGE_ROOTS.get(source.module());
        for (var importedType : imports(source.content())) {
            for (var foreignRoot : STAGED_PACKAGE_ROOTS.values()) {
                if (foreignRoot.equals(ownRoot)
                        || !(importedType.equals(foreignRoot)
                        || importedType.startsWith(foreignRoot + "."))) {
                    continue;
                }
                if (!(importedType.equals(foreignRoot + ".api")
                        || importedType.startsWith(foreignRoot + ".api."))) {
                    throw new ArchitectureViolation(
                            source + " imports a foreign non-api package: " + importedType
                    );
                }
            }
        }
    }

    private static void verifyDomainCapabilityIsolation(List<SourceUnit> sources) {
        for (var source : sources) {
            for (var importedType : imports(source.content())) {
                for (var forbidden : FORBIDDEN_DOMAIN_REFERENCES) {
                    if (importedType.equals(forbidden) || importedType.startsWith(forbidden + ".")) {
                        throw new ArchitectureViolation(
                                source + " imports forbidden Adapter capability " + importedType
                        );
                    }
                }
            }
            if (PROCESS_BUILDER.matcher(source.content()).find()
                    || NATIVE_METHOD.matcher(source.content()).find()
                    || source.content().contains("System.loadLibrary(")
                    || source.content().contains("System.load(")) {
                throw new ArchitectureViolation(
                        source + " owns process/native capability reserved for an app Adapter"
                );
            }
        }
    }

    private static Set<String> imports(String content) {
        var result = new LinkedHashSet<String>();
        var matcher = IMPORT_DECLARATION.matcher(content);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return Set.copyOf(result);
    }

    private static void verifyAcyclic(Map<String, Set<String>> graph) {
        var complete = new LinkedHashSet<>(graph.keySet());
        graph.values().forEach(complete::addAll);
        var visiting = new LinkedHashSet<String>();
        var visited = new LinkedHashSet<String>();
        var path = new ArrayDeque<String>();
        for (var node : complete) {
            visit(node, graph, visiting, visited, path);
        }
    }

    private static void visit(
            String node,
            Map<String, Set<String>> graph,
            Set<String> visiting,
            Set<String> visited,
            Deque<String> path
    ) {
        if (visited.contains(node)) {
            return;
        }
        if (!visiting.add(node)) {
            throw new ArchitectureViolation("compile dependency cycle: " + path + " -> " + node);
        }
        path.addLast(node);
        for (var dependency : graph.getOrDefault(node, Set.of())) {
            visit(dependency, graph, visiting, visited, path);
        }
        path.removeLast();
        visiting.remove(node);
        visited.add(node);
    }

    private static void assertTopologicalReactorOrder(
            List<String> order,
            Map<String, Set<String>> graph
    ) {
        var positions = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < order.size(); index++) {
            positions.put(order.get(index), index);
        }
        for (var entry : graph.entrySet()) {
            for (var dependency : entry.getValue()) {
                assertTrue(
                        positions.get(dependency) < positions.get(entry.getKey()),
                        () -> dependency + " must precede dependent module " + entry.getKey()
                );
            }
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        var result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return Set.copyOf(result);
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        var result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return Set.copyOf(result);
    }

    private static Path repositoryRoot() {
        var configured = System.getProperty("maven.multiModuleProjectDirectory");
        if (configured != null && !configured.isBlank()) {
            var candidate = Path.of(configured).toAbsolutePath().normalize();
            if (isRepositoryRoot(candidate)) {
                return candidate;
            }
        }
        var candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (isRepositoryRoot(candidate)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new ArchitectureViolation("cannot locate RenderWeave reactor root");
    }

    private static boolean isRepositoryRoot(Path candidate) {
        return Files.isRegularFile(candidate.resolve("pom.xml"))
                && Files.isRegularFile(candidate.resolve(APP).resolve("pom.xml"));
    }

    private record Reactor(List<String> order, Map<String, MavenModule> modules) {
    }

    private record MavenModule(String artifactId, List<MavenDependency> dependencies) {
    }

    private record MavenDependency(
            String groupId,
            String artifactId,
            String scope,
            boolean optional
    ) {
    }

    private record SourceUnit(
            String module,
            Path path,
            String packageName,
            String content
    ) {
        @Override
        public String toString() {
            return module + ":" + path;
        }
    }

    private static final class ArchitectureViolation extends RuntimeException {
        private ArchitectureViolation(String message) {
            super(message);
        }
    }
}
