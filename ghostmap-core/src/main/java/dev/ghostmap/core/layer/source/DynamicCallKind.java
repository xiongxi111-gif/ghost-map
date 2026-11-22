package dev.ghostmap.core.layer.source;

/** Known APIs whose target can depend on runtime values. */
public enum DynamicCallKind {
    NONE,
    GET_BEAN,
    REFLECTION,
    CLASS_LOADING,
    METHOD_HANDLE,
    REGISTRY
}
