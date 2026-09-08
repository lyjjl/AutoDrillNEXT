package autodrillnext;

import arc.Core;
import arc.Settings;
import arc.mock.MockApplication;
import arc.mock.MockFiles;
import arc.mock.MockGraphics;
import arc.util.I18NBundle;
import arc.util.Time;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.model.ContentId;
import autodrillnext.model.ExistingNetwork;
import autodrillnext.model.PlannerRequest;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.ExitPort;
import autodrillnext.world.TileKey;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.core.World;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.graphics.CacheLayer;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.OreBlock;
import mindustry.world.blocks.production.Drill;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forked because Mindustry's content registry, entities and clock are process-global. */
class BuiltMiningRuntimeTest {
    @Test
    void compiledMiningPlansKeepDeliveringInTheRealGameEngine() throws Exception {
        String classpath = System.getProperty("autodrillnext.test.classpath", System.getProperty("java.class.path"));
        Path log = java.nio.file.Files.createTempFile("autodrillnext-engine-", ".log");
        try {
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx768m", "-cp", classpath, EngineScenario.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean exited = process.waitFor(120, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            String output = java.nio.file.Files.readString(log);
            System.out.print(output);
            assertTrue(exited, "real-engine scenarios exceeded 120 seconds\n" + output);
            assertEquals(0, process.exitValue(), output);
        } finally {
            java.nio.file.Files.deleteIfExists(log);
        }
    }


    public static final class EngineScenario {
        private static final Set<String> TRANSPORTS = Set.of(
            "conveyor", "titanium-conveyor", "bridge-conveyor", "duct", "duct-bridge");
        // User's 7-column, 12-row screenshot, read from top to bottom. C = coal.
        private static final String[] DIAGONAL_COAL = {
            ".......",
            ".....C.",
            "....C..",
            "....C..",
            "...CC..",
            "...CC..",
            "..CCC..",
            "..CCC..",
            "..CC...",
            "..C....",
            ".CC....",
            "......."
        };

        public static void main(String[] args) throws Exception {
            Core.app = new MockApplication();
            Core.files = new MockFiles();
            Core.graphics = new MockGraphics();
            Core.settings = new Settings();
            Core.bundle = I18NBundle.createEmptyBundle();
            Vars.headless = true;
            Vars.net = new mindustry.net.Net(null);
            Vars.content = new ContentLoader();
            Vars.state = new GameState();
            Vars.state.rules = new Rules();
            Vars.state.rules.infiniteResources = true;
            Groups.init();
            Vars.indexer = new mindustry.ai.BlockIndexer();
            Vars.world = new World();
            CacheLayer.init();
            Vars.content.createBaseContent();
            mindustry.type.Item modOre = new mindustry.type.Item("verification-mod-ore");
            modOre.hardness = 2;
            OreBlock oreBlock = new OreBlock("verification-mod-deposit", modOre);
            Drill modDrill = new Drill("verification-mod-drill");
            modDrill.size = 3;
            modDrill.tier = 3;
            modDrill.drillTime = 180;
            modDrill.requirements(mindustry.type.Category.production, mindustry.type.ItemStack.empty);
            modDrill.alwaysUnlocked = true;
            modDrill.consumePower(0.1f);
            Vars.content.init();
            Time.setDeltaProvider(() -> 1f);
            for (ExitPort.Side side : ExitPort.Side.values()) {
                run("irregular-" + side, (Drill) Blocks.pneumaticDrill, Blocks.oreCoal, side, true);
            }
            run("mod-3x3", modDrill, oreBlock, ExitPort.Side.RIGHT, false);
            for (ExitPort.Side side : ExitPort.Side.values()) {
                diagonalCoalScenario("diagonal-coal-" + side, side, new TileKey(14, 16));
            }
            diagonalCoalScenario("diagonal-coal-tip-selection", ExitPort.Side.TOP, new TileKey(17, 22));
            run("diagonal-coal-unmixed", (Drill) Blocks.pneumaticDrill, Blocks.oreCoal, ExitPort.Side.RIGHT,
                EngineScenario::diagonalCoalTerrain, new TileKey(14, 16), 18, 18, false, TRANSPORTS);
            var mixed = run("mixed-ground", (Drill) Blocks.pneumaticDrill, Blocks.oreCoal, ExitPort.Side.RIGHT, () -> {
                for (int x = 12; x < 20; x++) {
                    for (int y = 12; y < 20; y++) Vars.world.tile(x, y).setOverlay(Blocks.oreCoal);
                }
            }, new TileKey(16, 16), 64, 36, true,
                Set.of(Blocks.conveyor.name, Blocks.titaniumConveyor.name),
                PlannerRequest.SearchStrategy.HEURISTIC);
            var used = mixed.graph().placements().stream().map(p -> p.spec().id().value())
                .collect(java.util.stream.Collectors.toSet());
            if (!used.equals(Set.of(Blocks.conveyor.name, Blocks.titaniumConveyor.name))) {
                throw new AssertionError("low-flow branches must use cheaper native conveyors: " + used);
            }
        }

