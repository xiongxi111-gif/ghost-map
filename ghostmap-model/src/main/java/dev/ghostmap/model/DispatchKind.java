package dev.ghostmap.model;

/** Invocation or dynamic-dispatch mechanism at an edge site. */
public enum DispatchKind {
    STATIC,
    VIRTUAL,
    INTERFACE,
    DYNAMIC,
    REFLECTION,
    PROXY,
    LAMBDA,
    REGISTRY
}
