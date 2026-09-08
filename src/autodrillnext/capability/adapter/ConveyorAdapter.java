package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.simulation.SimulationFidelity;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.Conveyor;

public final class ConveyorAdapter implements CapabilityAdapter<Conveyor, ItemTransportSpec> {
    @Override
    public boolean supports(Block block) {
        return block instanceof Conveyor;
    }

    @Override
    public ItemTransportSpec describe(Conveyor block, CapabilityContext context) {
        TransportMechanisms.requireStandardBuild(block);
        return new ItemTransportSpec(
            AdapterSupport.id(block),
            block.size,
            TransportMechanisms.conveyorCapacity(block),
            0,
            false,
            AdapterSupport.cost(block, context),
            SimulationFidelity.BOUNDED_MODEL,
            block instanceof mindustry.world.blocks.distribution.ArmoredConveyor
                ? ItemTransportSpec.PortBehavior.ARMORED_CONVEYOR : ItemTransportSpec.PortBehavior.CONVEYOR,
            TransportConsumers.powerPerSecond(block)
        );
    }
}
