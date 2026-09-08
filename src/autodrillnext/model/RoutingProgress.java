package autodrillnext.model;

import autodrillnext.world.TileKey;

import java.util.List;
import java.util.Objects;

/** Immutable observation of the routing work currently executing on the planning worker. */
public record RoutingProgress(Phase phase, List<TransportPlacement> placements, List<TileKey> frontier,
                              TileKey focus, long expandedStates, int connectedSources, int totalSources,
                              PlanGraph candidate, int removedBlocks) {
    public RoutingProgress {
        Objects.requireNonNull(phase);
        placements = List.copyOf(placements);
        frontier = List.copyOf(frontier);
    }

    public enum Phase { SEARCHING, CONNECTED, CANDIDATE, SIMPLIFYING, SIMPLIFIED, NO_ROUTE }
}
