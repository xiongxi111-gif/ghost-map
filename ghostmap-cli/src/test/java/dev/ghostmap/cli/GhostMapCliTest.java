package dev.ghostmap.cli;

import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.JsonValue.BooleanValue;
import dev.ghostmap.model.Snapshot;
import dev.ghostmap.model.SnapshotJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GhostMapCliTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void indexesAstOnlyAndSourcePlusClassesIntoRoundTrippableSnapshots() throws Exception {
        CliTestCompiler.CompiledSample sample = CliTestCompiler.compile(temporaryDirectory.resolve("sample"),
                "sample.Ghost", """
                        package sample;
                        public record Ghost(String name) {
                        }
                        class Caller {
                            String call(Ghost ghost) { return ghost.name(); }
                        }
                        """);
        Path astOutput = temporaryDirectory.resolve("ast.ghostmap.json");
        Path staticOutput = temporaryDirectory.resolve("static.ghostmap.json");

        Execution ast = execute("index", "--src", sample.sourceRoot().toString(), "--out", astOutput.toString());
        Execution combined = execute("index", "--src", sample.sourceRoot().toString(), "--classes",
                sample.classes().toString(), "--out", staticOutput.toString());

        assertEquals(0, ast.exitCode(), ast.error());
        assertEquals(0, combined.exitCode(), combined.error());
        Snapshot astSnapshot = SnapshotJson.fromJson(Files.readString(astOutput));
        Snapshot staticSnapshot = SnapshotJson.fromJson(Files.readString(staticOutput));
        assertEquals(astSnapshot, SnapshotJson.fromJson(SnapshotJson.toJson(astSnapshot)));
        assertEquals(staticSnapshot, SnapshotJson.fromJson(SnapshotJson.toJson(staticSnapshot)));
        assertEquals(Set.of(LayerId.AST), astSnapshot.layersRun());
        assertEquals(Set.of(LayerId.AST, LayerId.BYTECODE), staticSnapshot.layersRun());
        assertNotEquals(astSnapshot.codeVersion().classHash(), staticSnapshot.codeVersion().classHash());
        assertEquals("UNVERSIONED", astSnapshot.codeVersion().commit());
        assertTrue(astSnapshot.codeVersion().dirty());
        assertTrue(astSnapshot.codeVersion().repo().startsWith("file:"));
        assertEquals("jp/v1", staticSnapshot.tool().joinPolicy());
        assertTrue(staticSnapshot.report().staticRecallOnExercised().astOnly().isEmpty());
        assertTrue(staticSnapshot.report().staticRecallOnExercised().ghostMap().isEmpty());

        String generatedAccessor = "m:sample.Ghost#name()Ljava/lang/String;";
        assertFalse(astSnapshot.nodes().stream().anyMatch(node -> node.id().value().equals(generatedAccessor)));
        assertTrue(staticSnapshot.nodes().stream().anyMatch(node -> node.id().value().equals(generatedAccessor)
                && node.attrs().get("generated") instanceof BooleanValue generated && generated.value()));
        assertTrue(staticSnapshot.nodes().size() > astSnapshot.nodes().size());
        assertTrue(staticSnapshot.edges().size() > astSnapshot.edges().size());
    }

    @Test
    void runtimeFlagBuildsHonestRecallPhantomsAndUnresolvedCounts() throws Exception {
        CliTestCompiler.CompiledSample sample = CliTestCompiler.compile(temporaryDirectory.resolve("runtime-sample"),
                "sample.Caller", """
                        package sample;
                        import java.lang.reflect.Method;
                        class Caller {
                            void call() { Target.hit(); }
                            Object reflect(Method method, Object target) throws Exception {
                                return method.invoke(target);
                            }
                        }
                        class Target { static void hit() { } }
                        """);
        Path runtime = temporaryDirectory.resolve("runtime.jsonl");
        Files.writeString(runtime, """
                {"schema":"ghostmap.runtime/v1","runId":"cli-run","startedAt":"2026-11-22T00:00:00Z","endedAt":"2026-11-22T00:00:01Z","reason":"shutdown","filter":{"include":["sample"],"exclude":[]},"edgeLimit":100,"droppedEvents":0,"edges":[{"caller":{"owner":"sample.Caller","name":"call","descriptor":"()V"},"callee":{"owner":"sample.Target","name":"hit","descriptor":"()V"},"hits":2,"dispatch":"UNKNOWN"},{"caller":{"owner":"sample.Caller","name":"call","descriptor":"()V"},"callee":{"owner":"sample.Hidden","name":"missing","descriptor":"()V"},"hits":1,"dispatch":"UNKNOWN"}]}
                """);
        Path output = temporaryDirectory.resolve("runtime.ghostmap.json");

        Execution execution = execute("index", "--src", sample.sourceRoot().toString(), "--classes",
                sample.classes().toString(), "--runtime", runtime.toString(), "--out", output.toString());

        assertEquals(0, execution.exitCode(), execution.error());
        Snapshot snapshot = SnapshotJson.fromJson(Files.readString(output));
        assertEquals(Set.of(LayerId.AST, LayerId.BYTECODE, LayerId.RUNTIME), snapshot.layersRun());
        assertEquals(1, snapshot.runtimeRuns().size());
        assertEquals("cli-run", snapshot.runtimeRuns().getFirst().runId());
        assertEquals(1, snapshot.report().phantomEdges());
        assertEquals(1L, snapshot.report().unresolvedSites().get(dev.ghostmap.model.UnresolvedSiteKind.REFLECTION));
        assertEquals(0.5, snapshot.report().staticRecallOnExercised().astOnly().orElseThrow());
        assertEquals(0.5, snapshot.report().staticRecallOnExercised().ghostMap().orElseThrow());
        assertEquals(3, snapshot.report().exercised().methodsExercised());
        assertTrue(snapshot.report().exercised().methodsInScope() >= 3);
        assertTrue(snapshot.report().caveat().contains("only over code exercised"));
        assertTrue(snapshot.report().caveat().contains("must not be extrapolated"));
        assertTrue(snapshot.report().edgesByProvenance().keySet().stream()
                .anyMatch(key -> key.contains("RUNTIME")));
    }

    @Test
    void missingRequiredOptionsAndRootsFailWithoutSnapshots() throws Exception {
        Path output = temporaryDirectory.resolve("missing.ghostmap.json");
        Execution missingOutput = execute("index", "--src", temporaryDirectory.toString());
        Execution missingSourceOption = execute("index", "--out", output.toString());
        Execution missingSource = execute("index", "--src", temporaryDirectory.resolve("absent").toString(),
                "--out", output.toString());
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Execution missingClasses = execute("index", "--src", source.toString(), "--classes",
                temporaryDirectory.resolve("missing-classes").toString(), "--out", output.toString());

        assertTrue(missingOutput.exitCode() != 0);
        assertTrue(missingSourceOption.exitCode() != 0);
        assertTrue(missingSource.exitCode() != 0);
        assertTrue(missingClasses.exitCode() != 0);
        assertTrue(missingSource.error().contains("source root"));
        assertTrue(missingClasses.error().contains("class root"));
        assertFalse(Files.exists(output));
    }

    @Test
    void invalidOutputAndMutuallyExclusiveModesFailWithoutSnapshots() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path classes = Files.createDirectory(temporaryDirectory.resolve("classes"));
        Path outputDirectory = Files.createDirectory(temporaryDirectory.resolve("output-directory"));
        Execution outputIsDirectory = execute("index", "--src", source.toString(),
                "--out", outputDirectory.toString());
        Path blockingFile = Files.writeString(temporaryDirectory.resolve("blocking-file"), "not a directory");
        Path blockedOutput = blockingFile.resolve("snapshot.json");
        Execution cannotWrite = execute("index", "--src", source.toString(), "--out", blockedOutput.toString());
        Path contradictoryOutput = temporaryDirectory.resolve("contradictory.json");
        Execution contradictory = execute("index", "--src", source.toString(), "--classes", classes.toString(),
                "--ast-only", "--out", contradictoryOutput.toString());

        assertTrue(outputIsDirectory.exitCode() != 0);
        assertTrue(cannotWrite.exitCode() != 0);
        assertTrue(contradictory.exitCode() != 0);
        assertTrue(contradictory.error().contains("--ast-only"));
        assertFalse(Files.exists(blockedOutput));
        assertFalse(Files.exists(contradictoryOutput));
    }

    private static Execution execute(String... args) {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine commandLine = new CommandLine(new GhostMapCli());
        commandLine.setOut(new PrintWriter(output, true));
        commandLine.setErr(new PrintWriter(error, true));
        int exitCode = commandLine.execute(args);
        return new Execution(exitCode, output.toString(), error.toString());
    }

    private record Execution(int exitCode, String output, String error) {
    }
}
