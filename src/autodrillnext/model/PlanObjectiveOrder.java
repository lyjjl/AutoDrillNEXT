package autodrillnext.model;

import java.util.Comparator;
import java.util.Objects;

public final class PlanObjectiveOrder {
    private final PlannerRequest.Profile profile;

    private PlanObjectiveOrder(PlannerRequest.Profile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public static PlanObjectiveOrder forProfile(PlannerRequest.Profile profile) {
        return new PlanObjectiveOrder(profile);
    }

    public int compare(ObjectiveValue left, ObjectiveValue right) {
        int ranked = compareRankedFields(left, right);
        return ranked != 0 ? ranked : right.canonicalId().compareTo(left.canonicalId());
    }

    public boolean canBeat(ObjectiveValue optimistic, ObjectiveValue incumbent) {
        return compareRankedFields(optimistic, incumbent) >= 0;
    }
    public boolean equivalentRank(ObjectiveValue left, ObjectiveValue right) {
        return compareRankedFields(left, right) == 0;
    }


    private int compareRankedFields(ObjectiveValue left, ObjectiveValue right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (profile == PlannerRequest.Profile.LOW_COMPLEXITY) {
            int byComplexity = Integer.compare(right.complexity(), left.complexity());
            if (byComplexity != 0) return byComplexity;
        }
        if (profile == PlannerRequest.Profile.LOW_COST
            || profile == PlannerRequest.Profile.LOW_COMPLEXITY) {
            int byCost = compareCost(left, right);
            if (byCost != 0) return byCost;
        }
        return compareThroughputFields(left, right);
    }

    public Comparator<ObjectiveValue> bestFirst() {
        return (left, right) -> compare(right, left);
    }

    private int compareThroughputFields(ObjectiveValue left, ObjectiveValue right) {
        int byOutput = Long.compare(
            ObjectiveValue.outputRank(left.qout()),
            ObjectiveValue.outputRank(right.qout())
        );
        if (byOutput != 0) return byOutput;
        int byCoverage = Integer.compare(left.coveredOreCells(), right.coveredOreCells());
        if (byCoverage != 0) return byCoverage;
        return compareCost(left, right);
    }

    private int compareCost(ObjectiveValue left, ObjectiveValue right) {
        int byMaterials = Double.compare(
            right.materials().economicValue(),
            left.materials().economicValue()
        );
        if (byMaterials != 0) return byMaterials;
        int bySpace = Integer.compare(right.space(), left.space());
        if (bySpace != 0) return bySpace;
        return Integer.compare(right.complexity(), left.complexity());
    }
}
