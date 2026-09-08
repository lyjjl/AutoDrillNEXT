package autodrillnext.model;

import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.world.TileKey;

import java.util.Objects;

/** One physical block, with one selected downstream port and optional bridge target. */
public record TransportPlacement(ItemTransportSpec spec, TileKey tile, int rotation,
                                 TileKey output, TileKey bridgeLink) {
    public TransportPlacement {
        Objects.requireNonNull(spec, "transport spec");
        Objects.requireNonNull(tile, "transport tile");
        Objects.requireNonNull(output, "transport output");
        if (rotation < 0 || rotation > 3) throw new IllegalArgumentException("invalid rotation");
    }
}
