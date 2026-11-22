package dev.ghostmap.core.layer;

import java.nio.file.Path;

/** Filesystem input for a source analysis run. */
public record LayerInput(Path sourceRoot) {
    public LayerInput {
        if (sourceRoot == null) {
            throw new NullPointerException("sourceRoot");
        }
        sourceRoot = sourceRoot.toAbsolutePath().normalize();
    }
}
