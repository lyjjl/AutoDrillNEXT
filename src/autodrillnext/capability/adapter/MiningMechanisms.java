package autodrillnext.capability.adapter;

import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.BurstDrill;
import mindustry.world.blocks.production.Drill;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeCoolant;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquidFilter;
import mindustry.world.consumers.ConsumePower;

import java.lang.reflect.Method;
import java.util.Set;

/** Name/data subclasses and captured placement overrides are supported; custom mining mechanisms are not assumed. */
final class MiningMechanisms {
    private static final Set<String> MINING_METHODS = Set.of(
        "update", "updateTile", "onProximityUpdate", "updateFacing", "updateLasers", "shouldConsume",
        "updateConsumption", "updateEfficiencyMultiplier", "efficiencyScale", "getProgressIncrease",
        "productionValid", "cheating", "timer", "timeScale", "canConsume", "consumeTriggerValid",
        "consume", "offload", "dump", "canDump", "acceptItem", "handleItem", "produced", "incrementDump",
        "acceptLiquid", "handleLiquid", "getMaximumAccepted", "edelta", "delta",
        "create", "init", "created", "placed", "onProximityAdded", "onProximityRemoved"
    );
    private static final Set<String> BLOCK_MINING_METHODS = Set.of("countOre", "getDrop", "canMine", "getDrillTime");

    private MiningMechanisms() { }

    static void requireStandardBuild(Block block) {
        if (block.consumers != null) {
            for (Consume consume : block.consumers) {
                Class<?> type = consume.getClass();
                if (type != ConsumePower.class && type != ConsumeLiquid.class
                    && type != ConsumeLiquidFilter.class && type != ConsumeCoolant.class) {
                    throw new IllegalArgumentException("unsupported mining consumer: " + type.getName());
                }
            }
        }
        for (Class<?> type = block.getClass(); type != null && type != Block.class; type = type.getSuperclass()) {
            if (type == Drill.class || type == BurstDrill.class || type == BeamDrill.class) continue;
            for (Method method : type.getDeclaredMethods()) {
                if (BLOCK_MINING_METHODS.contains(method.getName())) {
                    throw new IllegalArgumentException("unsupported mining override: " + type.getName() + "." + method.getName());
                }
            }
        }
        // Uninitialized pure capability fixtures have no factory. Runtime capture requires it.
        if (block.buildType == null) return;
        Building build = block.newBuilding();
        boolean floor = block instanceof Drill && build instanceof Drill.DrillBuild;
        boolean wall = block instanceof BeamDrill && build instanceof BeamDrill.BeamDrillBuild;
        if (!floor && !wall) throw new IllegalArgumentException("unsupported mining building: " + build.getClass().getName());
        for (Class<?> type = build.getClass(); type != null && type != Building.class; type = type.getSuperclass()) {
            if (type == Drill.DrillBuild.class || type == BurstDrill.BurstDrillBuild.class
                || type == BeamDrill.BeamDrillBuild.class) continue;
            for (Method method : type.getDeclaredMethods()) {
                if (MINING_METHODS.contains(method.getName())) {
                    throw new IllegalArgumentException("unsupported mining override: " + type.getName() + "." + method.getName());
                }
            }
        }
    }
}
