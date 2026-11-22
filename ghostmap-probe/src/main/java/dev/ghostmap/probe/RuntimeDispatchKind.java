package dev.ghostmap.probe;

/** Dispatch mechanism detectable from an entered method and its live stack. */
enum RuntimeDispatchKind {
    UNKNOWN,
    REFLECTION,
    PROXY,
    LAMBDA,
    DYNAMIC,
    REGISTRY
}
