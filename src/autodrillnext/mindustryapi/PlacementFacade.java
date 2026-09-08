package autodrillnext.mindustryapi;

import mindustry.game.Team;
import mindustry.world.Block;
import mindustry.world.Build;

import java.util.Objects;

public final class PlacementFacade {
    public boolean validPlace(Block block, Team team, int x, int y, int rotation) {
        return Build.validPlace(
            Objects.requireNonNull(block, "block"),
            Objects.requireNonNull(team, "team"),
            x,
            y,
            Math.floorMod(rotation, 4)
        );
    }

    public boolean validPlaceIgnoreUnits(
        Block block,
        Team team,
        int x,
        int y,
        int rotation,
        boolean checkWorld,
        boolean checkAdjacent
    ) {
        return Build.validPlaceIgnoreUnits(
            Objects.requireNonNull(block, "block"),
            Objects.requireNonNull(team, "team"),
            x,
            y,
            Math.floorMod(rotation, 4),
            checkWorld,
            checkAdjacent
        );
    }
}
