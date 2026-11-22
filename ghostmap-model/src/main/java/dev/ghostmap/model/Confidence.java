package dev.ghostmap.model;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;

/** Noisy-OR confidence algebra used by the join policy. */
public final class Confidence {
    private Confidence() {
    }

    public static double noisyOr(Collection<Double> weights) {
        Objects.requireNonNull(weights, "weights");
        BigDecimal complementProduct = BigDecimal.ONE;
        for (Double boxedWeight : weights) {
            Objects.requireNonNull(boxedWeight, "weight");
            double weight = requireWeight(boxedWeight);
            complementProduct = complementProduct.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(weight)));
        }
        double result = BigDecimal.ONE.subtract(complementProduct).doubleValue();
        return result >= 1.0 ? Math.nextDown(1.0) : result;
    }

    public static double noisyOr(double... weights) {
        Objects.requireNonNull(weights, "weights");
        BigDecimal complementProduct = BigDecimal.ONE;
        for (double weight : weights) {
            requireWeight(weight);
            complementProduct = complementProduct.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(weight)));
        }
        double result = BigDecimal.ONE.subtract(complementProduct).doubleValue();
        return result >= 1.0 ? Math.nextDown(1.0) : result;
    }

    public static double applyPenalty(double weight, double multiplier) {
        requireWeight(weight);
        if (!Double.isFinite(multiplier) || multiplier < 0.0 || multiplier > 1.0) {
            throw new IllegalArgumentException("penalty multiplier must be in [0, 1]");
        }
        return weight * multiplier;
    }

    private static double requireWeight(double weight) {
        if (!Double.isFinite(weight) || weight < 0.0 || weight >= 1.0) {
            throw new IllegalArgumentException("evidence weight must be in [0, 1)");
        }
        return weight;
    }
}
