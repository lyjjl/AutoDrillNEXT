package autodrillnext.testsupport;

import autodrillnext.model.ContentId;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.ExitPort;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileState;
import autodrillnext.world.WorldSnapshot;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Visible terrain transcribed from the supplied 955×601 screenshot. */
public final class ScreenshotWorldScenario {
    private static final int WIDTH = 19;
    private static final int HEIGHT = 11;
    private static final ContentId LEAD = ContentId.of("mindustry:lead");
    private static final Set<TileKey> WALLS = cells(
        row(0, 10, 11, 12, 13, 14, 15, 16, 17, 18),
        row(1, 9, 10, 11, 12, 13, 14, 18),
        row(2, 5, 6, 7, 8, 9, 10, 11, 12),
        row(3, 3, 4, 5, 6, 7, 8, 9),
        row(4, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
        row(5, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
        row(6, 0, 1, 2, 3, 4, 5, 6, 7, 8),
        row(7, 0, 1, 2, 3, 4),
        row(8, 0, 1, 2)
    );
    private static final Set<TileKey> LEAD_ORE = cells(
        row(1, 15),
        row(2, 13, 14, 15, 16),
        row(3, 12, 13, 14, 15, 16, 17),
        row(4, 11, 13, 14, 15, 16, 17),
        row(5, 11, 13, 14, 15, 16, 17),
        row(6, 9, 10, 11, 12, 13),
        row(7, 5, 6, 7, 8, 9),
        row(8, 3, 4, 5, 6, 7, 8, 9),
        row(9, 2, 3, 4, 5, 6, 7, 8, 9)
    );

    private ScreenshotWorldScenario() {
    }

    public static WorldSnapshot world() {
        LinkedHashMap<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (outside(x, y)) continue;
                TileKey tile = new TileKey(x, y);
                tiles.put(tile, new TileState(false, WALLS.contains(tile), false, null, null,
                    LEAD_ORE.contains(tile) ? LEAD : null, null));
            }
        }
        return WorldSnapshot.of(tiles);
    }

    public static TileKey seed() {
        return new TileKey(14, 3);
    }

    public static ExitPort blockedExit() {
        return new ExitPort(
            "screenshot-blocked",
            ExitPort.Side.BOTTOM,
            ExitPort.Bias.CENTER,
            List.of(
                new ExitAnchor(new TileKey(4, 2), ExitPort.Side.BOTTOM, ExitPort.Bias.CENTER, 0),
                new ExitAnchor(new TileKey(3, 2), ExitPort.Side.BOTTOM, ExitPort.Bias.CENTER, 1),
                new ExitAnchor(new TileKey(2, 2), ExitPort.Side.BOTTOM, ExitPort.Bias.CENTER, 2)
            )
        );
    }

    public static Set<TileKey> walls() {
        return WALLS;
    }

    public static Set<TileKey> leadOre() {
        return LEAD_ORE;
    }

    private static boolean outside(int x, int y) {
        return x < switch (y) {
            case 0 -> 10;
            case 1 -> 9;
            case 2 -> 5;
            case 3 -> 3;
            default -> 0;
        };
    }

    private static Set<TileKey> cells(TileKey[]... rows) {
        LinkedHashSet<TileKey> result = new LinkedHashSet<>();
        for (TileKey[] row : rows) {
            for (TileKey tile : row) result.add(tile);
        }
        return Set.copyOf(result);
    }

    private static TileKey[] row(int y, int... columns) {
        TileKey[] tiles = new TileKey[columns.length];
        for (int index = 0; index < columns.length; index++) tiles[index] = new TileKey(columns[index], y);
        return tiles;
    }
}
