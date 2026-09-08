package autodrillnext.world;

import autodrillnext.model.ContentId;

import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class TerrainSnapshot {
    private final Map<TileKey, TileState> tiles;
    private final TerrainRevision revision;
    private final PlacementRules placementRules;

    private TerrainSnapshot(Map<TileKey, TileState> tiles, TerrainRevision revision, PlacementRules placementRules) {
        LinkedHashMap<TileKey, TileState> ordered = new LinkedHashMap<>();
        tiles.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> ordered.put(
                Objects.requireNonNull(entry.getKey(), "tile key"),
                Objects.requireNonNull(entry.getValue(), "tile state")
            ));
        this.tiles = Collections.unmodifiableMap(ordered);
        this.revision = Objects.requireNonNull(revision, "revision");
        this.placementRules = placementRules;
    }

    public static TerrainSnapshot of(Map<TileKey, TileState> tiles, TerrainRevision revision) {
        return new TerrainSnapshot(tiles, revision, null);
    }

    public static TerrainSnapshot of(WorldSnapshot world) {
        return new TerrainSnapshot(world.tiles(), world.revision(), world.placementRules());
    }

    public boolean hasRuntimeRules() {
        return placementRules != null;
    }

    public boolean canPlace(ContentId blockId, TileKey anchor, int rotation) {
        if (!tiles.containsKey(anchor)) return false;
        if (placementRules != null) return placementRules.canPlace(blockId, anchor, rotation);
        TileState tile = tiles.get(anchor);
        return rotation >= 0 && rotation < 4 && !tile.fogged() && !tile.solid()
            && !tile.deepLiquid() && !tile.isReservedByExistingBuild();
    }

    public MiningResult mining(ContentId blockId, TileKey anchor, int rotation) {
        if (!tiles.containsKey(anchor) || placementRules == null) return null;
        return placementRules.mining(blockId, anchor, rotation);
    }

    public List<PowerAccess> powerAccess(ContentId blockId, TileKey anchor, int rotation) {
        if (!tiles.containsKey(anchor) || placementRules == null) return List.of();
        return placementRules.powerAccess(blockId, anchor, rotation);
    }

    public Map<ContentId, String> runtimeDiagnostics() {
        return placementRules == null ? Map.of() : placementRules.diagnostics();
    }

    public static TerrainSnapshot around(
        WorldSnapshot world,
        OrePatch patch,
        int margin,
        int maxTiles
    ) {
        if (margin < 0) throw new IllegalArgumentException("margin must be non-negative");
        if (maxTiles <= 0) throw new IllegalArgumentException("maxTiles must be positive");
        int minX = patch.minX() - margin;
        int maxX = patch.maxX() + margin;
        int minY = patch.minY() - margin;
        int maxY = patch.maxY() + margin;
        long area = (long) (maxX - minX + 1) * (maxY - minY + 1);
        if (area > maxTiles) throw new IllegalArgumentException("terrain snapshot exceeds tile limit");

        LinkedHashMap<TileKey, TileState> selected = new LinkedHashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                TileKey key = new TileKey(x, y);
                TileState state = world.tile(key);
                if (state != null) selected.put(key, state);
            }
        }
        return new TerrainSnapshot(selected, world.revision(), world.placementRules());
    }

    public static TerrainSnapshot between(
        WorldSnapshot world,
        OrePatch patch,
        ExitAnchor exit,
        int margin,
        int maxTiles
    ) {
        if (margin < 0) throw new IllegalArgumentException("margin must be non-negative");
        if (maxTiles <= 0) throw new IllegalArgumentException("maxTiles must be positive");
        int minX = Math.min(patch.minX(), exit.tile().x()) - margin;
        int maxX = Math.max(patch.maxX(), exit.tile().x()) + margin;
        int minY = Math.min(patch.minY(), exit.tile().y()) - margin;
        int maxY = Math.max(patch.maxY(), exit.tile().y()) + margin;

        LinkedHashMap<TileKey, TileState> selected = new LinkedHashMap<>();
        for (Map.Entry<TileKey, TileState> entry : world.tiles().entrySet()) {
            TileKey key = entry.getKey();
            if (key.x() >= minX && key.x() <= maxX && key.y() >= minY && key.y() <= maxY) {
                selected.put(key, entry.getValue());
            }
        }
        if (selected.size() > maxTiles) {
            throw new IllegalArgumentException("terrain snapshot exceeds tile limit");
        }
        return new TerrainSnapshot(selected, world.revision(), world.placementRules());
    }

    public TileState tile(TileKey key) {
        return tiles.get(key);
    }

    public boolean contains(TileKey key) {
        return tiles.containsKey(key);
    }

    public Map<TileKey, TileState> tiles() {
        return tiles;
    }

    public TerrainRevision revision() {
        return revision;
    }
}
