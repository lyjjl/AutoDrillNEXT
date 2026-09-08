package autodrillnext.model;

import java.util.List;
import java.util.Objects;

/** Latest search geometry plus a bounded history of actual decisions, never an animation queue. */
public record PlanningProgress(
    MiningLayout attempted,
    PlanGraph route,
    MiningLayout bestLayout,
    PlanGraph bestRoute,
    RoutingProgress routing,
    Stage stage,
    Counters counters,
    List<DecisionEvent> decisions
) {
    public PlanningProgress {
        Objects.requireNonNull(attempted, "attempted layout");
        Objects.requireNonNull(stage, "search stage");
        Objects.requireNonNull(counters, "search counters");
        decisions = List.copyOf(decisions);
    }

    public enum Stage {
        HEURISTIC_WARM_START, LAYOUT, ROUTING, EVALUATING, SIMPLIFYING, CERTIFYING, COMPLETE
    }

    public enum Decision {
        NEW_BEST, KEPT_BEST, NO_ROUTE, CAPACITY, SUPPORT, PHYSICAL, SIMULATION, BUDGET, REDUNDANT_REMOVED
    }

    public record Counters(
        long layoutsTried,
        long routesTried,
        long exploredStates,
        long prunedStates,
        long pendingStates,
        long accepted,
        long rejected
    ) {
        public Counters {
            if (layoutsTried < 0 || routesTried < 0 || exploredStates < 0
                || prunedStates < 0 || pendingStates < 0 || accepted < 0 || rejected < 0) {
                throw new IllegalArgumentException("progress counters must be non-negative");
            }
        }
    }

    public record DecisionEvent(long sequence, Decision decision, double qOut, double materialCost,
                                int transportBlocks) {}
}
