package autodrillnext.model;

import java.util.List;
import java.util.Objects;

/** Evidence describing the result and remaining gap of one finite patch search. */
public record OptimalityCertificate(
    SearchVerdict verdict,
    PatchSearchScope scope,
    int exploredStates,
    int prunedStates,
    int pendingStates,
    ObjectiveValue lowerBound,
    ObjectiveValue upperBound,
    SearchStopReason stopReason,
    List<PlannerDiagnostic> diagnostics
) {
    public OptimalityCertificate {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(lowerBound, "lower bound");
        Objects.requireNonNull(upperBound, "upper bound");
        Objects.requireNonNull(stopReason, "stop reason");
        diagnostics = List.copyOf(diagnostics);
        if (exploredStates < 0 || prunedStates < 0 || pendingStates < 0) {
            throw new IllegalArgumentException("search counts must be non-negative");
        }
        if (verdict != SearchVerdict.NONE && scope == null) {
            throw new IllegalArgumentException("searched verdict requires scope");
        }

        boolean hasIncumbent = ObjectiveValue.outputRank(lowerBound.qout()) > 0;
        if (verdict == SearchVerdict.CURRENT_BEST && !hasIncumbent) {
            throw new IllegalArgumentException("current best requires incumbent");
        }
        if (verdict == SearchVerdict.SEARCH_INCOMPLETE && hasIncumbent) {
            throw new IllegalArgumentException("incomplete search must not carry incumbent");
        }
        if (verdict == SearchVerdict.PATCH_OPTIMAL && !hasIncumbent) {
            throw new IllegalArgumentException("optimal proof requires incumbent");
        }
        if ((verdict == SearchVerdict.PATCH_OPTIMAL || verdict == SearchVerdict.PROVEN_INFEASIBLE)
            && pendingStates != 0) {
            throw new IllegalArgumentException("proven verdict requires empty queue");
        }
        if (verdict == SearchVerdict.PROVEN_INFEASIBLE && hasIncumbent) {
            throw new IllegalArgumentException("infeasible proof cannot carry incumbent");
        }

        if (verdict == SearchVerdict.CURRENT_BEST || verdict == SearchVerdict.SEARCH_INCOMPLETE) {
            if (stopReason == SearchStopReason.NONE) {
                throw new IllegalArgumentException("incomplete verdict requires stop reason");
            }
            DiagnosticCode required = stopReason == SearchStopReason.MODEL_INCOMPLETE
                ? DiagnosticCode.UNSUPPORTED_CONTENT
                : DiagnosticCode.SEARCH_LIMIT_REACHED;
            if (!contains(diagnostics, required)) {
                throw new IllegalArgumentException("incomplete verdict requires matching diagnostic");
            }
            if (stopReason != SearchStopReason.MODEL_INCOMPLETE && pendingStates == 0) {
                throw new IllegalArgumentException("limited verdict requires pending states");
            }
        }

        if (verdict == SearchVerdict.PATCH_OPTIMAL || verdict == SearchVerdict.PROVEN_INFEASIBLE) {
            if (stopReason != SearchStopReason.NONE) {
                throw new IllegalArgumentException("proven verdict cannot have stop reason");
            }
            if (contains(diagnostics, DiagnosticCode.SEARCH_LIMIT_REACHED)) {
                throw new IllegalArgumentException("proven verdict cannot have search limit diagnostic");
            }
        }

        if (scope != null && stopReason != SearchStopReason.MODEL_INCOMPLETE) {
            PlanObjectiveOrder order = PlanObjectiveOrder.forProfile(scope.request().profile());
            if (order.compare(upperBound, lowerBound) < 0) {
                throw new IllegalArgumentException("upper bound must not be below lower bound");
            }
            if (verdict == SearchVerdict.PATCH_OPTIMAL && order.compare(upperBound, lowerBound) != 0) {
                throw new IllegalArgumentException("proven bounds must match");
            }
        }
        if (verdict == SearchVerdict.PROVEN_INFEASIBLE
            && (!isZero(lowerBound) || !isZero(upperBound))) {
            throw new IllegalArgumentException("infeasible proof requires zero bounds");
        }
    }


    public boolean optimal() {
        return verdict == SearchVerdict.PATCH_OPTIMAL;
    }

    public static OptimalityCertificate none() {
        ObjectiveValue none = ObjectiveValue.zero("none");
        return new OptimalityCertificate(
            SearchVerdict.NONE,
            null,
            0,
            0,
            0,
            none,
            none,
            SearchStopReason.NONE,
            List.of()
        );
    }


    private static boolean contains(List<PlannerDiagnostic> diagnostics, DiagnosticCode code) {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.code() == code);
    }

    private static boolean isZero(ObjectiveValue value) {
        return ObjectiveValue.outputRank(value.qout()) == 0
            && value.coveredOreCells() == 0
            && value.materials().amounts().isEmpty()
            && value.space() == 0
            && value.complexity() == 0;
    }
}
