package dev.ghostmap.model;

import java.util.OptionalDouble;

/** Recall measured only against runtime-exercised calls. */
public record StaticRecall(OptionalDouble astOnly, OptionalDouble ghostMap) {
    public StaticRecall {
        if (astOnly == null || ghostMap == null) {
            throw new NullPointerException("recall values must not be null");
        }
        astOnly.ifPresent(value -> requireRate(value, "astOnly"));
        ghostMap.ifPresent(value -> requireRate(value, "ghostMap"));
    }

    private static void requireRate(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [0, 1]");
        }
    }
}
