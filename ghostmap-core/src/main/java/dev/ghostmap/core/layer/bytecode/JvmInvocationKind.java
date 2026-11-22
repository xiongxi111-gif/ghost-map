package dev.ghostmap.core.layer.bytecode;

/** JVM invocation opcodes represented as call facts. */
public enum JvmInvocationKind {
    INVOKEVIRTUAL,
    INVOKEINTERFACE,
    INVOKESTATIC,
    INVOKESPECIAL,
    INVOKEDYNAMIC
}
