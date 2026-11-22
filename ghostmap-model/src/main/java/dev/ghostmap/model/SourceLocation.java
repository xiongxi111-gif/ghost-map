package dev.ghostmap.model;

/** Inclusive source range that can be opened by a snapshot consumer. */
public record SourceLocation(String file, int line, int endLine) {
    public SourceLocation {
        ModelChecks.nonBlank(file, "file");
        ModelChecks.positive(line, "line");
        if (endLine < line) {
            throw new IllegalArgumentException("endLine must be greater than or equal to line");
        }
    }
}
