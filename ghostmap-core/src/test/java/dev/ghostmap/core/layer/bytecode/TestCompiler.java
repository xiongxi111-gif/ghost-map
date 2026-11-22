package dev.ghostmap.core.layer.bytecode;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TestCompiler {
    private TestCompiler() {
    }

    static Path compile(Path workspace, Map<String, String> sources, boolean debug) throws IOException {
        Path sourceRoot = Files.createDirectories(workspace.resolve("src"));
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        List<Path> sourceFiles = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = sourceRoot.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            sourceFiles.add(file);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("tests require a JDK compiler");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sourceFiles);
            String classPath = System.getProperty("java.class.path");
            List<String> options = List.of(
                    "--release", "17",
                    debug ? "-g" : "-g:none",
                    "-parameters",
                    "-classpath", classPath,
                    "-processorpath", classPath,
                    "-processor", "lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
                    "-d", classes.toString());
            boolean success = compiler.getTask(null, files, diagnostics, options, null, units).call();
            if (!success) {
                throw new IllegalStateException("test compilation failed: " + diagnostics.getDiagnostics());
            }
        }
        return classes;
    }
}
