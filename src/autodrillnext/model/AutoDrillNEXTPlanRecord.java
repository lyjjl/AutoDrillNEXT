package autodrillnext.model;

import autodrillnext.world.TerrainRevision;
import autodrillnext.world.TileKey;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

public record AutoDrillNEXTPlanRecord(
    String planId,
    Set<TileKey> region,
    Map<TileKey, String> placementFingerprints,
    Set<TileKey> ownedTiles,
    TerrainRevision revision
) {
    public AutoDrillNEXTPlanRecord {
        if (planId == null || planId.isBlank()) throw new IllegalArgumentException("plan id must not be blank");
        region = immutableTiles(region);
        LinkedHashMap<TileKey, String> fingerprints = new LinkedHashMap<>();
        placementFingerprints.forEach((tile, fingerprint) -> {
            fingerprints.put(Objects.requireNonNull(tile, "fingerprint tile"), Objects.requireNonNull(fingerprint, "fingerprint"));
        });
        placementFingerprints = Collections.unmodifiableMap(fingerprints);
        ownedTiles = immutableTiles(ownedTiles);
        Objects.requireNonNull(revision, "revision");
        if (!region.containsAll(ownedTiles)) throw new IllegalArgumentException("owned tiles must be inside region");
    }

    public static AutoDrillNEXTPlanRecord empty(TerrainRevision revision) {
        return new AutoDrillNEXTPlanRecord("none", Set.of(), Map.of(), Set.of(), revision);
    }

    public boolean owns(TileKey tile, String fingerprint) {
        return ownedTiles.contains(tile) && fingerprint.equals(placementFingerprints.get(tile));
    }

    private static Set<TileKey> immutableTiles(Set<TileKey> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
