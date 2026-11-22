package dev.ghostmap.core.layer;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Filesystem roots supplied to one or more analysis layers. */
public record LayerInput(Optional<Path> sourceRoot, List<Path> classRoots) {
    public LayerInput(Path sourceRoot) {
        this(Optional.of(normalize(sourceRoot)), List.of());
    }

    public LayerInput {
        if (sourceRoot == null || classRoots == null) {
            throw new NullPointerException("layer input fields must not be null");
        }
        sourceRoot = sourceRoot.map(LayerInput::normalize);
        classRoots = classRoots.stream().map(LayerInput::normalize).toList();
    }

    public static LayerInput forClassRoots(Path... classRoots) {
        if (classRoots == null) {
            throw new NullPointerException("classRoots");
        }
        return new LayerInput(Optional.empty(), Arrays.asList(classRoots));
    }

    public static LayerInput forClassRoots(List<Path> classRoots) {
        return new LayerInput(Optional.empty(), classRoots);
    }

    public Path requireSourceRoot() {
        return sourceRoot.orElseThrow(() -> new IllegalArgumentException("source root is not configured"));
    }

    private static Path normalize(Path path) {
        if (path == null) {
            throw new NullPointerException("input path");
        }
        return path.toAbsolutePath().normalize();
    }
}
