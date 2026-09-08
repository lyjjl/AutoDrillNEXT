package autodrillnext.world;

import java.util.Objects;

public record ExitAnchor(TileKey tile, ExitPort.Side side, ExitPort.Bias bias, int rank) {
    public ExitAnchor {
        Objects.requireNonNull(tile, "tile");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(bias, "bias");
        if (rank < 0) throw new IllegalArgumentException("anchor rank must be non-negative");
    }
}