        private static void diagonalCoalScenario(String name, ExitPort.Side side, TileKey seed) {
            var result = run(name, (Drill) Blocks.pneumaticDrill, Blocks.oreCoal, side,
                EngineScenario::diagonalCoalTerrain, seed, 18, 18, true, TRANSPORTS);
            if (result.graph().placements().stream().anyMatch(p -> !p.spec().id().value().equals(Blocks.conveyor.name))) {
                throw new AssertionError(name + " upgraded transport although ordinary conveyors can serve all 18 coal cells");
            }
        }

        private static void run(String name, Drill drill, Block deposit, ExitPort.Side side, boolean obstacle) {
            run(name, drill, deposit, side, () -> {
                for (int x = 12; x < 20; x++) {
                    for (int y = 12; y < 20; y++) {
                        if (!obstacle || x + y >= 26 && x + y <= 36) Vars.world.tile(x, y).setOverlay(deposit);
                    }
                }
                if (obstacle) {
                    for (int y = 11; y <= 20; y++) Vars.world.tile(21, y).setBlock(Blocks.stoneWall);
                    Vars.world.tile(21, 15).setBlock(Blocks.air);
                }
            }, new TileKey(16, 16), obstacle ? 58 : 64, 0, true, TRANSPORTS);
        }

        private static void diagonalCoalTerrain() {
            for (int row = 0; row < DIAGONAL_COAL.length; row++) {
                for (int column = 0; column < DIAGONAL_COAL[row].length(); column++) {
                    if (DIAGONAL_COAL[row].charAt(column) == 'C') {
                        Vars.world.tile(12 + column, 23 - row).setOverlay(Blocks.oreCoal);
                    }
                }
            }
        }

        private static autodrillnext.model.PlannerResult run(
            String name, Drill drill, Block deposit, ExitPort.Side side,
            Runnable terrain, TileKey seed, int expectedPatchCells, int minimumCoverage,
            boolean allowMixedTransport, Set<String> transportIds
        ) {
            return run(
                name, drill, deposit, side, terrain, seed, expectedPatchCells, minimumCoverage,
                allowMixedTransport, transportIds, PlannerRequest.SearchStrategy.EXHAUSTIVE);
        }

