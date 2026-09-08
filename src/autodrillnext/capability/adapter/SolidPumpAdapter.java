package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.LiquidProviderSpec;
import autodrillnext.model.LiquidId;
import mindustry.world.Block;
import mindustry.world.blocks.production.SolidPump;

public final class SolidPumpAdapter implements CapabilityAdapter<SolidPump, LiquidProviderSpec> {
    @Override
    public boolean supports(Block block) {
        return block instanceof SolidPump;
    }

    @Override
    public LiquidProviderSpec describe(SolidPump block, CapabilityContext context) {
        if (block.result == null) throw new IllegalStateException("solid pump result is not initialized");
        float supply = block.consumeTime <= 0f ? 0f : block.pumpAmount / block.consumeTime * 60f;
        return new LiquidProviderSpec(
            AdapterSupport.id(block),
            block.size,
            LiquidId.of(block.result.name),
            AdapterSupport.positiveOrZero(supply * block.baseEfficiency),
            AdapterSupport.cost(block, context)
        );
    }
}
