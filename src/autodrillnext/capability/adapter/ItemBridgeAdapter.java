package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.simulation.SimulationFidelity;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.liquid.LiquidBridge;

public class ItemBridgeAdapter implements CapabilityAdapter<ItemBridge, ItemTransportSpec> {
    @Override
    public boolean supports(Block block) {
        return block instanceof ItemBridge && !(block instanceof LiquidBridge);
    }

    @Override
    public ItemTransportSpec describe(ItemBridge block, CapabilityContext context) {
        TransportMechanisms.requireStandardBuild(block);
        float capacity = TransportMechanisms.itemBridgeCapacity(block);
        return new ItemTransportSpec(
            AdapterSupport.id(block),
            block.size,
            capacity,
            block.range,
            true,
            AdapterSupport.cost(block, context),
            SimulationFidelity.BOUNDED_MODEL,
            ItemTransportSpec.PortBehavior.ITEM_BRIDGE,
            TransportConsumers.powerPerSecond(block)
        );
    }
}
