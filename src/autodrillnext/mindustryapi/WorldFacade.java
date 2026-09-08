package autodrillnext.mindustryapi;

import arc.Core;
import arc.struct.Seq;
import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.adapter.MiningProduction;
import autodrillnext.capability.spec.DrillSpec;
import autodrillnext.model.ContentId;
import autodrillnext.world.MiningResult;
import autodrillnext.world.PlacementFootprint;
import autodrillnext.world.PlacementRules;
import autodrillnext.world.PowerAccess;
import autodrillnext.world.TerrainRevision;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileState;
import autodrillnext.world.WorldSnapshot;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.DuctBridge;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.modules.PowerModule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WorldFacade {
    public WorldSnapshot snapshot(Team team, TileKey seed, int maxTiles) {
        requireMainThread();
        return snapshot(team, seed, maxTiles, new GameFacade().snapshot(team, Vars.state.rules));
    }

    public WorldSnapshot snapshot(Team team, TileKey seed, int maxTiles, CapabilitySnapshot capabilities) {
        requireMainThread();
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(capabilities, "capabilities");
        if (maxTiles <= 0) throw new IllegalArgumentException("max tiles must be positive");
        if (Vars.world == null) throw new IllegalStateException("world is not loaded");

        int radius = Math.max(0, (int) ((Math.sqrt(maxTiles) - 1f) / 2f));
        int minX = Math.max(0, seed.x() - radius);
        int maxX = Math.min(Vars.world.width() - 1, seed.x() + radius);
        int minY = Math.max(0, seed.y() - radius);
        int maxY = Math.min(Vars.world.height() - 1, seed.y() + radius);
        LinkedHashMap<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile != null) tiles.put(new TileKey(x, y), state(tile, team));
            }
        }
        PlacementRules rules = captureRules(team, tiles, capabilities);
        long revision = ((long) Vars.world.tileChanges << 32) ^ (Vars.world.floorChanges & 0xffffffffL);
        return WorldSnapshot.captured(tiles, TerrainRevision.of(revision), rules);
    }

    static void requireMainThread() {
        if (Core.app == null || !Core.app.isOnMainThread()) {
            throw new IllegalStateException("runtime world capture requires the game main thread");
        }
    }

    private PlacementRules captureRules(Team team, Map<TileKey, TileState> tiles, CapabilitySnapshot capabilities) {
        Map<ContentId, Map<TileKey, Integer>> placements = new LinkedHashMap<>();
        Map<PlacementRules.Placement, MiningResult> mining = new LinkedHashMap<>();
        Map<PlacementRules.Placement, List<PowerAccess>> power = new LinkedHashMap<>();
        Map<ContentId, String> diagnostics = new LinkedHashMap<>();
        Map<Integer, Float> graphSupply = new LinkedHashMap<>();
        Seq<Building> connections = new Seq<>();
        for (CapabilityDescriptor descriptor : capabilities.descriptors().values()) {
            if (!descriptor.states().contains(CapabilityState.AVAILABLE_NOW) || descriptor.spec() == null) continue;
            TileKey attemptedAnchor = null;
            int attemptedRotation = 0;
            try {
                Block block = Vars.content.block(descriptor.id().value());
                if (block == null) throw new IllegalStateException("missing captured block");
                if (!block.unlockedNow() || !block.isPlaceable() || block.isBanned()) continue;
                Building probe = null;
                boolean miningBlock = descriptor.spec() instanceof DrillSpec;
                if (miningBlock || block.hasPower) {
                    if (block.buildType == null) throw new IllegalStateException("uninitialized captured block");
                    probe = block.newBuilding();
                    probe.block = block;
                    probe.team = team;
                    if (block.hasPower) probe.power = new PowerModule();
                }
                Map<TileKey, Integer> anchors = new LinkedHashMap<>();
                placements.put(descriptor.id(), anchors);
                for (TileKey anchor : tiles.keySet()) {
                    PlacementFootprint footprint = PlacementFootprint.square(anchor, block.size);
                    if (!tiles.keySet().containsAll(footprint.tiles())) continue;
                    if (block instanceof DuctBridge bridge && wouldChangeExistingBridgeLink(bridge, team, anchor)) continue;
                    for (int rotation = 0; rotation < 4; rotation++) {
                        attemptedAnchor = anchor;
                        attemptedRotation = rotation;
                        if (!Build.validPlace(block, team, anchor.x(), anchor.y(), rotation)) continue;
                        anchors.merge(anchor, 1 << rotation, (a, b) -> a | b);
                        if (probe == null) continue;
                        probe.tile = Vars.world.tile(anchor.x(), anchor.y());
                        probe.rotation = rotation;
                        probe.x = probe.tile.worldx() + block.offset;
                        probe.y = probe.tile.worldy() + block.offset;
                        PlacementRules.Placement placement = new PlacementRules.Placement(descriptor.id(), anchor, rotation);
                        if (miningBlock) {
                            MiningResult result = captureMining(block, probe, footprint, tiles.keySet());
                            if (result != null) mining.put(placement, result);
                        }
                        if (block.hasPower) {
                            List<PowerAccess> access = capturePower(probe, connections, graphSupply);
                            if (!access.isEmpty()) power.put(placement, access);
                        }
                    }
                }
            } catch (RuntimeException failure) {
                // A mod-specific capture failure invalidates that descriptor, not unrelated usable content.
                placements.remove(descriptor.id());
                mining.keySet().removeIf(placement -> placement.block().equals(descriptor.id()));
                power.keySet().removeIf(placement -> placement.block().equals(descriptor.id()));
                String context = descriptor.id().value() + (attemptedAnchor == null ? ""
                    : "@" + attemptedAnchor.x() + "," + attemptedAnchor.y() + "/r" + attemptedRotation);
                diagnostics.put(descriptor.id(), context + ": " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        }
        return new PlacementRules(placements, mining, power, diagnostics);
    }

    private boolean wouldChangeExistingBridgeLink(DuctBridge bridge, Team team, TileKey anchor) {
        // DirectionBridge.findLink ignores intervening terrain and the receiver's rotation.
        // Query the live world, not only the cropped snapshot, to protect existing external links too.
        for (int direction = 0; direction < 4; direction++) {
            int dx = arc.math.geom.Geometry.d4x(direction);
            int dy = arc.math.geom.Geometry.d4y(direction);
            for (int distance = 1; distance <= bridge.range; distance++) {
                Building existing = Vars.world.build(anchor.x() + dx * distance, anchor.y() + dy * distance);
                if (existing != null && existing.block == bridge && existing.team == team) return true;
            }
        }
        return false;
    }

    private List<PowerAccess> capturePower(Building probe, Seq<Building> connections, Map<Integer, Float> graphSupply) {
        probe.proximity.clear();
        for (var edge : probe.block.getEdges()) {
            Building neighbor = Vars.world.build(probe.tileX() + edge.x, probe.tileY() + edge.y);
            if (neighbor != null && !probe.proximity.contains(neighbor)) probe.proximity.add(neighbor);
        }
        Map<TileKey, PowerAccess> access = new LinkedHashMap<>();
        for (Building neighbor : probe.getPowerConnections(connections)) {
            addPowerAccess(neighbor, false, Integer.MAX_VALUE, graphSupply, access);
        }
        PowerNode.getNodeLinks(probe.tile, probe.block, probe.team, node -> {
            if (node.block instanceof PowerNode powerNode && node.power != null) {
                int free = Math.max(0, powerNode.maxNodes - node.power.links.size);
                if (free > 0) addPowerAccess(node, true, free, graphSupply, access);
            }
        });
        ArrayList<PowerAccess> ordered = new ArrayList<>(access.values());
        ordered.sort(Comparator.comparingInt(PowerAccess::graphId).thenComparing(PowerAccess::connector));
        return List.copyOf(ordered);
    }

    private void addPowerAccess(Building connector, boolean requiresLink, int freeConnections,
                                Map<Integer, Float> graphSupply, Map<TileKey, PowerAccess> access) {
        if (connector.power == null || connector.power.graph == null) return;
        PowerGraph graph = connector.power.graph;
        float available = graphSupply.computeIfAbsent(graph.getID(), ignored -> availablePower(graph));
        TileKey key = new TileKey(connector.tileX(), connector.tileY());
        PowerAccess previous = access.get(key);
        if (previous == null || !requiresLink) {
            access.put(key, new PowerAccess(graph.getID(), key, available, freeConnections, requiresLink));
        }
    }

    private float availablePower(PowerGraph graph) {
        // Equivalent to getPowerProduced/getPowerNeeded divided by Time.delta, but remains valid while paused.
        // No battery discharge or temporary graph transfers are counted as continuing generation.
        float produced = 0f;
        float needed = 0f;
        for (Building producer : graph.producers) {
            produced += producer.getPowerProduction() * producer.timeScale();
        }
        for (Building consumer : graph.consumers) {
            if (consumer.shouldConsumePower && consumer.block.consPower != null) {
                needed += consumer.block.consPower.requestedPower(consumer) * consumer.timeScale();
            }
        }
        return Math.max(0f, produced - needed) * 60f;
    }

    private MiningResult captureMining(Block block, Building probe, PlacementFootprint footprint, Set<TileKey> boundedTiles) {
        // These calls run the game's dominant-ore selection / per-lane first-solid ray traversal.
        // The detached probe is never initialized, added to Groups, or installed into the world.
        probe.onProximityUpdate();
        float baselineMultiplier = MiningProduction.baselineMultiplier(block, probe.cheating());
        if (baselineMultiplier <= 0f) return null;
        if (block instanceof Drill drill && probe instanceof Drill.DrillBuild build) {
            Item item = build.dominantItem;
            if (item == null || build.dominantItems <= 0) return null;
            Set<TileKey> covered = new LinkedHashSet<>();
            for (TileKey key : footprint.tiles()) {
                Tile tile = Vars.world.tile(key.x(), key.y());
                if (drill.canMine(tile) && drill.getDrop(tile) == item) covered.add(key);
            }
            if (covered.size() != build.dominantItems) {
                throw new IllegalStateException("unsupported mining coverage override: " + block.name);
            }
            return new MiningResult(Map.of(ContentId.of(item.name),
                new MiningResult.Output(covered, 60f * build.dominantItems / drill.getDrillTime(item) * baselineMultiplier)));
        }
        if (block instanceof BeamDrill drill && probe instanceof BeamDrill.BeamDrillBuild build) {
            Map<ContentId, Set<TileKey>> covered = new LinkedHashMap<>();
            for (Tile tile : build.facing) {
                if (tile == null || tile.wallDrop() == null) continue;
                TileKey key = new TileKey(tile.x, tile.y);
                // A ray outside the captured terrain cannot be reasoned about by the worker.
                if (!boundedTiles.contains(key)) return null;
                covered.computeIfAbsent(ContentId.of(tile.wallDrop().name), ignored -> new LinkedHashSet<>()).add(key);
            }
            if (covered.isEmpty()) return null;
            float perRay = 60f / drill.getDrillTime(build.lastItem) * baselineMultiplier;
            Map<ContentId, MiningResult.Output> outputs = new LinkedHashMap<>();
            covered.forEach((item, keys) -> outputs.put(item, new MiningResult.Output(keys, perRay * keys.size())));
            return new MiningResult(outputs);
        }
        throw new IllegalStateException("unsupported mining build: " + block.name);
    }

    private TileState state(Tile tile, Team team) {
        var floor = tile.floor();
        var floorDrop = tile.drop();
        var wallDrop = tile.wallDrop();
        var block = tile.block();
        boolean occupied = block != null && block != Blocks.air;
        String blockId = occupied ? block.name : null;
        String teamId = occupied && tile.team() != null ? tile.team().name : null;
        boolean fogged = Vars.state.rules.fog && Vars.state.rules.staticFog && Vars.fogControl != null
            && !Vars.fogControl.isDiscovered(team, tile.x, tile.y);
        boolean deepLiquid = floor != null && floor.isLiquid && floor.isDeep();
        return new TileState(
            fogged, tile.solid(), deepLiquid, blockId, teamId,
            floorDrop == null ? null : ContentId.of(floorDrop.name),
            wallDrop == null ? null : ContentId.of(wallDrop.name)
        );
    }
}
