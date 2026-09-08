package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.ItemTransportSpec;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.Duct;

public final class DuctAdapter implements CapabilityAdapter<Duct, ItemTransportSpec> {
    @Override
    public boolean supports(Block block) {
        return block instanceof Duct;
    }

    @Override
    public ItemTransportSpec describe(Duct block, CapabilityContext context) {
        TransportMechanisms.requireStandardBuild(block);
        return new ItemTransportSpec(
            AdapterSupport.id(block),
            block.size,
            TransportMechanisms.ductCapacity(block),
            0,
            false,
            AdapterSupport.cost(block, context),
            autodrillnext.simulation.SimulationFidelity.BOUNDED_MODEL,
            block.armored ? ItemTransportSpec.PortBehavior.ARMORED_DUCT : ItemTransportSpec.PortBehavior.DUCT,
            TransportConsumers.powerPerSecond(block)
        );
    }
}
