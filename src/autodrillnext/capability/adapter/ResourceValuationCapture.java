package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.ResourceValuation;
import autodrillnext.model.ItemId;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.StaticWall;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.Separator;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquids;
import mindustry.world.consumers.ConsumePower;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main-thread metadata capture only; never called by solver expansion or retained in its pricebook. */
public final class ResourceValuationCapture {
    private static final Set<String> PRODUCTION_METHODS = Set.of(
        "update", "updateTile", "craft", "consume", "offload", "getProgressIncrease",
        "updateConsumption", "efficiencyScale", "shouldConsume", "productionValid");
    private static final Class<?> DEFAULT_ITEM_MULTIPLIER =
        new ConsumeItems(ItemStack.empty).multiplier.getClass();

    private ResourceValuationCapture() { }

    public static ResourceValuation capture(Iterable<Item> items, Iterable<Block> blocks) {
        Set<ItemId> ids = new HashSet<>();
        for (Item item : items) ids.add(id(item));
        List<Block> miners = new ArrayList<>();
        for (Block block : blocks) {
            if (!(block instanceof Drill) && !(block instanceof BeamDrill)) continue;
            try {
                block.reinitializeConsumers();
                MiningMechanisms.requireStandardBuild(block);
                miners.add(block);
            } catch (RuntimeException unsupported) {
                // Custom mining mechanics cannot establish a supported minimum mining tier.
            }
        }
        Map<ItemId, Long> raw = new HashMap<>();
        List<ResourceValuation.Recipe> recipes = new ArrayList<>();
        for (Block block : blocks) {
            if (block.itemDrop != null && (block instanceof Floor || block instanceof StaticWall)) {
                Item item = block.itemDrop;
                boolean wall = block instanceof StaticWall || ((Floor) block).wallOre;
                int minimumTier = Integer.MAX_VALUE;
                for (Block miner : miners) {
                    if (!wall && miner instanceof Drill drill && drill.tier >= item.hardness
                        && drill.blockedItem != item && (drill.blockedItems == null || !drill.blockedItems.contains(item))) {
                        minimumTier = Math.min(minimumTier, drill.tier);
                    } else if (wall && miner instanceof BeamDrill drill && drill.tier >= item.hardness
                        && drill.blockedItem != item && (drill.blockedItems == null || !drill.blockedItems.contains(item))) {
                        minimumTier = Math.min(minimumTier, drill.tier);
                    }
                }
                // Proven deposits remain raw even if only an unsupported/custom miner can harvest them.
                long tier = minimumTier == Integer.MAX_VALUE ? 7L
                    : Math.min(7L, Math.max(1L, Math.max((long) item.hardness, minimumTier) + 1L));
                raw.merge(id(item), tier, Math::min);
            }
            ItemStack[] outputs;
            boolean separator;
            if (block instanceof GenericCrafter crafter) {
                outputs = crafter.outputItems != null ? crafter.outputItems
                    : crafter.outputItem == null ? null : new ItemStack[]{crafter.outputItem};
                separator = false;
            } else if (block instanceof Separator split) {
                outputs = split.results;
                separator = true;
            } else continue;
            if (outputs == null || outputs.length == 0) continue;
            try {
                if (!standardProducer(block)) continue;
                block.reinitializeConsumers();
                Map<ItemId, Integer> inputs = inputs(block);
                if (inputs == null) continue;
                Map<ItemId, Integer> amounts = new HashMap<>();
                for (ItemStack stack : outputs) {
                    if (stack == null || stack.item == null || stack.amount <= 0) continue;
                    amounts.merge(id(stack.item), stack.amount, Math::addExact);
                }
                long sum = 0L;
                for (int amount : amounts.values()) sum += amount;
                for (Map.Entry<ItemId, Integer> output : amounts.entrySet()) {
                    double yield = separator ? (double) output.getValue() / sum : output.getValue();
                    recipes.add(new ResourceValuation.Recipe(output.getKey(), yield, inputs));
                }
            } catch (RuntimeException unsupported) {
                // Broken/dynamic/custom recipes use the unknown weight instead of a guessed free recipe.
            }
        }
        return ResourceValuation.infer(ids, raw, recipes);
    }

    /** Fixed mandatory item inputs only. Power/liquids have no item price and add no premium.
     * Recipes without item inputs and dynamic/filter/custom consumers fall back to unknown.
     * Optional boosters do not change the unboosted recipe. */
    private static Map<ItemId, Integer> inputs(Block block) {
        Map<ItemId, Integer> inputs = new HashMap<>();
        for (Consume consume : block.consumers) {
            if (consume.optional) continue;
            if (consume.getClass() == ConsumeItems.class) {
                if (consume.multiplier == null || consume.multiplier.getClass() != DEFAULT_ITEM_MULTIPLIER) return null;
                for (ItemStack stack : ((ConsumeItems) consume).items) {
                    if (stack == null || stack.item == null || stack.amount <= 0) return null;
                    inputs.merge(id(stack.item), stack.amount, Math::addExact);
                }
            } else if (consume.getClass() != ConsumePower.class && consume.getClass() != ConsumeLiquid.class
                && consume.getClass() != ConsumeLiquids.class) return null;
        }
        return inputs;
    }

    private static boolean standardProducer(Block block) {
        // Inherited data-only mod subclasses are safe; custom production callbacks are not recipes.
        if (customProductionMethods(block.getClass(), Block.class)) return false;
        if (block.buildType == null) return true;
        Building build = block.newBuilding();
        if (!(build instanceof GenericCrafter.GenericCrafterBuild) && !(build instanceof Separator.SeparatorBuild)) return false;
        return !customProductionMethods(build.getClass(), Building.class);
    }

    private static boolean customProductionMethods(Class<?> type, Class<?> stop) {
        for (; type != null && type != stop; type = type.getSuperclass()) {
            if (type.getPackageName().equals("mindustry.world.blocks.production")) continue;
            for (Method method : type.getDeclaredMethods()) {
                if (PRODUCTION_METHODS.contains(method.getName())) return true;
            }
        }
        return false;
    }

    private static ItemId id(Item item) { return ItemId.of(item.name); }
}