        private static autodrillnext.model.PlannerResult run(
            String name, Drill drill, Block deposit, ExitPort.Side side,
            Runnable terrain, TileKey seed, int expectedPatchCells, int minimumCoverage,
            boolean allowMixedTransport, Set<String> transportIds,
            PlannerRequest.SearchStrategy searchStrategy
        ) {
            Groups.clear();
            Vars.state = new GameState();
            Vars.state.rules = new Rules();
            Vars.state.rules.infiniteResources = true;
            Vars.world = new World();
            Vars.world.setGenerating(true);
            Vars.world.resize(40, 40);
            Vars.world.tiles.fill();
            for (Tile tile : Vars.world.tiles) tile.setFloor((Floor) Blocks.stone);
            terrain.run();
            Vars.world.setGenerating(false);
            arc.Events.fire(new mindustry.game.EventType.WorldLoadEvent());
            if (drill.consPower != null) {
                Vars.world.tile(16, 25).setBlock(Blocks.powerSource, Team.sharded, 0);
                Vars.world.tile(16, 23).setBlock(Blocks.powerNodeLarge, Team.sharded, 0);
                for (Tile tile : Vars.world.tiles) if (tile.build != null && tile.build.tile == tile) tile.build.updateProximity();
                Vars.world.build(16, 25).placed();
                Vars.world.build(16, 23).placed();
                tick(10);
            }
            TileKey outlet = switch (side) {
                case TOP -> new TileKey(16, 23);
                case BOTTOM -> new TileKey(16, 8);
                case LEFT -> new TileKey(8, 16);
                case RIGHT -> new TileKey(24, 16);
            };
            ExitPort exit = new ExitPort("engine-" + side, side, ExitPort.Bias.CENTER,
                List.of(new ExitAnchor(outlet, side, ExitPort.Bias.CENTER, 0)));
            PlannerRequest request = new PlannerRequest(seed, Team.sharded.name, exit,
                PlannerRequest.Profile.THROUGHPUT, searchStrategy, 0f, 1.10f,
                PlannerRequest.BudgetMode.INFINITE_RESOURCES,
                false, 1600, 60, allowMixedTransport);
            LocalMiningPlanner planner = new LocalMiningPlanner();
            long started = System.nanoTime();
            var captured = planner.capture(request);
            var descriptors = new LinkedHashMap<ContentId, autodrillnext.capability.spec.CapabilityDescriptor>();
            captured.capabilities().descriptors().forEach((id, descriptor) -> {
                if (id.value().equals(drill.name) || transportIds.contains(id.value())) descriptors.put(id, descriptor);
            });
            var snapshot = new LocalMiningPlanner.PlanningSnapshot(
                captured.world(),
                captured.inventory(),
                new CapabilitySnapshot(descriptors),
                captured.existingNetwork()
            );
            var result = planner.plan(request, snapshot, drill.name, "");
            if (!result.compileReady()) throw new AssertionError(name + " rejected: " + result.diagnostics());
            if (result.patch().absoluteCells().size() != expectedPatchCells) {
                throw new AssertionError(name + " incorrectly connected ore patches: "
                    + result.patch().absoluteCells().size() + " != " + expectedPatchCells);
            }
            long covered = result.layout().candidates().stream()
                .flatMap(candidate -> candidate.coveredOreCells().stream()).distinct().count();
            if (covered < minimumCoverage) {
                throw new AssertionError(name + " lost ore coverage: " + covered + " < " + minimumCoverage);
            }
            outlet = result.exit().anchor().tile();
            Vars.player = mindustry.gen.Player.create();
            Vars.player.team(Team.sharded);
            Vars.player.unit(mindustry.content.UnitTypes.alpha.create(Team.sharded));
            if (name.equals("irregular-TOP")) {
                var queuedResult = result;
                var last = queuedResult.compileRecords().get(queuedResult.compileRecords().size() - 1);
                Vars.player.unit().addBuild(new mindustry.entities.units.BuildPlan(last.tile().x(), last.tile().y(),
                    last.rotation(), Vars.content.block(last.blockId())));
                expectRejected(() -> new autodrillnext.mindustryapi.BuildPlanFacade().submit(
                    queuedResult.compileRecords()), "conflicting queued placement");
                Vars.player.unit().plans().clear();
                Tile changed = Vars.world.tile(request.seed().x(), request.seed().y());
                changed.setOverlay(Blocks.oreCopper);
                var staleResult = result;
                expectRejected(() -> submit(planner, request, staleResult), "changed dominant ore");
                changed.setOverlay(deposit);
                snapshot = filteredSnapshot(planner.capture(request), descriptors.keySet());
                result = planner.plan(request, snapshot, drill.name, "");
                if (!result.compileReady()) {
                    throw new AssertionError(name + " replan after restored ore rejected: " + result.diagnostics());
                }
            }
            if (drill.consPower != null) {
                Vars.world.tile(16, 25).setBlock(Blocks.air);
                var staleResult = result;
                expectRejected(() -> submit(planner, request, staleResult), "disconnected power source");
                Vars.world.tile(16, 25).setBlock(Blocks.powerSource, Team.sharded, 0);
                Vars.world.build(16, 25).placed();
                tick(10);
                snapshot = filteredSnapshot(planner.capture(request), descriptors.keySet());
                result = planner.plan(request, snapshot, drill.name, "");
                if (!result.compileReady()) {
                    throw new AssertionError(name + " replan after restored power rejected: " + result.diagnostics());
                }
            }
            submit(planner, request, result);
            if (Vars.player.unit().plans().size != result.compileRecords().size()) {
                throw new AssertionError(name + " submitted queue dropped physical placements");
            }
            Vars.player.unit().plans().clear();
            for (var record : result.compileRecords()) {
                Block block = Vars.content.block(record.blockId());
                if (!mindustry.world.Build.validPlace(block, Team.sharded, record.tile().x(), record.tile().y(), record.rotation())) {
                    throw new AssertionError(name + " unplaceable record " + record);
                }
                Vars.world.tile(record.tile().x(), record.tile().y()).setBlock(block, Team.sharded, record.rotation());
                var built = Vars.world.build(record.tile().x(), record.tile().y());
                if (record.config() instanceof autodrillnext.world.TileOffset offset) {
                    built.configured(null, new arc.math.geom.Point2(offset.x(), offset.y()));
                }
                built.placed();
            }
            int dx = side == ExitPort.Side.RIGHT ? 1 : side == ExitPort.Side.LEFT ? -1 : 0;
            int dy = side == ExitPort.Side.TOP ? 1 : side == ExitPort.Side.BOTTOM ? -1 : 0;
            // Drain the receiver every tick so its finite storage cannot throttle the measured exit.
            int receiverX = outlet.x() + (dx > 0 ? 1 : dx < 0 ? -2 : 0);
            int receiverY = outlet.y() + (dy > 0 ? 1 : dy < 0 ? -2 : 0);
            Vars.world.tile(receiverX, receiverY).setBlock(Blocks.container, Team.sharded, 0);
            for (Tile tile : Vars.world.tiles) if (tile.build != null && tile.build.tile == tile) tile.build.updateProximity();
            var receiver = Vars.world.build(receiverX, receiverY);
            mindustry.type.Item item = ((OreBlock) deposit).itemDrop;
            Vars.state.rules.infiniteResources = false;
            tick(1800);
            receiver.items.clear();
            int first = receive(3600, receiver, item);
            java.util.Map<TileKey, Float> active = new LinkedHashMap<>();
            for (var candidate : result.layout().candidates()) {
                var built = (Drill.DrillBuild) Vars.world.build(candidate.anchor().x(), candidate.anchor().y());
                active.put(candidate.anchor(), built.timeDrilled);
            }
            int second = receive(3600, receiver, item);
            if (first <= 0 || second <= 0) throw new AssertionError(name + " stopped delivering: " + first + "," + second);
            double potential = result.layout().productionPerSecond();
            // Warm steady runs must deliver most modeled mining output, not merely one lucky item.
            double expected = Math.min(potential, result.budget().selected().qout());
            if (Math.min(first, second) / 60.0 < expected * 0.90) {
                throw new AssertionError(name + " real rate " + first / 60.0 + "," + second / 60.0 + " < modeled " + expected);
            }
            for (var candidate : result.layout().candidates()) {
                var built = (Drill.DrillBuild) Vars.world.build(candidate.anchor().x(), candidate.anchor().y());
                if (built.dominantItem != item || built.timeDrilled - active.get(candidate.anchor()) < 60f) {
                    throw new AssertionError(name + " drill cannot mine/dump at " + candidate.anchor()
                        + "; active=" + (built.timeDrilled - active.get(candidate.anchor()))
                        + "; power=" + (built.power == null ? "none" : built.power.status)
                        + "; efficiency=" + built.efficiency + "; items=" + built.items
                        + "; target=" + candidate.production() + "; dominant=" + built.dominantItem
                        + "; actual/min=" + first + "," + second + "; potential=" + potential
                        + "; sourceFlow=" + result.graph().flow() + "; placements=" + result.graph().placements());
                }
            }
            System.out.println(name + " drills=" + result.layout().candidates().size() + " covered="
                + result.certificate().lowerBound().coveredOreCells() + " measured=" + first + "," + second
                + " expected/s=" + expected + " transports=" + result.graph().placements().stream()
                    .map(placement -> placement.spec().id().value()).distinct().toList()
                + " elapsed=" + (System.nanoTime() - started) / 1e9);
            return result;
        }

