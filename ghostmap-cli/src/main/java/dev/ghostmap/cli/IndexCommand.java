package dev.ghostmap.cli;

import dev.ghostmap.core.join.JoinPolicy;
import dev.ghostmap.core.join.JoinedGraph;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import dev.ghostmap.core.layer.bytecode.BytecodeLayer;
import dev.ghostmap.core.layer.runtime.RuntimeLayer;
import dev.ghostmap.core.layer.source.SourceLayer;
import dev.ghostmap.model.CodeVersion;
import dev.ghostmap.model.LayerId;
import dev.ghostmap.model.RuntimeRun;
import dev.ghostmap.model.Snapshot;
import dev.ghostmap.model.SnapshotJson;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "index", mixinStandardHelpOptions = true,
        description = "Index Java source and optional class directories or JARs into a snapshot.")
final class IndexCommand implements Callable<Integer> {
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Option(names = "--src", required = true, paramLabel = "<sourceRoot>",
            description = "Java source root to parse.")
    private Path sourceRoot;

    @Option(names = "--classes", split = ",", paramLabel = "<classRootOrJar>[,...]",
            description = "Class directories or JARs. Omit for AST-only indexing.")
    private List<Path> classRoots = new ArrayList<>();

    @Option(names = "--runtime", split = ",", paramLabel = "<runtime.jsonl>[,...]",
            description = "Probe runtime JSONL files. Omit to keep indexing static-only.")
    private List<Path> runtimeFiles = new ArrayList<>();

    @Option(names = "--ast-only", description = "Reject class roots and run only the source layer.")
    private boolean astOnly;

    @Option(names = "--out", required = true, paramLabel = "<snapshot.ghostmap.json>",
            description = "Snapshot output file.")
    private Path output;

    @Override
    public Integer call() {
        PrintWriter error = spec.commandLine().getErr();
        try {
            Path source = validateSourceRoot(sourceRoot);
            List<Path> classes = validateClassRoots(classRoots);
            List<Path> runtime = validateRuntimeFiles(runtimeFiles);
            if (astOnly && !classes.isEmpty()) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "--ast-only cannot be combined with --classes");
            }
            Path destination = validateOutput(output);
            Instant analysedAt = Instant.now();

            List<LayerFacts> facts = new ArrayList<>();
            Set<LayerId> layers = EnumSet.noneOf(LayerId.class);
            LayerFacts sourceFacts = new SourceLayer().analyse(new LayerInput(source));
            facts.add(sourceFacts);
            layers.add(LayerId.AST);
            if (!classes.isEmpty()) {
                facts.add(new BytecodeLayer().analyse(LayerInput.forClassRoots(classes)));
                layers.add(LayerId.BYTECODE);
            }
            List<RuntimeRun> runtimeRuns = List.of();
            if (!runtime.isEmpty()) {
                LayerFacts runtimeFacts = new RuntimeLayer().analyse(LayerInput.forRuntimeFiles(runtime));
                facts.add(runtimeFacts);
                layers.add(LayerId.RUNTIME);
                runtimeRuns = RuntimeLayer.runtimeRuns(runtimeFacts);
            }
            JoinedGraph graph = new JoinPolicy().join(facts);
            graph.diagnostics().forEach(diagnostic -> error.printf("warning: %s:%d [%s] %s%n",
                    diagnostic.file(), diagnostic.line(), diagnostic.code(), diagnostic.message()));

            CodeVersion version = TargetMetadata.resolve(source, classes, analysedAt);
            Snapshot snapshot = new SnapshotAssembler().assemble(graph, version, layers, runtimeRuns);
            writeAtomically(destination, SnapshotJson.toJson(snapshot));
            spec.commandLine().getOut().printf("wrote %s (%d nodes, %d edges, layers=%s)%n",
                    destination, snapshot.nodes().size(), snapshot.edges().size(), snapshot.layersRun());
            return CommandLine.ExitCode.OK;
        } catch (CommandLine.ParameterException exception) {
            error.println("ghostmap index: " + exception.getMessage());
            return CommandLine.ExitCode.USAGE;
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            error.println("ghostmap index: " + message);
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    private Path validateSourceRoot(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "source root does not exist or is not a directory: " + root);
        }
        if (!Files.isReadable(normalized)) {
            throw new CommandLine.ParameterException(spec.commandLine(), "source root is not readable: " + root);
        }
        return normalized;
    }

    private List<Path> validateClassRoots(List<Path> roots) {
        List<Path> validated = new ArrayList<>();
        for (Path root : roots) {
            Path normalized = root.toAbsolutePath().normalize();
            boolean supported = Files.isDirectory(normalized)
                    || Files.isRegularFile(normalized)
                    && normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
            if (!supported) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "class root does not exist or is not a directory/JAR: " + root);
            }
            if (!Files.isReadable(normalized)) {
                throw new CommandLine.ParameterException(spec.commandLine(), "class root is not readable: " + root);
            }
            validated.add(normalized);
        }
        return List.copyOf(validated);
    }

    private List<Path> validateRuntimeFiles(List<Path> files) {
        List<Path> validated = new ArrayList<>();
        for (Path file : files) {
            Path normalized = file.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "runtime input does not exist or is not a file: " + file);
            }
            if (!Files.isReadable(normalized)) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "runtime input is not readable: " + file);
            }
            validated.add(normalized);
        }
        return List.copyOf(validated);
    }

    private Path validateOutput(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            throw new CommandLine.ParameterException(spec.commandLine(), "output is a directory: " + path);
        }
        return normalized;
    }

    private static void writeAtomically(Path destination, String json) throws IOException {
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, destination.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, json + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
