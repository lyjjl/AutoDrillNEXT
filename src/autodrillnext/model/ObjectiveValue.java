package autodrillnext.model;

import autodrillnext.capability.spec.CostVector;

import java.util.Objects;

/** Canonical value tuple; callers choose an explicit {@link PlanObjectiveOrder}. */
public record ObjectiveValue(
    float qout,
    int coveredOreCells,
    CostVector materials,
    int space,
    int complexity,
    String canonicalId
) {
    public ObjectiveValue {
        if (!Float.isFinite(qout) || qout < 0f) throw new IllegalArgumentException("qout must be finite and non-negative");
        if (coveredOreCells < 0 || space < 0 || complexity < 0) {
            throw new IllegalArgumentException("objective counts must be non-negative");
        }
        Objects.requireNonNull(materials, "materials");
        if (canonicalId == null || canonicalId.isBlank()) throw new IllegalArgumentException("canonical id must not be blank");
    }

    /** Transitive throughput buckets at the solver's 0.0001 items/second precision. */
    public static long outputRank(float output) {
        return Math.round((double) output * 10_000d);
    }

    public static ObjectiveValue zero(String canonicalId) {
        return new ObjectiveValue(0f, 0, CostVector.empty(), 0, 0, canonicalId);
    }

}
