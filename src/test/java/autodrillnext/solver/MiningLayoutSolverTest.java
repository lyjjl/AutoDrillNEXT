package autodrillnext.solver;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.ContentId;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.ProductionEstimate;
import autodrillnext.model.PlannerRequest;
import autodrillnext.world.ExitPort;
import autodrillnext.world.PlacementFootprint;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;
import autodrillnext.world.OrePatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningLayoutSolverTest {
    @Test
    void seedsAreDeterministicAndNeverContainOverlappingFootprints() {
        DrillCandidate first = candidate("first", 0, 0, 3f);
        DrillCandidate overlap = candidate("overlap", 0, 0, 100f);
        DrillCandidate second = candidate("second", 2, 0, 2f);
        PlannerRequest request = PlannerRequest.defaults(
            new TileKey(0, 0),
            "sharded",
            ExitPort.forPreference(
                ExitPort.Side.RIGHT,
                ExitPort.Bias.CENTER,
                OrePatch.of(ContentId.of("mindustry:copper"), new TileKey(0, 0), Set.of(new TileOffset(0, 0)))
            )
        );

        List<MiningLayout> firstRun = new MiningLayoutSolver().seeds(List.of(first, overlap, second), request);
        List<MiningLayout> secondRun = new MiningLayoutSolver().seeds(List.of(first, overlap, second), request);

        assertEquals(firstRun, secondRun);
        assertFalse(firstRun.isEmpty());
        for (MiningLayout layout : firstRun) {
            assertTrue(layout.hasNoFootprintOverlap());
        }
    }

    @Test
    void productionSeedPrefersDownstreamPotentialBeforeCoveredCellCount() {
        DrillCandidate productive = candidate("productive", 0, 0, 20f);
        DrillCandidate cheap = candidate("cheap", 0, 0, 1f);
        PlannerRequest request = PlannerRequest.defaults(
            new TileKey(0, 0),
            "sharded",
            ExitPort.forPreference(
                ExitPort.Side.RIGHT,
                ExitPort.Bias.CENTER,
                OrePatch.of(ContentId.of("mindustry:copper"), new TileKey(0, 0), Set.of(new TileOffset(0, 0)))
            )
        );

        MiningLayout layout = new MiningLayoutSolver().seeds(List.of(cheap, productive), request).get(0);

        assertEquals(List.of(productive), layout.candidates());
        assertEquals(20f, layout.productionPerSecond());
    }

    @Test
    void compactSeedsRetainTranslatedPinwheelsAndFreeCardinalOutputs() {
        List<DrillCandidate> original = squareCandidates(0, 0, 2, 13);
        List<DrillCandidate> translated = squareCandidates(-23, 17, 2, 13);
        MiningLayoutSolver solver = new MiningLayoutSolver();
        List<MiningLayout> seeds = solver.seeds(original, request());
        List<MiningLayout> moved = solver.seeds(translated, request());

        assertTrue(seeds.stream().anyMatch(layout -> layout.candidates().size() >= 32),
            "the 13x13 patch admits four eight-drill pinwheels");
        assertEquals(seeds.stream().mapToInt(layout -> layout.candidates().size()).sorted().boxed().toList(),
            moved.stream().mapToInt(layout -> layout.candidates().size()).sorted().boxed().toList());
        for (MiningLayout layout : seeds) assertFreeOutput(layout);
    }

    @Test
    void sizeAdaptiveLanesKeepDenseModDrillsReachableOnIrregularTerrain() {
        List<DrillCandidate> candidates = squareCandidates(0, 0, 3, 12).stream()
            .filter(candidate -> !(candidate.anchor().x() == 4 && candidate.anchor().y() == 4)).toList();
        List<MiningLayout> seeds = new MiningLayoutSolver().seeds(candidates, request());

        assertTrue(seeds.stream().anyMatch(layout -> layout.candidates().size() >= 12));
        assertTrue(seeds.stream().map(layout -> Set.copyOf(layout.candidates())).distinct().count() > 1);
        for (MiningLayout layout : seeds) {
            assertTrue(candidates.containsAll(layout.candidates()));
            assertTrue(layout.hasNoFootprintOverlap());
            assertFreeOutput(layout);
        }
    }

    @Test
    void sharedWalkwaysSupportTwoDrillBandsWithoutLosingDenseCoverage() {
        MiningLayoutSolver solver = new MiningLayoutSolver();
        for (int size : List.of(1, 2)) {
            int extent = size * 5;
            List<MiningLayout> seeds = solver.seeds(squareCandidates(0, 0, size, extent), request());
            assertTrue(seeds.stream().anyMatch(layout ->
                layout.productionPerSecond() >= extent * extent * 0.8f && hasInteriorWalkway(layout, extent)),
                "two drill bands must share one free walkway rather than reserving a gap per band");
            for (MiningLayout layout : seeds) assertFreeOutput(layout);
        }
    }

    private boolean hasInteriorWalkway(MiningLayout layout, int extent) {
        Set<TileKey> occupied = new java.util.HashSet<>();
        layout.candidates().forEach(candidate -> occupied.addAll(candidate.footprint().tiles()));
        for (int lane = 1; lane < extent - 1; lane++) {
            boolean freeColumn = true, freeRow = true;
            for (int coordinate = 0; coordinate < extent; coordinate++) {
                freeColumn &= !occupied.contains(new TileKey(lane, coordinate));
                freeRow &= !occupied.contains(new TileKey(coordinate, lane));
            }
            if (freeColumn || freeRow) return true;
        }
        return false;
    }

    @Test
    void refinementReplacesOneObstructionWithSeveralCoverageCandidates() {
        DrillCandidate obstruction = new DrillCandidate("large", ContentId.of("mod:large"), new TileKey(0, 0), 0,
            PlacementFootprint.of(Set.of(new TileKey(0, 0), new TileKey(1, 0))),
            Set.of(new TileKey(0, 0)), new ProductionEstimate(ContentId.of("mindustry:copper"), 3f),
            List.of(), CostVector.empty(), Set.of());
        DrillCandidate a = candidate("a", 0, 0, 2f);
        DrillCandidate b = candidate("b", 1, 0, 2f);

        MiningLayout improved = new MiningLayoutSolver().improve(MiningLayout.of(List.of(obstruction)),
            List.of(obstruction, a, b), request(), MiningLayout::productionPerSecond);

        assertEquals(Set.of(a, b), Set.copyOf(improved.candidates()));
        assertEquals(4f, improved.productionPerSecond());
    }

    @Test
    void refinementCanRemoveMultipleNonOverlappingRoutingObstructions() {
        DrillCandidate producer = candidate("producer", 0, 0, 10f);
        DrillCandidate obstructionA = candidate("obstruction-a", 2, 0, 1f);
        DrillCandidate obstructionB = candidate("obstruction-b", 4, 0, 1f);
        List<DrillCandidate> all = List.of(producer, obstructionA, obstructionB);
        MiningLayout improved = new MiningLayoutSolver().improve(MiningLayout.of(all), all, request(),
            layout -> layout.candidates().equals(List.of(producer)) ? 10d : 0d);

        assertEquals(List.of(producer), improved.candidates());
    }

    @Test
    void exhaustedSharedBudgetDoesNotRestartExpensiveRefinement() {
        DrillCandidate producer = candidate("producer", 0, 0, 3f);
        MiningLayout initial = MiningLayout.of(List.of(producer));
        MiningLayout result = new MiningLayoutSolver().improve(initial, List.of(producer), request(),
            layout -> { throw new AssertionError("refinement restarted an exhausted route budget"); },
            System.nanoTime() - 1);
        assertEquals(initial, result);
    }

    private PlannerRequest request() {
        return PlannerRequest.defaults(new TileKey(0, 0), "sharded", ExitPort.forPreference(
            ExitPort.Side.RIGHT, ExitPort.Bias.CENTER,
            OrePatch.of(ContentId.of("mindustry:copper"), new TileKey(0, 0), Set.of(new TileOffset(0, 0)))));
    }

    private List<DrillCandidate> squareCandidates(int offsetX, int offsetY, int size, int extent) {
        java.util.ArrayList<DrillCandidate> result = new java.util.ArrayList<>();
        for (int x = 0; x <= extent - size; x++) {
            for (int y = 0; y <= extent - size; y++) {
                Set<TileKey> tiles = new java.util.HashSet<>();
                for (int dx = 0; dx < size; dx++) for (int dy = 0; dy < size; dy++)
                    tiles.add(new TileKey(offsetX + x + dx, offsetY + y + dy));
                result.add(new DrillCandidate(x + ":" + y, ContentId.of("arbitrary:excavator-" + size),
                    new TileKey(offsetX + x, offsetY + y), 0, PlacementFootprint.of(tiles), tiles,
                    new ProductionEstimate(ContentId.of("mindustry:copper"), tiles.size()),
                    List.of(), CostVector.empty(), Set.of()));
            }
        }
        return result;
    }

    private void assertFreeOutput(MiningLayout layout) {
        Set<TileKey> occupied = new java.util.HashSet<>();
        layout.candidates().forEach(candidate -> occupied.addAll(candidate.footprint().tiles()));
        for (DrillCandidate candidate : layout.candidates()) {
            assertTrue(candidate.footprint().tiles().stream().anyMatch(tile ->
                !occupied.contains(new TileKey(tile.x() + 1, tile.y()))
                    || !occupied.contains(new TileKey(tile.x() - 1, tile.y()))
                    || !occupied.contains(new TileKey(tile.x(), tile.y() + 1))
                    || !occupied.contains(new TileKey(tile.x(), tile.y() - 1))));
        }
    }

    private DrillCandidate candidate(String id, int x, int y, float production) {
        return new DrillCandidate(
            id,
            ContentId.of("mod:drill"),
            new TileKey(x, y),
            0,
            PlacementFootprint.of(Set.of(new TileKey(x, y))),
            Set.of(new TileKey(x, y)),
            new ProductionEstimate(ContentId.of("mindustry:copper"), production),
            List.of(),
            CostVector.empty(),
            Set.of()
        );
    }
}
