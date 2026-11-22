package dev.ghostmap.cli;

import dev.ghostmap.model.CodeVersion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

final class TargetMetadata {
    private TargetMetadata() {
    }

    static CodeVersion resolve(Path sourceRoot, List<Path> classRoots, Instant analysedAt) throws IOException {
        String classHash = classHash(classRoots);
        Optional<Path> gitRoot = git(sourceRoot, "rev-parse", "--show-toplevel").map(Path::of);
        if (gitRoot.isEmpty()) {
            return new CodeVersion(sourceRoot.toUri().toString(), "UNVERSIONED", true, classHash, analysedAt);
        }
        Path root = gitRoot.orElseThrow().toAbsolutePath().normalize();
        String commit = git(root, "rev-parse", "HEAD").orElse("UNKNOWN");
        String repo = git(root, "config", "--get", "remote.origin.url").orElse(root.toUri().toString());
        List<String> statusArguments = new ArrayList<>();
        statusArguments.add("status");
        statusArguments.add("--porcelain");
        statusArguments.add("--untracked-files=all");
        statusArguments.add("--");
        addPathspec(statusArguments, root, sourceRoot);
        classRoots.forEach(path -> addPathspec(statusArguments, root, path));
        boolean dirty = git(root, statusArguments.toArray(String[]::new)).filter(output -> !output.isBlank())
                .isPresent();
        return new CodeVersion(repo, commit, dirty, classHash, analysedAt);
    }

    static String classHash(List<Path> classRoots) throws IOException {
        MessageDigest digest = sha256();
        for (int rootIndex = 0; rootIndex < classRoots.size(); rootIndex++) {
            Path root = classRoots.get(rootIndex);
            if (Files.isDirectory(root)) {
                hashDirectory(digest, root, rootIndex);
            } else {
                hashJar(digest, root, rootIndex);
            }
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static void hashDirectory(MessageDigest digest, Path root, int rootIndex) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .filter(path -> !skipClass(path.getFileName().toString()))
                    .sorted().toList()) {
                update(digest, rootIndex + ":" + root.relativize(file).toString().replace('\\', '/'));
                digest.update(Files.readAllBytes(file));
            }
        }
    }

    private static void hashJar(MessageDigest digest, Path jar, int rootIndex) throws IOException {
        try (JarFile entries = new JarFile(jar.toFile())) {
            List<JarEntry> classes = entries.stream().filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().endsWith(".class"))
                    .filter(entry -> !skipClass(entry.getName()))
                    .filter(entry -> !entry.getName().startsWith("META-INF/versions/"))
                    .sorted(java.util.Comparator.comparing(JarEntry::getName)).toList();
            for (JarEntry entry : classes) {
                update(digest, rootIndex + ":" + entry.getName());
                try (InputStream input = entries.getInputStream(entry)) {
                    digest.update(input.readAllBytes());
                }
            }
        }
    }

    private static boolean skipClass(String name) {
        return name.endsWith("module-info.class") || name.endsWith("package-info.class");
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
    }

    private static Optional<String> git(Path directory, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(arguments));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return process.exitValue() == 0 ? Optional.of(output) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static void addPathspec(List<String> arguments, Path gitRoot, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.startsWith(gitRoot)) {
            arguments.add(gitRoot.relativize(absolute).toString());
        }
    }
}
