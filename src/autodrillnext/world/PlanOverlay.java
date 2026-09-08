package autodrillnext.world;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class PlanOverlay {
    private final Set<TileKey> occupiedTiles = new LinkedHashSet<>();
    private final Set<LinkReservation> links = new LinkedHashSet<>();
    private final Set<String> configs = new LinkedHashSet<>();

    public boolean canReserve(PlacementFootprint footprint) {
        for (TileKey tile : footprint.tiles()) {
            if (occupiedTiles.contains(tile)) return false;
        }
        return true;
    }

    public boolean canReserve(PlanPlacement placement) {
        if (!canReserve(placement.footprint())) return false;
        if (intersects(links, placement.links())) return false;
        return !intersects(configs, placement.configs());
    }

    public void reserve(PlanPlacement placement) {
        if (!canReserve(placement)) throw new IllegalArgumentException("placement conflicts with plan overlay");
        occupiedTiles.addAll(placement.footprint().tiles());
        links.addAll(placement.links());
        configs.addAll(placement.configs());
    }

    public Set<TileKey> occupiedTiles() {
        return Collections.unmodifiableSet(occupiedTiles);
    }

    public Set<LinkReservation> links() {
        return Collections.unmodifiableSet(links);
    }

    public Set<String> configs() {
        return Collections.unmodifiableSet(configs);
    }

    private static <T> boolean intersects(Set<T> first, Set<T> second) {
        for (T value : second) {
            if (first.contains(value)) return true;
        }
        return false;
    }
}
