package autodrillnext.world;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class WorldSnapshot {
    private final Map<TileKey, TileState> tiles;
    private final TerrainRevision revision;
    private final PlacementRules placementRules;

    private WorldSnapshot(Map<TileKey, TileState> tiles, TerrainRevision revision, PlacementRules placementRules) {
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

    public static WorldSnapshot of(Map<TileKey, TileState> tiles) {
        return of(tiles, TerrainRevision.initial());
    }

    public static WorldSnapshot of(Map<TileKey, TileState> tiles, TerrainRevision revision) {
        return new WorldSnapshot(tiles, revision, null);
    }

    public static WorldSnapshot captured(Map<TileKey, TileState> tiles, TerrainRevision revision, PlacementRules rules) {
        return new WorldSnapshot(tiles, revision, Objects.requireNonNull(rules, "placement rules"));
    }

    public PlacementRules placementRules() {
        return placementRules;
    }

    public TileState tile(TileKey key) {
        return tiles.get(key);
    }

    public TileState requireTile(TileKey key) {
        TileState state = tile(key);
        if (state == null) throw new IllegalArgumentException("unknown tile: " + key);
        return state;
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

    public WorldSnapshot translated(int dx, int dy) {
        LinkedHashMap<TileKey, TileState> translated = new LinkedHashMap<>();
        tiles.forEach((key, state) -> translated.put(key.translated(dx, dy), state));
        return new WorldSnapshot(translated, revision,
            placementRules == null ? null : placementRules.translated(dx, dy));
    }
}
