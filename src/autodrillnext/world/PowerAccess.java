package autodrillnext.world;

import java.util.Objects;

/** Existing graph supply only; stored battery energy is not sustainable generation. */
public record PowerAccess(
    int graphId,
    TileKey connector,
    float availablePerSecond,
    int freeConnections,
    boolean requiresLink
) {
    public PowerAccess {
        Objects.requireNonNull(connector, "connector");
        if (!Float.isFinite(availablePerSecond) || availablePerSecond < 0f || freeConnections < 0) {
            throw new IllegalArgumentException("invalid existing power access");
        }
    }

    public PowerAccess translated(int dx, int dy) {
        return new PowerAccess(graphId, connector.translated(dx, dy), availablePerSecond, freeConnections, requiresLink);
    }
}
