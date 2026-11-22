package dev.ghostmap.cli;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

final class CliTestCompiler {
    private CliTestCompiler() {
    }

    static CompiledSample compile(Path workspace, String className, String source) throws IOException {
        Path sourceRoot = Files.createDirectories(workspace.resolve("src"));
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        Path sourceFile = sourceRoot.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("tests require a JDK compiler");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(sourceFile);
            List<String> options = List.of("--release", "17", "-g", "-proc:none", "-d", classes.toString());
            if (!compiler.getTask(null, files, diagnostics, options, null, units).call()) {
                throw new IllegalStateException("test compilation failed: " + diagnostics.getDiagnostics());
            }
        }
        return new CompiledSample(sourceRoot, classes);
    }

    record CompiledSample(Path sourceRoot, Path classes) {
    }
}
