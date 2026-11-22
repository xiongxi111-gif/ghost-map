package dev.ghostmap.model;

/** Join-policy state of an edge. */
public enum EdgeStatus {
    CONFIRMED,
    PREDICTED,
    CANDIDATE,
    UNRESOLVED,
    PHANTOM
}
