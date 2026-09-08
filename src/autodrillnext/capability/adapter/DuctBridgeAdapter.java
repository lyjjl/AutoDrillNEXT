package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.ItemTransportSpec;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.DuctBridge;

public final class DuctBridgeAdapter implements CapabilityAdapter<DuctBridge, ItemTransportSpec> {
    @Override
    public boolean supports(Block block) {
        return block instanceof DuctBridge;
    }

    @Override
    public ItemTransportSpec describe(DuctBridge block, CapabilityContext context) {
        TransportMechanisms.requireStandardBuild(block);
        return new ItemTransportSpec(
            AdapterSupport.id(block),
            block.size,
            TransportMechanisms.ductBridgeCapacity(block),
            block.range,
            true,
            AdapterSupport.cost(block, context),
            autodrillnext.simulation.SimulationFidelity.BOUNDED_MODEL,
            ItemTransportSpec.PortBehavior.DUCT_BRIDGE,
            TransportConsumers.powerPerSecond(block)
        );
    }
}
