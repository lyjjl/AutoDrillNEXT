package autodrillnext.world;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class PlacementFootprint {
    private final Set<TileKey> tiles;

    private PlacementFootprint(Set<TileKey> tiles) {
        if (tiles.isEmpty()) throw new IllegalArgumentException("placement footprint must not be empty");
        LinkedHashSet<TileKey> copy = new LinkedHashSet<>();
        tiles.forEach(tile -> copy.add(Objects.requireNonNull(tile, "footprint tile")));
        this.tiles = Collections.unmodifiableSet(copy);
    }

    public static PlacementFootprint of(Set<TileKey> tiles) {
        return new PlacementFootprint(tiles);
    }

    public static PlacementFootprint square(TileKey center, int size) {
        if (size <= 0) throw new IllegalArgumentException("footprint size must be positive");
        LinkedHashSet<TileKey> tiles = new LinkedHashSet<>();
        int low = (size - 1) / 2;
        for (int dx = -low; dx < size - low; dx++) {
            for (int dy = -low; dy < size - low; dy++) {
                tiles.add(new TileKey(center.x() + dx, center.y() + dy));
            }
        }
        return new PlacementFootprint(tiles);
    }

    public Set<TileKey> tiles() {
        return tiles;
    }

    public boolean overlaps(PlacementFootprint other) {
        for (TileKey tile : tiles) {
            if (other.tiles.contains(tile)) return true;
        }
        return false;
    }
}
