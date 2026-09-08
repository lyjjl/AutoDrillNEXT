package autodrillnext.world;

import autodrillnext.model.ContentId;

public record TileState(
    boolean fogged,
    boolean solid,
    boolean deepLiquid,
    String existingBlock,
    String existingTeam,
    ContentId floorOre,
    ContentId wallOre
) {
    public TileState {
        if (existingBlock == null && existingTeam != null) {
            throw new IllegalArgumentException("existing team requires an existing block");
        }
    }

    public ContentId ore() {
        return floorOre != null ? floorOre : wallOre;
    }

    public boolean hasOre() {
        return ore() != null;
    }

    public boolean isReservedByExistingBuild() {
        return existingBlock != null;
    }

    public static TileState empty() {
        return new TileState(false, false, false, null, null, null, null);
    }
}
