package autodrillnext.capability.adapter;

import mindustry.world.Block;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.BurstDrill;
import mindustry.world.blocks.production.Drill;

/** Main-thread extraction of the standard engine's steady-state optional-efficiency behavior. */
public final class MiningProduction {
    private MiningProduction() { }

    public static float fullBoostMultiplier(Block block) {
        if (block instanceof Drill drill) {
            return block instanceof BurstDrill ? drill.liquidBoostIntensity
                : drill.liquidBoostIntensity * drill.liquidBoostIntensity;
        }
        if (block instanceof BeamDrill beam) return beam.optionalBoostIntensity;
        throw new IllegalArgumentException("not a standard mining block: " + block.name);
    }

    public static float baselineMultiplier(Block block, boolean cheating) {
        // updateConsumption starts optionalEfficiency at 1, then takes the minimum of optional consumers.
        // With no optional consumers, full boost is intrinsic even when mandatory power is required.
        return cheating || !block.hasConsumers || block.optionalConsumers.length == 0
            ? fullBoostMultiplier(block) : 1f;
    }
}
