package dev.ghostmap.core.layer.bytecode;

/** Dynamic-dispatch patterns visible in JVM instructions. */
public enum BytecodeDynamicKind {
    NONE,
    GET_BEAN,
    REFLECTION,
    CLASS_LOADING,
    METHOD_HANDLE,
    REGISTRY,
    INVOKEDYNAMIC
}
