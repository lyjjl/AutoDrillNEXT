package autodrillnext.model;

import autodrillnext.capability.spec.CostVector;

import java.util.Objects;

public record PlanCost(CostVector materials, int space, int complexity) implements Comparable<PlanCost> {
    public PlanCost {
        Objects.requireNonNull(materials, "materials");
        if (space < 0 || complexity < 0) throw new IllegalArgumentException("plan cost metrics must be non-negative");
    }

    public static PlanCost empty() {
        return new PlanCost(CostVector.empty(), 0, 0);
    }

    public PlanCost plus(PlanCost other) {
        return new PlanCost(materials.plus(other.materials), space + other.space, complexity + other.complexity);
    }

    @Override
    public int compareTo(PlanCost other) {
        int byMaterials = Double.compare(materials.economicValue(), other.materials.economicValue());
        if (byMaterials != 0) return byMaterials;
        int bySpace = Integer.compare(space, other.space);
        return bySpace != 0 ? bySpace : Integer.compare(complexity, other.complexity);
    }
}
