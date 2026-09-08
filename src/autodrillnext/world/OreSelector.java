package autodrillnext.world;

import autodrillnext.model.ContentId;

public final class OreSelector {
    private static final String SAND = "sand";

    private OreSelector() {
    }

    public static ContentId selectableOre(TileState tile) {
        if (tile == null) return null;
        return selectableOre(tile.floorOre(), tile.wallOre());
    }

    public static ContentId selectableOre(ContentId floorOre, ContentId wallOre) {
        if (mineable(floorOre)) return floorOre;
        return mineable(wallOre) ? wallOre : null;
    }

    private static boolean mineable(ContentId ore) {
        return ore != null && !SAND.equals(ore.value());
    }
}
