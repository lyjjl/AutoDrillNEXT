package autodrillnext.capability.adapter;

import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.ArmoredConveyor;
import mindustry.world.blocks.distribution.BufferedItemBridge;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.DirectionBridge;
import mindustry.world.blocks.distribution.Duct;
import mindustry.world.blocks.distribution.DuctBridge;
import mindustry.world.blocks.distribution.ItemBridge;

import java.lang.reflect.Method;
import java.util.Set;

/** Captures stock transport mechanics, not display statistics or unmodeled mod hooks. */
final class TransportMechanisms {
    private static final Set<Class<?>> BLOCK_TYPES = Set.of(Conveyor.class, ArmoredConveyor.class,
        Duct.class, DirectionBridge.class, DuctBridge.class, ItemBridge.class, BufferedItemBridge.class);
    private static final Set<Class<?>> BUILD_TYPES = Set.of(Conveyor.ConveyorBuild.class,
        ArmoredConveyor.ArmoredConveyorBuild.class, Duct.DuctBuild.class,
        DirectionBridge.DirectionBridgeBuild.class, DuctBridge.DuctBridgeBuild.class,
        ItemBridge.ItemBridgeBuild.class, BufferedItemBridge.BufferedItemBridgeBuild.class);
    private static final Set<String> BLOCK_HOOKS = Set.of(
        "newBuilding", "linkValid", "positionsValid", "outputsItems", "rotatedOutput", "pointConfig"
    );
    private static final Set<String> BUILD_HOOKS = Set.of(
        "init", "created", "update", "updateTile", "onProximityUpdate", "onProximityAdded",
        "onProximityRemoved", "updateConsumption", "updateEfficiencyMultiplier", "efficiencyScale",
        "shouldConsume", "consume", "edelta", "delta", "getProgressIncrease", "acceptItem", "handleItem",
        "acceptStack", "handleStack", "removeStack", "getMaximumAccepted", "moveForward", "pass", "offload",
        "dump", "dumpAccumulate", "canDump", "doDump", "checkDump", "checkAccept", "linked", "checkIncoming",
        "findLink", "updateTransport", "front", "nearby", "relativeTo", "relativeToEdge",
        "configured", "configure", "configureAny", "config", "playerPlaced", "read"
    );

    private TransportMechanisms() { }

    static void requireStandardBuild(Block block) {
        if (!block.update || !block.hasItems || block.itemCapacity <= 0) {
            throw new IllegalArgumentException("unsupported inactive item transport");
        }
        boolean directional = block instanceof Conveyor || block instanceof Duct || block instanceof DuctBridge;
        if (directional && !block.rotate) {
            throw new IllegalArgumentException("unsupported nonrotating directional transport");
        }
        if ((block instanceof Duct || block instanceof DuctBridge) && !block.isDuct) {
            throw new IllegalArgumentException("unsupported duct port identity");
        }
        requireInheritedHooks(block.getClass(), Block.class, BLOCK_TYPES, BLOCK_HOOKS);
        // Pure capability fixtures may not have an initialized factory; runtime content does.
        if (block.buildType == null) return;
        Building build = block.newBuilding();
        Class<?> expected;
        if (block instanceof ArmoredConveyor) expected = ArmoredConveyor.ArmoredConveyorBuild.class;
        else if (block instanceof Conveyor) expected = Conveyor.ConveyorBuild.class;
        else if (block instanceof Duct) expected = Duct.DuctBuild.class;
        else if (block instanceof DuctBridge) expected = DuctBridge.DuctBridgeBuild.class;
        else if (block instanceof BufferedItemBridge) expected = BufferedItemBridge.BufferedItemBridgeBuild.class;
        else expected = ItemBridge.ItemBridgeBuild.class;
        Class<?> actual = build == null ? null : build.getClass();
        while (actual != null && !BUILD_TYPES.contains(actual)) actual = actual.getSuperclass();
        if (actual != expected) {
            throw new IllegalArgumentException("unsupported transport building: "
                + (build == null ? "null" : build.getClass().getName()));
        }
        requireInheritedHooks(build.getClass(), Building.class, BUILD_TYPES, BUILD_HOOKS);
    }

    private static void requireInheritedHooks(Class<?> type, Class<?> base, Set<Class<?>> standard, Set<String> hooks) {
        for (; type != null && type != base; type = type.getSuperclass()) {
            if (standard.contains(type)) continue;
            for (Method method : type.getDeclaredMethods()) {
                if (hooks.contains(method.getName())) {
                    throw new IllegalArgumentException("unsupported transport override: "
                        + type.getName() + "." + method.getName());
                }
            }
        }
    }

    static float conveyorCapacity(Conveyor block) {
        positive(block.speed, "conveyor speed");
        // ConveyorBuild spaces items by 0.4 tiles. Include one update for insertion/transfer ordering.
        return capacity(60d / (Math.ceil(0.4d / block.speed) + 1d));
    }

    static float ductCapacity(Duct block) {
        positive(block.speed, "duct speed");
        // DuctBuild holds one item and resets its travel progress for every accepted item.
        return capacity(60d / Math.max(1d, Math.ceil(block.speed)));
    }

    static float ductBridgeCapacity(DuctBridge block) {
        positive(block.speed, "duct bridge speed");
        requireRange(block.range);
        // Linked endpoints accumulate speed ticks; an unlinked endpoint moves at most one item per update.
        return capacity(Math.min(60d, 60d / block.speed));
    }

    static float itemBridgeCapacity(ItemBridge block) {
        requireRange(block.range);
        if (block instanceof BufferedItemBridge buffered) {
            positive(buffered.speed, "buffered bridge transit time");
            if (buffered.bufferCapacity <= 0) {
                throw new IllegalArgumentException("unsupported empty bridge buffer");
            }
            // poll(speed) plus a four-tick accept timer and one update to refill a freed buffer slot.
            return capacity(Math.min(15d, 60d * buffered.bufferCapacity / (Math.ceil(buffered.speed) + 5d)));
        }
        positive(block.transportTime, "item bridge transport time");
        // Link transfer may be faster, but the same spec also represents one-item/update dump endpoints.
        return capacity(Math.min(60d, 60d / block.transportTime));
    }

    private static void requireRange(int range) {
        if (range <= 0) throw new IllegalArgumentException("unsupported nonpositive bridge range");
    }

    private static void positive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0f) {
            throw new IllegalArgumentException("unsupported nonpositive or nonfinite " + name);
        }
    }

    private static float capacity(double value) {
        float result = (float) value;
        positive(result, "transport capacity");
        return result;
    }
}
