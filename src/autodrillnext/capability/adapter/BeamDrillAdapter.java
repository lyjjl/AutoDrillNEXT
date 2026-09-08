package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.DrillSpec;
import mindustry.world.Block;
import mindustry.world.blocks.production.BeamDrill;


public final class BeamDrillAdapter implements CapabilityAdapter<BeamDrill, DrillSpec> {
    @Override
    public boolean supports(Block block) {
        return block instanceof BeamDrill;
    }

    @Override
    public DrillSpec describe(BeamDrill block, CapabilityContext context) {
        SupportRequirements support = AdapterSupport.supports(block, context, block.optionalBoostIntensity - 1f);
        MiningMechanisms.requireStandardBuild(block);
        return new DrillSpec(
            AdapterSupport.id(block),
            block.size,
            block.range,
            block.drillTime,
            block.optionalBoostIntensity,
            support.mandatory(),
            support.optional(),
            AdapterSupport.cost(block, context),
            block.rotate,
            MiningProduction.baselineMultiplier(block, false)
        );
    }
}
