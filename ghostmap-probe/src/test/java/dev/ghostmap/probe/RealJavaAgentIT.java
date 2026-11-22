package dev.ghostmap.probe;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end proof that premain instruments code in separate target JVMs. */
class RealJavaAgentIT {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    static Path classWorkspace;

    private static Path targetClasses;
    private static Path agentJar;

    @BeforeAll
    static void compilePlainJavaTarget() throws IOException {
        agentJar = Path.of(System.getProperty("probe.agent.jar")).toAbsolutePath();
        assertTrue(Files.isRegularFile(agentJar), "shaded agent jar must exist before integration tests");
        Path sources = classWorkspace.resolve("sources");
        targetClasses = classWorkspace.resolve("classes");
        writeSources(sources);
        Files.createDirectories(targetClasses);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "integration tests require a JDK");
        List<Path> sourceFiles;
        try (var paths = Files.walk(sources)) {
            sourceFiles = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var units = files.getJavaFileObjectsFromPaths(sourceFiles);
            Boolean success = compiler.getTask(null, files, null,
                    List.of("--release", "17", "-d", targetClasses.toString()), null, units).call();
            assertEquals(Boolean.TRUE, success, "plain Java child target must compile");
        }
    }

    @Test
    void recordsRealCallsAndDispatchKindsInChildJvm(@TempDir Path runDirectory) throws Exception {
        ProbeFiles files = probeFiles(runDirectory);

        ChildResult child = run(files, 100, "happy");

        assertHealthyAndSilent(child);
        String json = Files.readString(files.output(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schema\":\"ghostmap.runtime/v1\""));
        assertTrue(json.contains("\"runId\":\"it-run\""));
        assertTrue(json.contains("\"startedAt\":"));
        assertTrue(json.contains("\"endedAt\":"));
        Instant startedAt = Instant.parse(stringField(json, "startedAt"));
        Instant endedAt = Instant.parse(stringField(json, "endedAt"));
        assertFalse(endedAt.isBefore(startedAt));
        assertTrue(json.contains("\"include\":[\"probe.fixture\"]"));
        assertEdge(json, "directCase", "hit", "UNKNOWN");
        assertHitsAtLeast(json, "directCase", "hit", 2);
        assertEdge(json, "reflectCase", "reflected", "REFLECTION");
        assertCalleeDispatch(json, "proxyHit", "PROXY");
        assertCalleeDispatch(json, "lambdaHit", "LAMBDA");
        assertEdge(json, "getBeanCase", "getBean", "DYNAMIC");
        assertTrue(Files.readString(files.log(), StandardCharsets.UTF_8).contains("INSTRUMENTATION_INSTALLED"));
    }

    @Test
    void excludesConfiguredAndOutOfFilterClasses(@TempDir Path runDirectory) throws Exception {
        ProbeFiles files = probeFiles(runDirectory);

        ChildResult child = run(files, 100, "exclude");

        assertHealthyAndSilent(child);
        String json = Files.readString(files.output(), StandardCharsets.UTF_8);
        assertFalse(json.contains("ExcludedWork"));
        assertFalse(json.contains("OutsideWork"));
        assertTrue(json.contains("\"name\":\"one\""));
    }

    @Test
    void boundsDistinctPairsDropsExcessAndStillIncrementsExistingPair(@TempDir Path runDirectory) throws Exception {
        ProbeFiles files = probeFiles(runDirectory);

        ChildResult child = run(files, 1, "bounded");

        assertHealthyAndSilent(child);
        String json = Files.readString(files.output(), StandardCharsets.UTF_8);
        assertEquals(1, occurrences(json, "\"caller\":"));
        assertEdge(json, "main", "one", "UNKNOWN");
        assertHitsAtLeast(json, "main", "one", 2);
        assertTrue(numberField(json, "droppedEvents") >= 2);
        assertTrue(Files.readString(files.log(), StandardCharsets.UTF_8).contains("droppedEvents="));
    }

    @Test
    void flushesCompleteSnapshotsOnTriggerAndShutdown(@TempDir Path runDirectory) throws Exception {
        ProbeFiles files = probeFiles(runDirectory);

        ChildResult child = run(files, 100, "trigger", files.trigger().toString());

        assertHealthyAndSilent(child);
        List<String> lines = Files.readAllLines(files.output(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"reason\":\"trigger\""));
        assertTrue(lines.get(1).contains("\"reason\":\"shutdown\""));
        for (String line : lines) {
            assertTrue(line.startsWith("{\"schema\":\"ghostmap.runtime/v1\""));
            assertTrue(line.contains("\"edges\":["));
            assertTrue(line.endsWith("}"));
        }
        assertFalse(Files.exists(files.trigger()), "successful trigger dump consumes the trigger file");
    }

    @Test
    void invalidOutputDisablesProbeWithoutCrashingTarget(@TempDir Path runDirectory) throws Exception {
        ProbeFiles files = probeFiles(runDirectory);
        Files.createDirectories(files.output());

        ChildResult child = run(files, 100, "happy");

        assertHealthyAndSilent(child);
        assertTrue(Files.isDirectory(files.output()));
        String log = Files.readString(files.log(), StandardCharsets.UTF_8);
        assertTrue(log.contains("DISABLED agent startup failed"));
    }

    private static ChildResult run(ProbeFiles files, int edgeLimit, String scenario, String... scenarioArguments)
            throws Exception {
        String options = "include=probe.fixture,exclude=probe.fixture.excluded,out=" + files.output()
                + ",edgeLimit=" + edgeLimit + ",trigger=" + files.trigger() + ",triggerPollMillis=25,log="
                + files.log() + ",runId=it-run";
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-javaagent:" + agentJar + '=' + options);
        command.add("-cp");
        command.add(targetClasses.toString());
        command.add("probe.fixture.ProbeTarget");
        command.add(scenario);
        command.addAll(List.of(scenarioArguments));
        Process process = new ProcessBuilder(command).start();
        boolean finished = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("child JVM exceeded " + PROCESS_TIMEOUT);
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ChildResult(process.exitValue(), stdout, stderr);
    }

    private static void assertHealthyAndSilent(ChildResult child) {
        assertEquals(0, child.exitCode(), "probe must not change the target exit code");
        assertEquals("APP-OUT" + System.lineSeparator(), child.stdout());
        assertEquals("APP-ERR" + System.lineSeparator(), child.stderr());
    }

    private static void assertEdge(String json, String caller, String callee, String dispatch) {
        Pattern pattern = Pattern.compile("\\\"caller\\\":\\{[^}]*\\\"name\\\":\\\""
                + Pattern.quote(caller) + "\\\"[^}]*},\\\"callee\\\":\\{[^}]*\\\"name\\\":\\\""
                + Pattern.quote(callee) + "\\\"[^}]*}[^}]*\\\"dispatch\\\":\\\""
                + Pattern.quote(dispatch) + "\\\"");
        assertTrue(pattern.matcher(json).find(), () -> "missing " + caller + " -> " + callee
                + " with dispatch " + dispatch + " in " + json);
    }

    private static void assertHitsAtLeast(String json, String caller, String callee, long expected) {
        Pattern pattern = Pattern.compile("\\\"caller\\\":\\{[^}]*\\\"name\\\":\\\""
                + Pattern.quote(caller) + "\\\"[^}]*},\\\"callee\\\":\\{[^}]*\\\"name\\\":\\\""
                + Pattern.quote(callee) + "\\\"[^}]*},\\\"hits\\\":(\\d+)");
        Matcher matcher = pattern.matcher(json);
        assertTrue(matcher.find(), () -> "missing edge hit count for " + caller + " -> " + callee);
        assertTrue(Long.parseLong(matcher.group(1)) >= expected,
                () -> "expected at least " + expected + " hits in " + json);
    }

    private static void assertCalleeDispatch(String json, String callee, String dispatch) {
        Pattern pattern = Pattern.compile("\\\"callee\\\":\\{[^}]*\\\"name\\\":\\\""
                + Pattern.quote(callee) + "\\\"[^}]*}[^}]*\\\"dispatch\\\":\\\""
                + Pattern.quote(dispatch) + "\\\"");
        assertTrue(pattern.matcher(json).find(),
                () -> "missing actual callee " + callee + " with dispatch " + dispatch + " in " + json);
    }

    private static String stringField(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\":\\\"([^\\\"]+)\\\"")
                .matcher(json);
        assertTrue(matcher.find(), () -> "missing string field " + name);
        return matcher.group(1);
    }

    private static long numberField(String json, String name) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(name) + "\\\":(\\d+)").matcher(json);
        assertTrue(matcher.find(), () -> "missing numeric field " + name);
        return Long.parseLong(matcher.group(1));
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }

    private static ProbeFiles probeFiles(Path directory) {
        return new ProbeFiles(directory.resolve("runtime-edges.jsonl"), directory.resolve("probe.log"),
                directory.resolve("dump.trigger"));
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private static void writeSources(Path root) throws IOException {
        write(root.resolve("probe/fixture/ProbeTarget.java"), """
                package probe.fixture;

                import java.lang.reflect.Method;
                import java.lang.reflect.Proxy;
                import java.nio.file.Files;
                import java.nio.file.Path;

                public final class ProbeTarget {
                    private ProbeTarget() { }

                    public static void main(String[] args) throws Exception {
                        System.out.println("APP-OUT");
                        System.err.println("APP-ERR");
                        switch (args[0]) {
                            case "happy" -> Work.happy();
                            case "exclude" -> Work.excludeCase();
                            case "bounded" -> {
                                Target.one();
                                Target.two();
                                Target.three();
                                Target.one();
                            }
                            case "trigger" -> {
                                Target.one();
                                Files.writeString(Path.of(args[1]), "dump");
                                Thread.sleep(1000);
                                Target.one();
                            }
                            default -> throw new IllegalArgumentException(args[0]);
                        }
                    }

                    static final class Work {
                        static void happy() throws Exception {
                            directCase();
                            directCase();
                            reflectCase();
                            proxyCase();
                            lambdaCase();
                            getBeanCase();
                        }

                        static void directCase() { Target.hit(); }

                        static void reflectCase() throws Exception {
                            Method method = Target.class.getDeclaredMethod("reflected");
                            method.invoke(null);
                        }

                        static void proxyCase() {
                            Action action = (Action) Proxy.newProxyInstance(
                                    ProbeTarget.class.getClassLoader(), new Class<?>[]{Action.class},
                                    (proxy, method, arguments) -> {
                                        Target.proxyHit();
                                        return null;
                                    });
                            action.run();
                        }

                        static void lambdaCase() {
                            Runnable runnable = () -> Target.lambdaHit();
                            runnable.run();
                        }

                        static void getBeanCase() { new BeanRegistry().getBean(); }

                        static void excludeCase() {
                            probe.fixture.excluded.ExcludedWork.run();
                            other.fixture.OutsideWork.run();
                            Target.one();
                        }
                    }

                    interface Action { void run(); }

                    static final class BeanRegistry {
                        Object getBean() { return new Object(); }
                    }

                    static final class Target {
                        static int calls;
                        static void hit() { calls++; }
                        static void reflected() { calls++; }
                        static void proxyHit() { calls++; }
                        static void lambdaHit() { calls++; }
                        static void one() { calls++; }
                        static void two() { calls++; }
                        static void three() { calls++; }
                    }
                }
                """);
        write(root.resolve("probe/fixture/excluded/ExcludedWork.java"), """
                package probe.fixture.excluded;
                public final class ExcludedWork {
                    private ExcludedWork() { }
                    public static void run() { }
                }
                """);
        write(root.resolve("other/fixture/OutsideWork.java"), """
                package other.fixture;
                public final class OutsideWork {
                    private OutsideWork() { }
                    public static void run() { }
                }
                """);
    }

    private static void write(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);
    }

    private record ProbeFiles(Path output, Path log, Path trigger) {
    }

    private record ChildResult(int exitCode, String stdout, String stderr) {
    }
}
