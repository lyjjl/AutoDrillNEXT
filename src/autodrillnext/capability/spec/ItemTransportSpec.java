package autodrillnext.capability.spec;

import autodrillnext.model.ContentId;
import autodrillnext.simulation.SimulationFidelity;

import java.util.Objects;

public record ItemTransportSpec(
    ContentId id,
    int size,
    float capacityPerSecond,
    int range,
    boolean bridge,
    CostVector cost,
    SimulationFidelity simulationFidelity,
    PortBehavior portBehavior,
    float powerPerSecond
) implements CapabilitySpec {
    public ItemTransportSpec(
        ContentId id,
        int size,
        float capacityPerSecond,
        int range,
        boolean bridge,
        CostVector cost
    ) {
        this(id, size, capacityPerSecond, range, bridge, cost, SimulationFidelity.UNSUPPORTED);
    }
    public ItemTransportSpec(ContentId id, int size, float capacityPerSecond, int range,
                             boolean bridge, CostVector cost, SimulationFidelity fidelity) {
        this(id, size, capacityPerSecond, range, bridge, cost, fidelity,
            bridge ? PortBehavior.ITEM_BRIDGE : PortBehavior.CONVEYOR);
    }

    public ItemTransportSpec(ContentId id, int size, float capacityPerSecond, int range,
                             boolean bridge, CostVector cost, SimulationFidelity fidelity, PortBehavior behavior) {
        this(id, size, capacityPerSecond, range, bridge, cost, fidelity, behavior, 0f);
    }

    public enum PortBehavior {
        CONVEYOR, ARMORED_CONVEYOR, DUCT, ARMORED_DUCT, ITEM_BRIDGE, DUCT_BRIDGE
    }

    public boolean supportedGeometry() {
        return size == 1;
    }
    public ItemTransportSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(simulationFidelity, "simulation fidelity");
        Objects.requireNonNull(portBehavior, "port behavior");
        if (bridge != (portBehavior == PortBehavior.ITEM_BRIDGE || portBehavior == PortBehavior.DUCT_BRIDGE)) {
            throw new IllegalArgumentException("bridge flag must match port behavior");
        }
        if (size < 1 || range < 0) throw new IllegalArgumentException("invalid transport dimensions");
        if (!Float.isFinite(powerPerSecond) || powerPerSecond < 0f) {
            throw new IllegalArgumentException("power demand must be finite and non-negative");
        }
        if (!Float.isFinite(capacityPerSecond) || capacityPerSecond < 0f) {
            throw new IllegalArgumentException("capacity must be finite and non-negative");
        }
    }
}
