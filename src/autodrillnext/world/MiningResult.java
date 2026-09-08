package autodrillnext.world;

import autodrillnext.model.ContentId;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Sustainable baseline production, including implicit boosts, with mandatory consumers fully supplied. */
public record MiningResult(Map<ContentId, Output> outputs) {
    public MiningResult {
        outputs = Map.copyOf(outputs);
    }

    public record Output(Set<TileKey> coveredTiles, float itemsPerSecond) {
        public Output {
            coveredTiles = Set.copyOf(coveredTiles);
            if (coveredTiles.isEmpty() || !Float.isFinite(itemsPerSecond) || itemsPerSecond <= 0f) {
                throw new IllegalArgumentException("invalid mining output");
            }
        }
    }

    public MiningResult translated(int dx, int dy) {
        Map<ContentId, Output> translated = new LinkedHashMap<>();
        outputs.forEach((item, output) -> {
            Set<TileKey> tiles = new LinkedHashSet<>();
            output.coveredTiles().forEach(tile -> tiles.add(tile.translated(dx, dy)));
            translated.put(item, new Output(tiles, output.itemsPerSecond()));
        });
        return new MiningResult(translated);
    }
}
