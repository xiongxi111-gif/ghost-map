package dev.ghostmap.core.layer.wiring;

/** XML construct that supplies a bean reference. */
public enum InjectionKind {
    PROPERTY,
    CONSTRUCTOR_ARGUMENT,
    REF,
    INNER_BEAN
}
