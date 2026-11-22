package dev.ghostmap.model;

/** Counts used to state the measured runtime coverage. */
public record ExercisedCoverage(long methodsExercised, long methodsInScope) {
    public ExercisedCoverage {
        ModelChecks.nonNegative(methodsExercised, "methodsExercised");
        ModelChecks.nonNegative(methodsInScope, "methodsInScope");
        if (methodsExercised > methodsInScope) {
            throw new IllegalArgumentException("methodsExercised must not exceed methodsInScope");
        }
    }
}
