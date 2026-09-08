package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.LiquidTransportSpec;
import mindustry.world.Block;
import mindustry.world.blocks.liquid.LiquidBridge;

public final class LiquidBridgeAdapter implements CapabilityAdapter<LiquidBridge, LiquidTransportSpec> {
    @Override
    public boolean supports(Block block) {
        return block instanceof LiquidBridge;
    }

    @Override
    public LiquidTransportSpec describe(LiquidBridge block, CapabilityContext context) {
        return new LiquidTransportSpec(
            AdapterSupport.id(block),
            block.size,
            AdapterSupport.positiveOrZero(block.liquidCapacity),
            AdapterSupport.positiveOrZero(block.liquidPressure),
            block.range,
            true,
            AdapterSupport.cost(block, context)
        );
    }
}
