package autodrillnext.world;

import autodrillnext.model.ContentId;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

public final class OrePatchAnalyzer {
    public OrePatch analyze(WorldSnapshot world, TileKey seed) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(seed, "seed");
        TileState seedState = world.requireTile(seed);
        ContentId ore = OreSelector.selectableOre(seedState);
        if (ore == null || seedState.fogged()) {
            throw new IllegalArgumentException("seed tile does not expose ore: " + seed);
        }

        Queue<TileKey> pending = new ArrayDeque<>();
        Set<TileKey> visited = new LinkedHashSet<>();
        pending.add(seed);
        visited.add(seed);
        while (!pending.isEmpty()) {
            TileKey current = pending.remove();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    TileKey neighbor = new TileKey(current.x() + dx, current.y() + dy);
                    if (visited.contains(neighbor)) continue;
                    TileState state = world.tile(neighbor);
                    if (state == null || state.fogged() || !ore.equals(OreSelector.selectableOre(state))) continue;
                    visited.add(neighbor);
                    pending.add(neighbor);
                }
            }
        }

        LinkedHashSet<TileOffset> relative = new LinkedHashSet<>();
        for (TileKey tile : visited) relative.add(tile.offsetFrom(seed));
        return OrePatch.of(ore, seed, relative);
    }
}
