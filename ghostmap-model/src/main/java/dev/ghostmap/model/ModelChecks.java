package dev.ghostmap.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ModelChecks {
    private ModelChecks() {
    }

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static <T> List<T> immutableList(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        values.forEach(value -> Objects.requireNonNull(value, name + " element"));
        return List.copyOf(values);
    }

    static <T> Set<T> immutableSet(Set<T> values, String name) {
        Objects.requireNonNull(values, name);
        values.forEach(value -> Objects.requireNonNull(value, name + " element"));
        return Set.copyOf(values);
    }

    static <K, V> Map<K, V> immutableMap(Map<K, V> values, String name) {
        Objects.requireNonNull(values, name);
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, name + " key");
            Objects.requireNonNull(value, name + " value");
        });
        return Map.copyOf(values);
    }

    static int positive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    static double confidence(double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0, 1]");
        }
        return value;
    }
}
