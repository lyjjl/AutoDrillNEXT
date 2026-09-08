package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.DrillSpec;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.ProductionEstimate;
import autodrillnext.world.OrePatch;
import autodrillnext.world.MiningResult;
import autodrillnext.world.PlacementFootprint;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CandidateGenerator {
    public List<DrillCandidate> generate(
        OrePatch patch,
        TerrainSnapshot terrain,
        CapabilitySnapshot capabilities,
        PlannerRequest request
    ) {
        return generate(patch, terrain, capabilities, request, null);
    }

    public List<DrillCandidate> generate(
        OrePatch patch,
        TerrainSnapshot terrain,
        CapabilitySnapshot capabilities,
        PlannerRequest request,
        String selectedDrillId
    ) {
        ArrayList<CapabilityDescriptor> drills = new ArrayList<>();
        for (CapabilityDescriptor descriptor : capabilities.descriptors().values()) {
            if (descriptor.kind() == autodrillnext.capability.spec.CapabilityKind.DRILL
                && descriptor.states().contains(CapabilityState.AVAILABLE_NOW)
                && descriptor.spec() instanceof DrillSpec spec
                && supportsPatch(spec, patch, terrain)) {
                if (selectedDrillId == null
                    || selectedDrillId.isBlank()
                    || descriptor.id().value().equals(selectedDrillId)) {
                    drills.add(descriptor);
                }
            }
        }
        drills.sort(Comparator.comparing(descriptor -> descriptor.id().value()));

        ArrayList<TileKey> anchors = new ArrayList<>(terrain.tiles().keySet());
        anchors.sort(TileKey::compareTo);
        ArrayList<DrillCandidate> generated = new ArrayList<>();
        for (CapabilityDescriptor descriptor : drills) {
            DrillSpec spec = (DrillSpec) descriptor.spec();
            int rotationCount = spec.rotates() ? 4 : 1;
            for (TileKey anchor : anchors) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                for (int rotation = 0; rotation < rotationCount; rotation++) {
                    PlacementFootprint footprint = PlacementFootprint.square(anchor, spec.size());
                    if (!terrain.canPlace(descriptor.id(), anchor, rotation)) continue;
                    if (!legalFootprint(footprint, terrain)) continue;
                    Set<TileKey> covered;
                    ProductionEstimate production;
                    if (terrain.hasRuntimeRules()) {
                        MiningResult mining = terrain.mining(descriptor.id(), anchor, rotation);
                        if (mining == null) continue;
                        MiningResult.Output output = mining.outputs().get(patch.ore());
                        if (output == null) continue;
                        covered = new LinkedHashSet<>(output.coveredTiles());
                        covered.retainAll(patch.absoluteCells());
                        if (covered.isEmpty()) continue;
                        float transportRate = 0f;
                        for (MiningResult.Output mined : mining.outputs().values()) transportRate += mined.itemsPerSecond();
                        production = new ProductionEstimate(patch.ore(), output.itemsPerSecond(), transportRate);
                    } else {
                        covered = coveredOre(spec, patch, terrain, anchor, footprint, rotation);
                        if (covered.isEmpty()) continue;
                        production = new ProductionEstimate(patch.ore(), spec.productionPerSecond(covered.size()));
                    }
                    String id = descriptor.id().value() + "@" + anchor.x() + "," + anchor.y() + "/r" + rotation;
                    generated.add(new DrillCandidate(
                        id,
                        descriptor.id(),
                        anchor,
                        rotation,
                        footprint,
                        covered,
                        production,
                        spec.mandatorySupport(),
                        spec.optionalSupport(),
                        descriptor.cost(),
                        Set.of()
                    ));
                }
            }
        }
        return withConflicts(generated);
    }

    private boolean supportsPatch(DrillSpec spec, OrePatch patch, TerrainSnapshot terrain) {
        TileState source = terrain.tile(patch.origin());
        if (source == null) return false;
        if (patch.ore().equals(source.floorOre())) return spec.minesFloorOre();
        if (patch.ore().equals(source.wallOre())) return spec.minesWallOre();
        return false;
    }

    private boolean legalFootprint(PlacementFootprint footprint, TerrainSnapshot terrain) {
        for (TileKey tile : footprint.tiles()) {
            TileState state = terrain.tile(tile);
            if (state == null || state.fogged()) return false;
            if (!terrain.hasRuntimeRules() && (state.solid() || state.deepLiquid())) return false;
            if (state.isReservedByExistingBuild()) return false;
        }
        return true;
    }

    private Set<TileKey> coveredOre(
        DrillSpec spec,
        OrePatch patch,
        TerrainSnapshot terrain,
        TileKey anchor,
        PlacementFootprint footprint,
        int rotation
    ) {
        LinkedHashSet<TileKey> covered = new LinkedHashSet<>();
        if (spec.range() == 0) {
            for (TileKey tile : footprint.tiles()) {
                if (patch.contains(tile) && hasMatchingOre(terrain.tile(tile), patch)) covered.add(tile);
            }
            return covered;
        }

        for (TileKey tile : footprint.tiles()) {
            if (patch.contains(tile) && hasMatchingOre(terrain.tile(tile), patch)) covered.add(tile);
        }
        int dx = rotation == 0 ? 1 : rotation == 2 ? -1 : 0;
        int dy = rotation == 1 ? 1 : rotation == 3 ? -1 : 0;
        for (int distance = 1; distance <= spec.range(); distance++) {
            TileKey tile = new TileKey(anchor.x() + dx * distance, anchor.y() + dy * distance);
            TileState state = terrain.tile(tile);
            if (state != null && patch.contains(tile) && hasMatchingOre(state, patch)) covered.add(tile);
        }
        return covered;
    }

    private boolean hasMatchingOre(TileState state, OrePatch patch) {
        return state != null && patch.ore().equals(state.ore());
    }

    private List<DrillCandidate> withConflicts(List<DrillCandidate> candidates) {
        Map<TileKey, List<String>> byTile = new HashMap<>();
        for (DrillCandidate candidate : candidates) {
            for (TileKey tile : candidate.footprint().tiles()) {
                byTile.computeIfAbsent(tile, ignored -> new ArrayList<>()).add(candidate.id());
            }
        }

        ArrayList<DrillCandidate> result = new ArrayList<>();
        for (DrillCandidate candidate : candidates) {
            LinkedHashSet<String> conflicts = new LinkedHashSet<>();
            for (TileKey tile : candidate.footprint().tiles()) {
                for (String other : byTile.getOrDefault(tile, List.of())) {
                    if (!candidate.id().equals(other)) conflicts.add(other);
                }
            }
            result.add(candidate.withConflicts(conflicts));
        }
        result.sort(Comparator
            .comparing((DrillCandidate candidate) -> candidate.drillId().value())
            .thenComparing(candidate -> candidate.anchor())
            .thenComparingInt(DrillCandidate::rotation)
            .thenComparing(DrillCandidate::id));
        return List.copyOf(result);
    }
}
