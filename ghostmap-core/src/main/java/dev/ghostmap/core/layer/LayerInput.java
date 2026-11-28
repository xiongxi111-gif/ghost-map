package dev.ghostmap.core.layer;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Filesystem roots supplied to one or more analysis layers. */
public record LayerInput(Optional<Path> sourceRoot, List<Path> classRoots, List<Path> resourceRoots,
                         List<Path> runtimeFiles) {
    public LayerInput(Path sourceRoot) {
        this(Optional.of(normalize(sourceRoot)), List.of(), List.of(), List.of());
    }

    public LayerInput(Optional<Path> sourceRoot, List<Path> classRoots) {
        this(sourceRoot, classRoots, List.of(), List.of());
    }

    /** Backward-compatible source/class/resource constructor. */
    public LayerInput(Optional<Path> sourceRoot, List<Path> classRoots, List<Path> resourceRoots) {
        this(sourceRoot, classRoots, resourceRoots, List.of());
    }

    public LayerInput {
        if (sourceRoot == null || classRoots == null || resourceRoots == null || runtimeFiles == null) {
            throw new NullPointerException("layer input fields must not be null");
        }
        sourceRoot = sourceRoot.map(LayerInput::normalize);
        classRoots = classRoots.stream().map(LayerInput::normalize).toList();
        resourceRoots = resourceRoots.stream().map(LayerInput::normalize).toList();
        runtimeFiles = runtimeFiles.stream().map(LayerInput::normalize).toList();
    }

    public static LayerInput forClassRoots(Path... classRoots) {
        if (classRoots == null) {
            throw new NullPointerException("classRoots");
        }
        return new LayerInput(Optional.empty(), Arrays.asList(classRoots), List.of(), List.of());
    }

    public static LayerInput forClassRoots(List<Path> classRoots) {
        return new LayerInput(Optional.empty(), classRoots, List.of(), List.of());
    }

    public static LayerInput forResourceRoots(Path... resourceRoots) {
        if (resourceRoots == null) {
            throw new NullPointerException("resourceRoots");
        }
        return new LayerInput(Optional.empty(), List.of(), Arrays.asList(resourceRoots), List.of());
    }

    public static LayerInput forResourceRoots(List<Path> resourceRoots) {
        return new LayerInput(Optional.empty(), List.of(), resourceRoots, List.of());
    }

    public static LayerInput forRuntimeFiles(Path... runtimeFiles) {
        if (runtimeFiles == null) {
            throw new NullPointerException("runtimeFiles");
        }
        return new LayerInput(Optional.empty(), List.of(), List.of(), Arrays.asList(runtimeFiles));
    }

    public static LayerInput forRuntimeFiles(List<Path> runtimeFiles) {
        return new LayerInput(Optional.empty(), List.of(), List.of(), runtimeFiles);
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
