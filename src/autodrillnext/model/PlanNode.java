package autodrillnext.model;

import autodrillnext.world.TileKey;

import java.util.Objects;

public record PlanNode(
    String id,
    Kind kind,
    TileKey tile,
    float productionPerSecond,
    ContentId output
) {
    public enum Kind {
        SOURCE,
        JUNCTION,
        SINK,
        DOWNSTREAM,
        EXISTING
    }

    public PlanNode {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("node id must not be blank");
        Objects.requireNonNull(kind, "node kind");
        Objects.requireNonNull(tile, "node tile");
        if (!Float.isFinite(productionPerSecond) || productionPerSecond < 0f) {
            throw new IllegalArgumentException("node production must be finite and non-negative");
        }
    }

    public static PlanNode source(String id, float productionPerSecond, TileKey tile) {
        return new PlanNode(id, Kind.SOURCE, tile, productionPerSecond, null);
    }

    public static PlanNode source(String id, float productionPerSecond, TileKey tile, ContentId output) {
        return new PlanNode(id, Kind.SOURCE, tile, productionPerSecond, output);
    }

    public static PlanNode junction(String id, TileKey tile) {
        return new PlanNode(id, Kind.JUNCTION, tile, 0f, null);
    }

    public static PlanNode sink(String id, TileKey tile) {
        return new PlanNode(id, Kind.SINK, tile, 0f, null);
    }

    public static PlanNode downstream(String id, TileKey tile) {
        return new PlanNode(id, Kind.DOWNSTREAM, tile, 0f, null);
    }

    public static PlanNode existing(String id, TileKey tile) {
        return new PlanNode(id, Kind.EXISTING, tile, 0f, null);
    }
}
