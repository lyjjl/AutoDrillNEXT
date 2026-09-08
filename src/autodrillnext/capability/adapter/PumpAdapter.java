package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.LiquidProviderSpec;
import mindustry.world.Block;
import mindustry.world.blocks.production.Pump;
import mindustry.world.blocks.production.SolidPump;

public class PumpAdapter implements CapabilityAdapter<Pump, LiquidProviderSpec> {
    @Override
    public boolean supports(Block block) {
        return block instanceof Pump && !(block instanceof SolidPump);
    }
    @Override
    public LiquidProviderSpec describe(Pump block, CapabilityContext context) {
        float supply = block.consumeTime <= 0f ? 0f : block.pumpAmount / block.consumeTime * 60f;
        return new LiquidProviderSpec(
            AdapterSupport.id(block),
            block.size,
            null,
            AdapterSupport.positiveOrZero(supply),
            AdapterSupport.cost(block, context)
        );
    }
}
