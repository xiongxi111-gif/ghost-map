package dev.ghostmap.core.join;

/** One deterministic rule in the ordered join policy. */
public interface JoinRule {
    String id();

    void apply(JoinContext context);
}
