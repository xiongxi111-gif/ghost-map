package dev.ghostmap.core.layer;

/** A locatable problem that did not abort the complete analysis run. */
public record Diagnostic(DiagnosticCode code, DiagnosticSeverity severity, String file, int line,
                         String message) {
    public Diagnostic {
        if (code == null || severity == null || file == null || message == null) {
            throw new NullPointerException("diagnostic fields must not be null");
        }
        if (file.isBlank() || message.isBlank()) {
            throw new IllegalArgumentException("diagnostic file and message must not be blank");
        }
        if (line < 0) {
            throw new IllegalArgumentException("diagnostic line must not be negative");
        }
    }
}
