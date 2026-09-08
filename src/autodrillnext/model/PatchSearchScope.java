package autodrillnext.model;

import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.simulation.SimulationFidelity;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.OrePatch;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.WorldSnapshot;

import java.util.Objects;

public record PatchSearchScope(
    WorldSnapshot world,
    OrePatch patch,
    CapabilitySnapshot capabilities,
    Inventory inventory,
    ExistingNetwork existingNetwork,
    ExitAnchor exit,
    PlannerRequest request,
    String selectedDrillId,
    String selectedLiquidId,
    SimulationFidelity simulationFidelity,
    boolean modelComplete
) {
    public PatchSearchScope {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(existingNetwork, "existing network");
        Objects.requireNonNull(exit, "exit");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(simulationFidelity, "simulation fidelity");

        world = world.placementRules() == null
            ? WorldSnapshot.of(world.tiles(), world.revision())
            : WorldSnapshot.captured(world.tiles(), world.revision(), world.placementRules());
        patch = OrePatch.of(patch.ore(), patch.origin(), patch.relativeCells());
        capabilities = new CapabilitySnapshot(capabilities.descriptors());
        inventory = Inventory.of(inventory.amounts());
        existingNetwork = new ExistingNetwork(
            existingNetwork.links(),
            existingNetwork.traversableTiles(),
            existingNetwork.powerSupplyPerSecond(),
            existingNetwork.liquidSupplyPerSecond(),
            existingNetwork.occupiedTiles()
        );
        selectedDrillId = normalize(selectedDrillId);
        selectedLiquidId = normalize(selectedLiquidId);
    }

    public static PatchSearchScope capture(
        WorldSnapshot world,
        OrePatch patch,
        CapabilitySnapshot capabilities,
        Inventory inventory,
        ExistingNetwork existingNetwork,
        ExitAnchor exit,
        PlannerRequest request,
        String selectedDrillId,
        String selectedLiquidId,
        SimulationFidelity simulationFidelity,
        boolean modelComplete
    ) {
        return new PatchSearchScope(
            world,
            patch,
            capabilities,
            inventory,
            existingNetwork,
            exit,
            request,
            selectedDrillId,
            selectedLiquidId,
            simulationFidelity,
            modelComplete
        );
    }

    public TerrainSnapshot terrain() {
        return TerrainSnapshot.of(world);
    }

    public TerrainSnapshot routingTerrain() {
        int maximumTransportReach = capabilities.descriptors().values().stream()
            .filter(descriptor -> descriptor.kind() == CapabilityKind.ITEM_TRANSPORT)
            .filter(descriptor -> descriptor.states().contains(CapabilityState.AVAILABLE_NOW))
            .map(descriptor -> (ItemTransportSpec) descriptor.spec())
            .filter(ItemTransportSpec::supportedGeometry)
            .mapToInt(spec -> Math.max(spec.size(), spec.range()))
            .max()
            .orElse(1);
        int margin = Math.max(4, maximumTransportReach + 2);
        return TerrainSnapshot.between(world, patch, exit, margin, request.maxTiles());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