        private static void submit(
            LocalMiningPlanner planner,
            PlannerRequest request,
            autodrillnext.model.PlannerResult result
        ) {
            var fresh = filteredSnapshot(
                planner.capture(request),
                result.certificate().scope().capabilities().descriptors().keySet()
            );
            planner.validateSubmission(request, fresh, result, "");
            new autodrillnext.mindustryapi.BuildPlanFacade().submit(result.compileRecords());
        }

        private static LocalMiningPlanner.PlanningSnapshot filteredSnapshot(
            LocalMiningPlanner.PlanningSnapshot source,
            java.util.Set<ContentId> ids
        ) {
            var descriptors = new LinkedHashMap<ContentId, autodrillnext.capability.spec.CapabilityDescriptor>();
            source.capabilities().descriptors().forEach((id, descriptor) -> {
                if (ids.contains(id)) descriptors.put(id, descriptor);
            });
            return new LocalMiningPlanner.PlanningSnapshot(
                source.world(),
                source.inventory(),
                new CapabilitySnapshot(descriptors),
                source.existingNetwork()
            );
        }

        private static void expectRejected(Runnable submission, String scenario) {
            int before = Vars.player.unit().plans().size;
            boolean rejected = false;
            try {
                submission.run();
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            if (!rejected || Vars.player.unit().plans().size != before) {
                throw new AssertionError(scenario + " did not preserve the build queue");
            }
        }

        private static int receive(int ticks, mindustry.gen.Building receiver, mindustry.type.Item item) {
            int received = 0;
            for (int i = 0; i < ticks; i++) {
                tick(1);
                received += receiver.items.get(item);
                receiver.items.clear();
            }
            return received;
        }

        private static void tick(int count) {
            for (int tick = 0; tick < count; tick++) {
                Time.update();
                Groups.powerGraph.update();
                Groups.build.each(building -> building.update());
            }
        }
    }
}
