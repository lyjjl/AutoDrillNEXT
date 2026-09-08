package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.DrillSpec;
import mindustry.world.Block;
import mindustry.world.blocks.production.Drill;


public class DrillAdapter implements CapabilityAdapter<Drill, DrillSpec> {
    @Override
    public boolean supports(Block block) {
        return block instanceof Drill;
    }

    @Override
    public DrillSpec describe(Drill block, CapabilityContext context) {
        float productionBoost = MiningProduction.fullBoostMultiplier(block);
        SupportRequirements support = AdapterSupport.supports(block, context, productionBoost - 1f);
        MiningMechanisms.requireStandardBuild(block);
        return new DrillSpec(
            AdapterSupport.id(block),
            block.size,
            0,
            block.drillTime,
            block.liquidBoostIntensity,
            support.mandatory(),
            support.optional(),
            AdapterSupport.cost(block, context),
            block.rotate,
            MiningProduction.baselineMultiplier(block, false)
        );
    }
}
