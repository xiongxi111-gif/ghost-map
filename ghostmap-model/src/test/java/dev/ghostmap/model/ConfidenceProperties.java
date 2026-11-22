package dev.ghostmap.model;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfidenceProperties {
    @Property(tries = 1_000)
    @Label("noisy-OR is order-independent")
    void orderIndependent(@ForAll("weights") List<Double> weights) {
        List<Double> reversed = new ArrayList<>(weights);
        Collections.reverse(reversed);
        assertEquals(Confidence.noisyOr(weights), Confidence.noisyOr(reversed));
    }

    @Property(tries = 1_000)
    @Label("noisy-OR remains within [0, 1)")
    void bounded(@ForAll("weights") List<Double> weights) {
        double confidence = Confidence.noisyOr(weights);
        assertTrue(confidence >= 0.0);
        assertTrue(confidence < 1.0);
    }

    @Property(tries = 1_000)
    @Label("adding evidence never lowers confidence")
    void monotone(@ForAll("weights") List<Double> weights, @ForAll("weight") double addedWeight) {
        double before = Confidence.noisyOr(weights);
        List<Double> extended = new ArrayList<>(weights);
        extended.add(addedWeight);
        assertTrue(Confidence.noisyOr(extended) >= before);
    }

    @Provide
    Arbitrary<List<Double>> weights() {
        return weight().list().ofMaxSize(40);
    }

    @Provide
    Arbitrary<Double> weight() {
        return Arbitraries.integers().between(0, 999_999).map(value -> value / 1_000_000.0);
    }
}
