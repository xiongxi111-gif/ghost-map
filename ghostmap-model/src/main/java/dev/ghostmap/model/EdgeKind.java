package dev.ghostmap.model;

/** Relationships represented by graph edges. */
public enum EdgeKind {
    CALLS,
    INJECTS,
    BINDS,
    EXTENDS,
    IMPLEMENTS,
    OVERRIDES,
    READS_CONFIG,
    ACCESSES,
    UNRESOLVED,
    PHANTOM
}
