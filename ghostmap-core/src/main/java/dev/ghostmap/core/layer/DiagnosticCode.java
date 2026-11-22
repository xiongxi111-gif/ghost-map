package dev.ghostmap.core.layer;

/** Machine-readable analysis failure categories. */
public enum DiagnosticCode {
    SOURCE_ROOT_NOT_FOUND,
    SOURCE_ROOT_NOT_DIRECTORY,
    SOURCE_ROOT_READ_ERROR,
    SOURCE_FILE_READ_ERROR,
    CLASS_ROOT_NOT_FOUND,
    CLASS_ROOT_UNSUPPORTED,
    CLASS_ROOT_READ_ERROR,
    CLASS_FILE_READ_ERROR,
    INVALID_CLASS_FILE,
    PARSE_ERROR
}
