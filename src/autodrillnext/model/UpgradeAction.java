package autodrillnext.model;

import autodrillnext.world.TileKey;

import java.util.Objects;

public record UpgradeAction(
    Kind kind,
    TileKey tile,
    String fromBlock,
    String toBlock,
    boolean executable
) {
    public enum Kind {
        ADD,
        RECONFIGURE,
        CONNECT,
        REPLACE,
        REMOVE,
        KEEP
    }

    public UpgradeAction {
        Objects.requireNonNull(kind, "upgrade action kind");
        Objects.requireNonNull(tile, "upgrade tile");
        if (fromBlock == null && kind != Kind.ADD) throw new IllegalArgumentException("non-add action needs source block");
        if (toBlock == null && kind != Kind.REMOVE && kind != Kind.KEEP) {
            throw new IllegalArgumentException("action needs target block");
        }
    }

    public static UpgradeAction add(TileKey tile, String block) {
        return new UpgradeAction(Kind.ADD, tile, null, block, true);
    }

    public static UpgradeAction remove(TileKey tile, String block, boolean executable) {
        return new UpgradeAction(Kind.REMOVE, tile, block, null, executable);
    }
}
