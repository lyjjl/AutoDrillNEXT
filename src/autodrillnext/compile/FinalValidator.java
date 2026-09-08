package autodrillnext.compile;

import autodrillnext.model.DiagnosticCode;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlannerDiagnostic;
import autodrillnext.world.TileKey;
import autodrillnext.solver.FlowSolver;
import autodrillnext.world.TileState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FinalValidator {
    private static final float EPSILON = 0.0001f;

    public ValidationResult validate(PlanGraph graph, LiveSnapshot live) {
        return validate(graph, live, null);
    }

    public ValidationResult validate(PlanGraph graph, LiveSnapshot live, MiningLayout layout) {
        ArrayList<PlannerDiagnostic> diagnostics = new ArrayList<>(graph.diagnostics());
        if (!graph.placements().isEmpty()) {
            List<BuildPlanCompiler.CompileRecord> records = new BuildPlanCompiler().compile(graph, layout);
            diagnostics.addAll(validatePhysical(records, layout, graph.exit(), live.capabilities()).diagnostics());
            for (var placement : graph.placements()) {
                if (!live.terrain().canPlace(placement.spec().id(), placement.tile(), placement.rotation())) {
                    diagnostics.add(blocked(placement.tile(), "block-placement-rule"));
                }
                if (TransportGeometry.conflictsWithExistingDirectionalBridge(placement.spec(), placement.tile(), live.terrain())) {
                    diagnostics.add(blocked(placement.tile(), "existing-directional-bridge-conflict"));
                }
            }
            if (layout != null) {
                for (var candidate : layout.candidates()) {
                    if (!live.terrain().canPlace(candidate.drillId(), candidate.anchor(), candidate.rotation())) {
                        diagnostics.add(blocked(candidate.anchor(), "block-placement-rule"));
                    }
                }
            }
            if (graph.flow() == null || !graph.flow().capacitySafe()) {
                diagnostics.add(PlannerDiagnostic.of(DiagnosticCode.NO_ROUTE));
            }
            for (PlanEdge edge : graph.edges()) {
                if (edge.assignedFlow() > edge.usableCapacity() + EPSILON) diagnostics.add(PlannerDiagnostic.of(DiagnosticCode.NO_ROUTE));
            }
            if (!live.infiniteResources() && !graph.cost().materials().componentWiseAtMost(live.inventory())) {
                diagnostics.add(PlannerDiagnostic.of(DiagnosticCode.BUDGET_DEFICIT));
            }
            return new ValidationResult(diagnostics.isEmpty(), diagnostics);
        }
        if (layout != null && !layout.candidates().isEmpty()) diagnostics.add(disconnected("missing-physical-placements"));
        Set<TileKey> plannedTiles = new HashSet<>();
        if (layout != null) {
            for (var candidate : layout.candidates()) {
                for (TileKey tile : candidate.footprint().tiles()) {
                    if (!plannedTiles.add(tile)) {
                        diagnostics.add(blocked(tile, "overlap"));
                    }
                    validateTerrain(tile, live, diagnostics);
                }
            }
        }
        for (PlanEdge edge : graph.edges()) {
            if (edge.kind() == EdgeKind.EXISTING_EDGE) continue;
            for (TileKey tile : edge.footprint()) {
                if (!plannedTiles.add(tile)) {
                    diagnostics.add(blocked(tile, "overlap"));
                }
                validateTerrain(tile, live, diagnostics);
            }
            if (edge.assignedFlow() > edge.usableCapacity() + EPSILON) {
                diagnostics.add(PlannerDiagnostic.of(DiagnosticCode.NO_ROUTE));
            }
        }

        if (graph.flow() == null && !graph.edges().isEmpty()) {
            diagnostics.add(PlannerDiagnostic.of(DiagnosticCode.NO_ROUTE));
        }
        if (graph.flow() != null && !graph.flow().capacitySafe()) {
            diagnostics.add(PlannerDiagnostic.of(DiagnosticCode.NO_ROUTE));
        }
        if (!live.infiniteResources() && !graph.cost().materials().componentWiseAtMost(live.inventory())) {
            diagnostics.add(PlannerDiagnostic.of(DiagnosticCode.BUDGET_DEFICIT));
        }
        return diagnostics.isEmpty()
            ? new ValidationResult(true, List.of())
            : new ValidationResult(false, diagnostics);
    }

    /** Reconstructs directed connectivity from emitted records, not from claimed graph edges. */
    public ValidationResult validatePhysical(
        List<BuildPlanCompiler.CompileRecord> records,
        MiningLayout layout,
        autodrillnext.world.ExitAnchor exit,
        autodrillnext.capability.spec.CapabilitySnapshot capabilities
    ) {
        ArrayList<PlannerDiagnostic> diagnostics = new ArrayList<>();
        if (layout == null || exit == null) {
            return new ValidationResult(false, List.of(disconnected("missing-layout-or-exit")));
        }

        Set<TileKey> occupied = new HashSet<>();
        Set<TileKey> transportTiles = new HashSet<>();
        for (BuildPlanCompiler.CompileRecord record : records) {
            if (record.operation() != BuildPlanCompiler.Operation.ADD) continue;
            if (!occupied.add(record.tile())) diagnostics.add(blocked(record.tile(), "overlap"));
            var descriptor = capabilities.descriptors().get(autodrillnext.model.ContentId.of(record.blockId()));
            if (descriptor != null
                && descriptor.spec() instanceof autodrillnext.capability.spec.ItemTransportSpec spec) {
                transportTiles.add(record.tile());
                if (!spec.supportedGeometry()) {
                    diagnostics.add(disconnected("unsupported-transport-size:" + record.blockId()));
                }
            }
        }

        Set<TileKey> drillTiles = new HashSet<>();
        for (var drill : layout.candidates()) {
            boolean built = records.stream().anyMatch(record ->
                record.operation() == BuildPlanCompiler.Operation.ADD
                    && record.tile().equals(drill.anchor())
                    && record.blockId().equals(drill.drillId().value())
                    && record.rotation() == drill.rotation()
            );
            for (TileKey tile : drill.footprint().tiles()) {
                if (!drillTiles.add(tile)) diagnostics.add(blocked(tile, "drill-overlap"));
                if (transportTiles.contains(tile)) diagnostics.add(blocked(tile, "drill-transport-overlap"));
            }
            if (!built) diagnostics.add(disconnected("drill-not-built:" + drill.id()));
        }

        var rebuilt = new PhysicalTransportGraphBuilder()
            .fromCompileRecords(records, layout, exit, capabilities);
        if (rebuilt.isEmpty()) {
            diagnostics.add(disconnected("invalid-physical-transport"));
        } else {
            var flow = new FlowSolver().assign(rebuilt.get());
            if (!flow.capacitySafe() || flow.qOut() <= 0f) {
                diagnostics.add(disconnected("physical-flow-infeasible"));
            }
        }
        return new ValidationResult(diagnostics.isEmpty(), diagnostics);
    }

    private PlannerDiagnostic disconnected(String reason) {
        return new PlannerDiagnostic(DiagnosticCode.NO_ROUTE, Map.of("reason", reason));
    }

    private void validateTerrain(TileKey tile, LiveSnapshot live, List<PlannerDiagnostic> diagnostics) {
        TileState state = live.terrain().tile(tile);
        if (state == null) {
            diagnostics.add(blocked(tile, "outside-snapshot"));
        } else if (state.fogged()) {
            diagnostics.add(blocked(tile, "fogged"));
        } else if (state.solid()) {
            diagnostics.add(blocked(tile, "solid"));
        } else if (state.deepLiquid()) {
            diagnostics.add(blocked(tile, "deep-liquid"));
        } else if (state.isReservedByExistingBuild()) {
            diagnostics.add(blocked(tile, "occupied"));
        }
    }

    private PlannerDiagnostic blocked(TileKey tile, String reason) {
        return new PlannerDiagnostic(
            DiagnosticCode.TERRAIN_OR_RULE_BLOCKED,
            Map.of("tile", tile.x() + "," + tile.y(), "reason", reason)
        );
    }
}
