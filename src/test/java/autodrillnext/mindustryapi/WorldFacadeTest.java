package autodrillnext.mindustryapi;

import arc.Core;
import arc.Settings;
import autodrillnext.model.ContentId;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.TileKey;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.core.World;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import mindustry.world.blocks.environment.AirBlock;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.StaticWall;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.Drill;
import mindustry.world.meta.BuildVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldFacadeTest {
    private Floor ground;
    private Item soft;
    private Item hard;

    @BeforeEach
    void initializeWorld() {
        Thread captureThread = Thread.currentThread();
        Core.app = new arc.mock.MockApplication() {
            @Override
            public Thread getMainThread() {
                return captureThread;
            }
        };
        Core.files = new arc.mock.MockFiles();
        Core.bundle = arc.util.I18NBundle.createEmptyBundle();
        Core.settings = new Settings();
        Vars.content = new ContentLoader();
        Vars.state = new GameState();
        Vars.state.rules.borderDarkness = false;
        Vars.state.rules.polygonCoreProtection = false;
        mindustry.graphics.CacheLayer.init();
        Vars.headless = true;
        Vars.fogControl = null;
        Groups.init();
        Blocks.air = new AirBlock("air");
        ground = new Floor("test-ground");
        soft = new Item("test-soft");
        soft.hardness = 1;
        hard = new Item("test-hard");
        hard.hardness = 5;
        Vars.world = new World();
        Vars.world.tiles = new Tiles(12, 12);
        for (int x = 0; x < 12; x++) {
            for (int y = 0; y < 12; y++) put(x, y, ground);
        }
    }

    @Test
    void runtimeCaptureRejectsWorkerThreadAccess() {
        java.util.concurrent.CompletableFuture.runAsync(() ->
            assertThrows(IllegalStateException.class, () ->
                new WorldFacade().snapshot(Team.sharded, new TileKey(5, 5), 25))
        ).join();
    }

    @Test
    void capturePreservesSignedWorldRevisionCounterBits() {
        Vars.world.tileChanges = -1;
        Vars.world.floorChanges = -1;
        var fresh = capture().revision();
        assertEquals(-1L, fresh.value());
        Vars.world.tileChanges = Integer.MAX_VALUE;
        var distinct = capture().revision();
        assertNotEquals(fresh, distinct);
    }

    @Test
    void hardOreAboveTierCannotGenerateRuntimeMining() {
        Drill drill = drill("mod-low-tier", 1);
        put(5, 5, ore("hard-floor", hard));
        TerrainSnapshot terrain = capture();
        assertFalse(terrain.canPlace(ContentId.of(drill.name), new TileKey(5, 5), 0));
        assertNull(terrain.mining(ContentId.of(drill.name), new TileKey(5, 5), 0));
    }

    @Test
    void fourByFourCoverageUsesActualAnchorWithoutMutatingWorld() {
        Drill drill = drill("mod-four-wide", 4);
        Floor ore = ore("soft-floor", soft);
        for (int x = 4; x <= 7; x++) for (int y = 4; y <= 7; y++) put(x, y, ore);
        int entities = Groups.all.size();
        int builds = Groups.build.size();
        int revision = Vars.world.tileChanges;
        TerrainSnapshot terrain = capture();
        var result = terrain.mining(ContentId.of(drill.name), new TileKey(5, 5), 0);
        assertNotNull(result, terrain.runtimeDiagnostics().toString());
        assertEquals(16, result.outputs().get(ContentId.of(soft.name)).coveredTiles().size());
        assertEquals(16 * 60f / drill.getDrillTime(soft) * drill.liquidBoostIntensity * drill.liquidBoostIntensity,
            result.outputs().get(ContentId.of(soft.name)).itemsPerSecond(), 0.0001f);
        assertEquals(entities, Groups.all.size());
        assertEquals(builds, Groups.build.size());
        assertEquals(revision, Vars.world.tileChanges);
        assertNull(Vars.world.tile(5, 5).build);
    }

    @Test
    void failingCustomPlacementQuarantinesOnlyThatDescriptor() {
        Drill good = drill("working-mod-drill", 1);
        Drill broken = new Drill("broken-mod-drill") {
            @Override
            public boolean canPlaceOn(Tile tile, Team team, int rotation) {
                throw new IllegalArgumentException("custom placement failure");
            }
        };
        broken.alwaysUnlocked = true;
        broken.buildVisibility = BuildVisibility.shown;
        broken.buildType = () -> broken.new DrillBuild();
        broken.init();
        put(5, 5, ore("soft-floor", soft));
        GameFacade.RuntimeSnapshot snapshot = new GameFacade().snapshot(new WorldFacade(), "sharded", new TileKey(6, 6), 625);
        TerrainSnapshot terrain = TerrainSnapshot.of(snapshot.world());
        assertTrue(terrain.canPlace(ContentId.of(good.name), new TileKey(5, 5), 0));
        assertFalse(terrain.canPlace(ContentId.of(broken.name), new TileKey(5, 5), 0));
        var failed = snapshot.capabilities().require(ContentId.of(broken.name));
        assertFalse(failed.selectable());
        assertTrue(failed.reasons().stream().anyMatch(reason -> reason.contains("custom placement failure")));
    }

    @Test
    void modNamedStandardSubclassUsesActualDominanceAndOreTiming() {
        Drill drill = drill("another-mod-standard-drill", 2);
        drill.tier = 6;
        drill.drillMultipliers.put(hard, 2f);
        Floor softFloor = ore("soft-floor", soft);
        Floor hardFloor = ore("hard-floor", hard);
        put(5, 5, softFloor);
        put(6, 5, hardFloor);
        put(5, 6, hardFloor);
        put(6, 6, hardFloor);
        TerrainSnapshot terrain = capture();
        var mined = terrain.mining(ContentId.of(drill.name), new TileKey(5, 5), 0);
        assertNotNull(mined);
        assertEquals(java.util.Set.of(ContentId.of(hard.name)), mined.outputs().keySet());
        assertEquals(3, mined.outputs().get(ContentId.of(hard.name)).coveredTiles().size());
        assertEquals(180f / drill.getDrillTime(hard) * drill.liquidBoostIntensity * drill.liquidBoostIntensity,
            mined.outputs().get(ContentId.of(hard.name)).itemsPerSecond(), 0.0001f);
        assertNotEquals(drill.getDrillTime(soft), drill.getDrillTime(hard));
        // Captured timing and mineability cannot change under the worker after capture.
        drill.tier = 0;
        drill.drillTime *= 10f;
        assertTrue(terrain.canPlace(ContentId.of(drill.name), new TileKey(5, 5), 0));
        assertEquals(mined, terrain.mining(ContentId.of(drill.name), new TileKey(5, 5), 0));
    }

    @Test
    void placementCapturesBlockSpecificLiquidRulesAndWholeFootprint() {
        Drill ordinary = drill("ordinary-mod-drill", 2);
        Drill floating = drill("floating-mod-drill", 2);
        floating.floating = true;
        floating.placeableLiquid = true;
        Floor waterOre = ore("deep-ore", soft);
        waterOre.isLiquid = true;
        waterOre.drownTime = 10f;
        for (int x = 5; x <= 6; x++) for (int y = 5; y <= 6; y++) put(x, y, waterOre);
        TerrainSnapshot terrain = capture();
        TileKey anchor = new TileKey(5, 5);
        assertFalse(terrain.canPlace(ContentId.of(ordinary.name), anchor, 0));
        assertTrue(terrain.canPlace(ContentId.of(floating.name), anchor, 0));
        assertFalse(terrain.canPlace(ContentId.of("uncaptured-block"), anchor, 0));
        assertFalse(terrain.canPlace(ContentId.of(floating.name), new TileKey(11, 11), 0));
    }

    @Test
    void wallDrillCapturesEveryLaneFirstSolidAndMixedOreCycleTiming() {
        BeamDrill beam = new BeamDrill("mod-ray-drill");
        beam.size = 2;
        beam.range = 4;
        beam.tier = 6;
        beam.drillTime = 60f;
        beam.optionalBoostIntensity = 2f;
        beam.drillMultipliers.put(hard, 2f);
        beam.alwaysUnlocked = true;
        beam.buildVisibility = BuildVisibility.shown;
        beam.buildType = () -> beam.new BeamDrillBuild();
        beam.init();
        wall(8, 5, "soft-wall", soft);
        wall(9, 6, "hard-wall", hard);
        TerrainSnapshot terrain = capture();
        var mixed = terrain.mining(ContentId.of(beam.name), new TileKey(5, 5), 0);
        assertNotNull(mixed);
        assertEquals(2, mixed.outputs().size());
        assertEquals(2f, mixed.outputs().get(ContentId.of(soft.name)).itemsPerSecond());
        assertEquals(2f, mixed.outputs().get(ContentId.of(hard.name)).itemsPerSecond());

        wall(7, 5, "barren-blocker", null);
        var blocked = capture().mining(ContentId.of(beam.name), new TileKey(5, 5), 0);
        assertNotNull(blocked);
        assertEquals(java.util.Set.of(ContentId.of(hard.name)), blocked.outputs().keySet());
        assertEquals(java.util.Set.of(new TileKey(9, 6)), blocked.outputs().get(ContentId.of(hard.name)).coveredTiles());
        assertEquals(4f, blocked.outputs().get(ContentId.of(hard.name)).itemsPerSecond());
    }

    @Test
    void bannedContentCannotAcquireRuntimePlacementRules() {
        Drill banned = drill("banned-mod-drill", 1);
        Vars.state.rules.bannedBlocks.add(banned);
        put(5, 5, ore("soft-floor", soft));
        TerrainSnapshot terrain = capture();
        assertFalse(terrain.canPlace(ContentId.of(banned.name), new TileKey(5, 5), 0));
    }

    @Test
    void shallowWaterRequirementAppliesToEveryFootprintTile() {
        mindustry.content.Liquids.water = new mindustry.type.Liquid("test-water");
        Drill waterDrill = drill("mod-water-drill", 2);
        waterDrill.requiresWater = true;
        Floor water = ore("shallow-water-ore", soft);
        water.isLiquid = true;
        water.liquidDrop = mindustry.content.Liquids.water;
        put(5, 5, water);
        put(6, 5, water);
        put(5, 6, water);
        put(6, 6, water);
        TileKey anchor = new TileKey(5, 5);
        assertTrue(capture().canPlace(ContentId.of(waterDrill.name), anchor, 0));
        put(6, 6, ground);
        assertFalse(capture().canPlace(ContentId.of(waterDrill.name), anchor, 0));
    }

    @Test
    void existingSameTeamDirectionalBridgeOutsideSnapshotBlocksEveryNewRotation() {
        var bridge = new mindustry.world.blocks.distribution.DuctBridge("mod-directional-bridge");
        bridge.range = 4;
        bridge.alwaysUnlocked = true;
        bridge.buildVisibility = BuildVisibility.shown;
        bridge.buildType = () -> bridge.new DuctBridgeBuild();
        bridge.init();
        Vars.world.setGenerating(true);
        Vars.world.tile(8, 5).setBlock(bridge, Team.sharded, 1);
        Vars.world.setGenerating(false);
        TerrainSnapshot terrain = TerrainSnapshot.of(new WorldFacade().snapshot(Team.sharded, new TileKey(5, 5), 9));
        assertFalse(terrain.contains(new TileKey(8, 5)));
        for (int rotation = 0; rotation < 4; rotation++) {
            assertFalse(terrain.canPlace(ContentId.of(bridge.name), new TileKey(5, 5), rotation));
        }
        assertTrue(terrain.canPlace(ContentId.of(bridge.name), new TileKey(5, 6), 0),
            terrain.runtimeDiagnostics().toString());
    }

    @Test
    void powerOnlyModDrillBaselineMatchesActualEngineProductionWindow() {
        Drill drill = new Drill("mod-power-only");
        drill.size = 3;
        drill.tier = 2;
        drill.drillTime = 280f;
        drill.hardnessDrillMultiplier = 0f;
        drill.liquidBoostIntensity = 1.6f;
        drill.alwaysUnlocked = true;
        drill.buildVisibility = BuildVisibility.shown;
        drill.consumePower(0.1f);
        drill.buildType = () -> drill.new DrillBuild();
        drill.init();
        Floor ore = ore("power-only-ore", soft);
        for (int x = 4; x <= 6; x++) for (int y = 4; y <= 6; y++) put(x, y, ore);
        var output = capture().mining(ContentId.of(drill.name), new TileKey(5, 5), 0)
            .outputs().get(ContentId.of(soft.name));
        var running = (Drill.DrillBuild) drill.newBuilding().create(drill, Team.sharded);
        running.tile = Vars.world.tile(5, 5);
        running.onProximityUpdate();
        running.power.status = 1f;
        float previousTime = arc.util.Time.time;
        float previousDelta = arc.util.Time.delta;
        int produced = 0;
        try {
            arc.util.Time.delta = 1f;
            for (int tick = 0; tick < 4200; tick++) {
                arc.util.Time.time += 1f;
                running.update();
                if (tick >= 600) produced += running.items.get(soft);
                running.items.clear();
            }
        } finally {
            arc.util.Time.time = previousTime;
            arc.util.Time.delta = previousDelta;
        }
        assertEquals(1f, running.optionalEfficiency);
        assertEquals(9f * 60f / 280f * 1.6f * 1.6f, output.itemsPerSecond(), 0.0001f);
        assertEquals(output.itemsPerSecond(), produced / 60f, 0.02f);
    }

    private void wall(int x, int y, String name, Item item) {
        StaticWall wall = new StaticWall(name);
        wall.itemDrop = item;
        Vars.world.tiles.set(x, y, new Tile(x, y, ground, Blocks.air, wall));
    }

    private Drill drill(String name, int size) {
        Drill drill = new Drill(name) { };
        drill.size = size;
        drill.tier = 2;
        drill.alwaysUnlocked = true;
        drill.buildVisibility = BuildVisibility.shown;
        drill.buildType = () -> drill.new DrillBuild();
        drill.init();
        return drill;
    }

    private Floor ore(String name, Item item) {
        Floor floor = new Floor(name);
        floor.itemDrop = item;
        return floor;
    }

    private void put(int x, int y, Floor floor) {
        Vars.world.tiles.set(x, y, new Tile(x, y, floor, Blocks.air, Blocks.air));
    }

    private TerrainSnapshot capture() {
        GameFacade game = new GameFacade();
        return TerrainSnapshot.of(new WorldFacade().snapshot(Team.sharded, new TileKey(6, 6), 625,
            game.snapshot(Team.sharded, Vars.state.rules)));
    }
}
