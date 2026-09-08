package autodrillnext.model;

import autodrillnext.world.ExitPort;
import autodrillnext.world.TileKey;

import java.util.Objects;

public record PlannerRequest(
    TileKey seed,
    String teamId,
    ExitPort exit,
    Profile profile,
    SearchStrategy searchStrategy,
    float targetQout,
    float transportHeadroom,
    BudgetMode budgetMode,
    boolean allowDestructiveRelayout,
    int maxTiles,
    int maxIterations,
    boolean allowMixedTransport
) {
    public enum Profile {
        BALANCED,
        THROUGHPUT,
        LOW_COST,
        LOW_COMPLEXITY
    }
    public enum SearchStrategy {
        EXHAUSTIVE,
        HEURISTIC
    }

    public enum BudgetMode {
        CURRENT_INVENTORY,
        INFINITE_RESOURCES
    }

    public PlannerRequest(
        TileKey seed,
        String teamId,
        ExitPort exit,
        Profile profile,
        float targetQout,
        float transportHeadroom,
        BudgetMode budgetMode,
        boolean allowDestructiveRelayout,
        int maxTiles,
        int maxIterations,
        boolean allowMixedTransport
    ) {
        this(
            seed,
            teamId,
            exit,
            profile,
            SearchStrategy.EXHAUSTIVE,
            targetQout,
            transportHeadroom,
            budgetMode,
            allowDestructiveRelayout,
            maxTiles,
            maxIterations,
            allowMixedTransport
        );
    }
    public PlannerRequest {
        Objects.requireNonNull(seed, "seed");
        if (teamId == null || teamId.isBlank()) throw new IllegalArgumentException("team id must not be blank");
        Objects.requireNonNull(exit, "exit");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(searchStrategy, "search strategy");
        Objects.requireNonNull(budgetMode, "budget mode");
        if (!Float.isFinite(targetQout) || targetQout < 0f) throw new IllegalArgumentException("invalid target qout");
        if (!Float.isFinite(transportHeadroom) || transportHeadroom < 1f) {
            throw new IllegalArgumentException("transport headroom must be at least one");
        }
        if (maxTiles <= 0 || maxIterations <= 0) throw new IllegalArgumentException("planner limits must be positive");
    }

    public static PlannerRequest defaults(TileKey seed, String teamId, ExitPort exit) {
        return new PlannerRequest(
            seed,
            teamId,
            exit,
            Profile.BALANCED,
            0f,
            1.10f,
            BudgetMode.CURRENT_INVENTORY,
            false,
            4096,
            1000,
            false
        );
    }
}
