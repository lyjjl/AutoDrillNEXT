package autodrillnext.world;

import autodrillnext.model.ContentId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertThrows;
class OrePatchAnalyzerTest {
    @Test
    void patchMembershipUsesOreIdentityAndEightNeighborTopology() {
        ContentId copper = ContentId.of("mindustry:copper");
        Map<TileKey, TileState> tiles = new LinkedHashMap<>();
        tiles.put(new TileKey(10, 10), new TileState(false, false, false, null, null, copper, null));
        tiles.put(new TileKey(11, 11), new TileState(false, false, false, "wall-proxy", "sharded", copper, null));
        tiles.put(new TileKey(11, 10), new TileState(false, false, false, null, null, ContentId.of("mindustry:lead"), null));

        OrePatch patch = new OrePatchAnalyzer().analyze(WorldSnapshot.of(tiles), new TileKey(10, 10));

        assertEquals(copper, patch.ore());
        assertEquals(Set.of(new TileOffset(0, 0), new TileOffset(1, 1)), patch.relativeCells());
        assertTrue(patch.contains(new TileKey(11, 11)));
    }
    @Test
    void sandIsNeverAcceptedAsAnOrePatch() {
        TileKey seed = new TileKey(10, 10);
        WorldSnapshot world = WorldSnapshot.of(Map.of(
            seed, new TileState(false, false, false, null, null, ContentId.of("sand"), null)
        ));

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> new OrePatchAnalyzer().analyze(world, seed)
        );

        assertTrue(failure.getMessage().contains("does not expose ore"));
    }


    @Test
    void translatedWorldsHaveIdenticalRelativePatchTopology() {
        ContentId thorium = ContentId.of("mindustry:thorium");
        Map<TileKey, TileState> tiles = Map.of(
            new TileKey(3, 4), new TileState(false, false, false, null, null, thorium, null),
            new TileKey(4, 4), new TileState(false, false, false, null, null, thorium, null),
            new TileKey(5, 5), new TileState(false, false, false, null, null, thorium, null)
        );
        WorldSnapshot original = WorldSnapshot.of(tiles);
        WorldSnapshot translated = original.translated(100, -20);

        OrePatch first = new OrePatchAnalyzer().analyze(original, new TileKey(3, 4));
        OrePatch second = new OrePatchAnalyzer().analyze(translated, new TileKey(103, -16));

        assertEquals(first.relativeCells(), second.relativeCells());
        assertEquals(first.ore(), second.ore());
    }
}
