package dev.ghostmap.core.layer.bytecode;

import dev.ghostmap.core.layer.DiagnosticCode;
import dev.ghostmap.core.layer.LayerFacts;
import dev.ghostmap.core.layer.LayerInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BytecodeLayerFailureTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void invalidClassReportsDiagnosticAndDoesNotDropValidSibling() throws Exception {
        Path classes = TestCompiler.compile(temporaryDirectory.resolve("invalid"), Map.of(
                "sample.SafeToRead", """
                        package sample;
                        class SafeToRead {
                            static {
                                if (System.nanoTime() != Long.MIN_VALUE) {
                                    throw new AssertionError("must never initialize during analysis");
                                }
                            }
                            int value() { return 7; }
                        }
                        """), true);
        Files.write(classes.resolve("Broken.class"), new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe});
        Files.write(classes.resolve("module-info.class"), new byte[]{1, 2, 3});
        Files.write(classes.resolve("package-info.class"), new byte[]{1, 2, 3});

        LayerFacts facts = new BytecodeLayer().analyse(LayerInput.forClassRoots(classes));

        assertTrue(facts.nodes().stream().anyMatch(node -> node.id().value().equals("t:sample.SafeToRead")));
        assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.INVALID_CLASS_FILE
                        && diagnostic.file().endsWith("Broken.class")));
        assertFalse(facts.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.file().endsWith("module-info.class") || diagnostic.file().endsWith("package-info.class")));
    }

    @Test
    void readsJarEntriesAndContinuesPastAnInvalidEntry() throws Exception {
        Path classes = TestCompiler.compile(temporaryDirectory.resolve("jar-input"), Map.of(
                "sample.JarGhost", """
                        package sample;
                        class JarGhost { String call() { return "boo".strip(); } }
                        """), true);
        Path jar = temporaryDirectory.resolve("ghosts.jar");
        writeJar(classes, jar);

        LayerFacts facts = new BytecodeLayer().analyse(LayerInput.forClassRoots(jar));

        assertTrue(facts.nodes().stream().anyMatch(node -> node.id().value().equals("t:sample.JarGhost")));
        assertTrue(facts.edges().stream().filter(BytecodeCallFact.class::isInstance)
                .map(BytecodeCallFact.class::cast).anyMatch(call -> call.methodName().equals("strip")));
        assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.INVALID_CLASS_FILE
                        && diagnostic.file().contains("Broken.class")));
    }

    @Test
    void missingAndUnsupportedRootsReturnDiagnostics() throws IOException {
        Path missing = temporaryDirectory.resolve("missing");
        Path unsupported = Files.writeString(temporaryDirectory.resolve("classes.bin"), "not a class root");

        LayerFacts facts = new BytecodeLayer().analyse(LayerInput.forClassRoots(missing, unsupported));

        assertEquals(2, facts.diagnostics().size());
        assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.CLASS_ROOT_NOT_FOUND));
        assertTrue(facts.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code() == DiagnosticCode.CLASS_ROOT_UNSUPPORTED));
        assertTrue(facts.nodes().isEmpty());
    }

    @Test
    void noClassRootsProducesAnEmptyBytecodeFactSet() {
        LayerFacts facts = new BytecodeLayer().analyse(LayerInput.forClassRoots());

        assertTrue(facts.nodes().isEmpty());
        assertTrue(facts.edges().isEmpty());
        assertTrue(facts.diagnostics().isEmpty());
    }

    private static void writeJar(Path classes, Path jar) throws IOException {
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream entries = new JarOutputStream(output);
             Stream<Path> paths = Files.walk(classes)) {
            for (Path classFile : paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList()) {
                String name = classes.relativize(classFile).toString().replace('\\', '/');
                entries.putNextEntry(new JarEntry(name));
                entries.write(Files.readAllBytes(classFile));
                entries.closeEntry();
            }
            entries.putNextEntry(new JarEntry("Broken.class"));
            entries.write(new byte[]{0, 1, 2, 3});
            entries.closeEntry();
            entries.putNextEntry(new JarEntry("META-INF/versions/17/module-info.class"));
            entries.write(new byte[]{0, 1, 2, 3});
            entries.closeEntry();
        }
    }
}
