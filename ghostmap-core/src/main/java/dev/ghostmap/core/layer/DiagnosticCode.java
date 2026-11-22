package dev.ghostmap.core.layer;

/** Machine-readable source analysis failure categories. */
public enum DiagnosticCode {
    SOURCE_ROOT_NOT_FOUND,
    SOURCE_ROOT_NOT_DIRECTORY,
    SOURCE_ROOT_READ_ERROR,
    SOURCE_FILE_READ_ERROR,
    PARSE_ERROR
}
