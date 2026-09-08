package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.LiquidTransportSpec;
import mindustry.world.Block;
import mindustry.world.blocks.liquid.Conduit;

public final class ConduitAdapter implements CapabilityAdapter<Conduit, LiquidTransportSpec> {
    @Override
    public boolean supports(Block block) {
        return block instanceof Conduit;
    }

    @Override
    public LiquidTransportSpec describe(Conduit block, CapabilityContext context) {
        return new LiquidTransportSpec(
            AdapterSupport.id(block),
            block.size,
            AdapterSupport.positiveOrZero(block.liquidCapacity),
            AdapterSupport.positiveOrZero(block.liquidPressure),
            0,
            false,
            AdapterSupport.cost(block, context)
        );
    }
}
