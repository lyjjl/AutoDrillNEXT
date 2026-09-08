package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.ExitPort;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.TileState;

import java.util.Map;
import java.util.Objects;

public final class ExitFeasibilitySolver {
    public ExitResolution resolve(
        ExitPort port,
        TerrainSnapshot terrain,
        CapabilitySnapshot capabilities
    ) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(capabilities, "capabilities");
        for (ExitAnchor anchor : port.anchors()) {
            TileState state = terrain.tile(anchor.tile());
            if (state == null || state.fogged() || state.solid() || state.deepLiquid()) continue;
            if (state.isReservedByExistingBuild()) continue;
            return ExitResolution.resolved(anchor);
        }
        return ExitResolution.unresolved(new PlannerDiagnostic(
            DiagnosticCode.NO_VALID_SINK,
            Map.of("anchors", anchors(port, terrain), "snapshot", bounds(terrain))
        ));
    }
    private String anchors(ExitPort port, TerrainSnapshot terrain) {
        StringBuilder result = new StringBuilder();
        for (ExitAnchor anchor : port.anchors()) {
            if (!result.isEmpty()) result.append("; ");
            result.append(anchor.tile().x()).append(',').append(anchor.tile().y())
                .append('=').append(state(terrain.tile(anchor.tile())));
        }
        return result.toString();
    }

    private String bounds(TerrainSnapshot terrain) {
        if (terrain.tiles().isEmpty()) return "empty";
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (var tile : terrain.tiles().keySet()) {
            minX = Math.min(minX, tile.x());
            maxX = Math.max(maxX, tile.x());
            minY = Math.min(minY, tile.y());
            maxY = Math.max(maxY, tile.y());
        }
        return minX + ".." + maxX + " x " + minY + ".." + maxY;
    }

    private String state(TileState state) {
        if (state == null) return "outside";
        if (state.fogged()) return "fogged";
        if (state.solid()) return "solid";
        if (state.deepLiquid()) return "deep-liquid";
        if (state.isReservedByExistingBuild()) return "occupied";
        return "open";
    }
}
