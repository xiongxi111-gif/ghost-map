package dev.ghostmap.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfidenceTest {
    @Test
    void followsNoisyOrFormulaAndAppliesPenaltyFirst() {
        assertEquals(0.91, Confidence.noisyOr(0.55, 0.80), 1.0e-12);
        assertEquals(0.275, Confidence.applyPenalty(0.55, 0.5), 1.0e-12);
        assertEquals(Confidence.noisyOr(0.275, 0.80),
                Confidence.noisyOr(Confidence.applyPenalty(0.55, 0.5), 0.80));
    }

    @Test
    void rejectsWeightsOutsideHalfOpenUnitInterval() {
        assertThrows(IllegalArgumentException.class, () -> Confidence.noisyOr(-0.01));
        assertThrows(IllegalArgumentException.class, () -> Confidence.noisyOr(1.0));
        assertThrows(IllegalArgumentException.class, () -> Confidence.noisyOr(Double.NaN));
    }
}
