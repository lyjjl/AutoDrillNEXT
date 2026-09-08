package autodrillnext.compile;

import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.capability.spec.ItemTransportSpec.PortBehavior;
import autodrillnext.model.TransportPlacement;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.TileKey;

import java.util.Map;

/** Static port rules from Mindustry v159 Conveyor, Duct, ItemBridge and DirectionBridge. */
public final class TransportGeometry {
    private TransportGeometry() {}

    public static TileKey step(TileKey tile, int direction, int distance) {
        return new TileKey(tile.x() + (direction == 0 ? distance : direction == 2 ? -distance : 0),
            tile.y() + (direction == 1 ? distance : direction == 3 ? -distance : 0));
    }

    public static int direction(TileKey from, TileKey to) {
        if (from.y() == to.y() && from.x() != to.x()) return from.x() < to.x() ? 0 : 2;
        if (from.x() == to.x() && from.y() != to.y()) return from.y() < to.y() ? 1 : 3;
        return -1;
    }

    public static int distance(TileKey a, TileKey b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y());
    }

    public static int exitDirection(ExitAnchor exit) {
        return switch (exit.side()) { case RIGHT -> 0; case TOP -> 1; case LEFT -> 2; case BOTTOM -> 3; };
    }

    public static boolean acceptsAdjacent(TransportPlacement receiver, TileKey source, ItemTransportSpec sourceSpec) {
        if (distance(source, receiver.tile()) != 1) return false;
        int side = direction(receiver.tile(), source);
        PortBehavior behavior = receiver.spec().portBehavior();
        if (behavior == PortBehavior.ITEM_BRIDGE) {
            return receiver.bridgeLink() != null && side != direction(receiver.tile(), receiver.bridgeLink());
        }
        if (behavior == PortBehavior.DUCT_BRIDGE) {
            return receiver.bridgeLink() != null && side != receiver.rotation();
        }
        if (side == receiver.rotation()) return false;
        if (behavior == PortBehavior.ARMORED_DUCT && side != (receiver.rotation() + 2) % 4) {
            return sourceSpec != null && (sourceSpec.portBehavior() == PortBehavior.DUCT
                || sourceSpec.portBehavior() == PortBehavior.ARMORED_DUCT
                || sourceSpec.portBehavior() == PortBehavior.DUCT_BRIDGE);
        }
        if (behavior == PortBehavior.ARMORED_CONVEYOR && side != (receiver.rotation() + 2) % 4) {
            return sourceSpec != null && (sourceSpec.portBehavior() == PortBehavior.CONVEYOR
                || sourceSpec.portBehavior() == PortBehavior.ARMORED_CONVEYOR);
        }
        return true;
    }

    public static boolean acceptsAdjacent(TransportPlacement receiver, TileKey source, ItemTransportSpec sourceSpec,
                                           Map<TileKey, TransportPlacement> placements) {
        if (!acceptsAdjacent(receiver, source, sourceSpec)) return false;
        if (receiver.spec().portBehavior() == PortBehavior.DUCT_BRIDGE) {
            int incomingDirection = direction(source, receiver.tile());
            for (TransportPlacement incoming : placements.values()) {
                if (receiver.tile().equals(incoming.bridgeLink()) && incoming.rotation() == incomingDirection) return false;
            }
        }
        return true;
    }

    public static boolean linkValid(TransportPlacement from, TransportPlacement to) {
        return from.spec().bridge() && from.spec().id().equals(to.spec().id())
            && to.tile().equals(from.bridgeLink()) && direction(from.tile(), to.tile()) >= 0
            && distance(from.tile(), to.tile()) <= from.spec().range() && !from.tile().equals(to.bridgeLink())
            && (from.spec().portBehavior() != PortBehavior.DUCT_BRIDGE
                || from.rotation() == direction(from.tile(), to.tile()));
    }

    public static boolean pairConnects(TransportPlacement from, TransportPlacement to) {
        if (!from.output().equals(to.tile())) return false;
        if (from.bridgeLink() != null) {
            if (!linkValid(from, to)) return false;
            if (from.spec().portBehavior() == PortBehavior.DUCT_BRIDGE) return true;
            // An unlinked ItemBridge cannot dump back toward an incoming bridge.
            return to.bridgeLink() != null || direction(to.tile(), to.output()) != direction(to.tile(), from.tile());
        }
        return acceptsAdjacent(to, from.tile(), from.spec())
            && (from.spec().portBehavior() == PortBehavior.ITEM_BRIDGE
                || step(from.tile(), from.rotation(), 1).equals(to.tile()));
    }

    public static TileKey automaticLink(TransportPlacement from, Map<TileKey, TransportPlacement> placements) {
        for (int distance = 1; distance <= from.spec().range(); distance++) {
            TileKey tile = step(from.tile(), from.rotation(), distance);
            TransportPlacement other = placements.get(tile);
            if (other != null && from.spec().id().equals(other.spec().id())) return tile;
        }
        return null;
    }

    public static boolean conflictsWithExistingDirectionalBridge(ItemTransportSpec spec, TileKey anchor,
                                                                   autodrillnext.world.TerrainSnapshot terrain) {
        if (spec.portBehavior() != PortBehavior.DUCT_BRIDGE) return false;
        // Snapshots do not retain existing rotations; reserve all four rays to avoid intercepting either direction.
        for (int direction = 0; direction < 4; direction++) {
            for (int distance = 1; distance <= spec.range(); distance++) {
                var tile = terrain.tile(step(anchor, direction, distance));
                if (tile != null && spec.id().value().equals(tile.existingBlock())) return true;
            }
        }
        return false;
    }

    public static float admissionCapacity(TransportPlacement receiver,
                                           Map<TileKey, TransportPlacement> placements,
                                           autodrillnext.model.MiningLayout layout) {
        PortBehavior behavior = receiver.spec().portBehavior();
        float nominal = receiver.spec().capacityPerSecond();
        if (behavior != PortBehavior.CONVEYOR && behavior != PortBehavior.ARMORED_CONVEYOR) return nominal;
        // v159 Conveyor.acceptItem requires minitem >= 0.4 at the rear, but > 0.7 at either side.
        // A lone side input inserts at y=0.5, so an ordinary bend is not intrinsically slower.
        // Reserve the larger gap only for competing suppliers. This is not a fairness guarantee.
        int suppliers = 0;
        boolean sideInput = false;
        for (TransportPlacement source : placements.values()) {
            if (acceptsAdjacent(receiver, source.tile(), source.spec(), placements)
                && outputsAdjacent(source, receiver.tile(), placements)) {
                suppliers++;
                sideInput |= isSide(receiver, source.tile());
            }
        }
        for (var drill : layout.candidates()) {
            boolean adjacent = false;
            for (TileKey tile : drill.footprint().tiles()) {
                if (acceptsAdjacent(receiver, tile, null, placements)) {
                    adjacent = true;
                    sideInput |= isSide(receiver, tile);
                }
            }
            if (adjacent) suppliers++;
        }
        return sideInput && suppliers > 1 ? nominal * (0.4f / 0.7f) : nominal;
    }

    private static boolean isSide(TransportPlacement receiver, TileKey source) {
        if (distance(receiver.tile(), source) != 1) return false;
        return (direction(receiver.tile(), source) & 1) != (receiver.rotation() & 1);
    }

    private static boolean outputsAdjacent(TransportPlacement source, TileKey target,
                                             Map<TileKey, TransportPlacement> placements) {
        if (source.bridgeLink() != null) return false;
        if (source.spec().portBehavior() != PortBehavior.ITEM_BRIDGE) return source.output().equals(target);
        // Unlinked ItemBridge dumps to any adjacent receiver except back along an incoming bridge ray.
        int outputDirection = direction(source.tile(), target);
        for (TransportPlacement incoming : placements.values()) {
            if (source.tile().equals(incoming.bridgeLink()) && direction(source.tile(), incoming.tile()) == outputDirection) return false;
        }
        return true;
    }

    public static boolean networkConsistent(Map<TileKey, TransportPlacement> placements, ExitAnchor exit) {
        for (TransportPlacement placement : placements.values()) {
            if (placement.spec().portBehavior() == PortBehavior.DUCT_BRIDGE
                && !java.util.Objects.equals(placement.bridgeLink(), automaticLink(placement, placements))) return false;
            if (isExitTerminal(placement, exit)) continue;
            TransportPlacement next = placements.get(placement.output());
            if (next == null || !pairConnects(placement, next)) return false;
            if (placement.bridgeLink() == null && !acceptsAdjacent(next, placement.tile(), placement.spec(), placements)) return false;
        }
        Map<TileKey, Boolean> reachesExit = new java.util.HashMap<>();
        for (TileKey start : placements.keySet()) {
            if (reachesExit.containsKey(start)) continue;
            java.util.ArrayList<TileKey> path = new java.util.ArrayList<>();
            java.util.HashSet<TileKey> currentPath = new java.util.HashSet<>();
            TileKey current = start;
            while (!reachesExit.containsKey(current)) {
                if (!currentPath.add(current)) return false;
                path.add(current);
                TransportPlacement placement = placements.get(current);
                if (placement == null) return false;
                if (isExitTerminal(placement, exit)) break;
                current = placement.output();
            }
            for (TileKey tile : path) reachesExit.put(tile, Boolean.TRUE);
        }
        return true;
    }

    private static boolean isExitTerminal(TransportPlacement placement, ExitAnchor exit) {
        return placement.tile().equals(exit.tile()) && placement.bridgeLink() == null
            && placement.output().equals(step(exit.tile(), exitDirection(exit), 1));
    }
}
