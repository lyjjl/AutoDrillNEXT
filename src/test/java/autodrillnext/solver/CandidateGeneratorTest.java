package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.DrillSpec;
import autodrillnext.model.ContentId;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.DrillCandidate;
import autodrillnext.world.MiningResult;
import autodrillnext.world.PlacementRules;
import autodrillnext.world.WorldSnapshot;
import autodrillnext.world.ExitPort;
import autodrillnext.world.OrePatch;
import autodrillnext.world.OrePatchAnalyzer;
import autodrillnext.world.TerrainRevision;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;
import autodrillnext.world.TileState;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateGeneratorTest {
    @Test
    void ordinaryDrillEnumeratesLegalOreAnchorsAndComputesProduction() {
        ContentId copper = ContentId.of("mindustry:copper");
        OrePatch patch = OrePatch.of(copper, new TileKey(2, 2), Set.of(
            new TileOffset(0, 0), new TileOffset(1, 0)
        ));
        TerrainSnapshot terrain = terrain(
            new TileKey(2, 2), new TileState(false, false, false, null, null, copper, null),
            new TileKey(3, 2), new TileState(false, false, false, null, null, copper, null)
        );
        DrillSpec spec = new DrillSpec(
            ContentId.of("mod:drill"), 1, 0, 60f, 0f, List.of(), List.of(), CostVector.empty()
        );
        CapabilitySnapshot capabilities = snapshot(spec);

        List<DrillCandidate> candidates = new CandidateGenerator().generate(
            patch,
            terrain,
            capabilities,
            PlannerRequest.defaults(new TileKey(2, 2), "sharded", ExitPort.forPreference(
                ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch
            ))
        );

        assertEquals(2, candidates.size());
        assertEquals(0, candidates.get(0).rotation());
        assertEquals(1f, candidates.get(0).production().perSecond());
    }
    @Test
    void candidateEnumerationIsIndependentOfTheSearchStateLimit() {
        ContentId copper = ContentId.of("mindustry:copper");
        OrePatch patch = OrePatch.of(copper, new TileKey(2, 2), Set.of(
            new TileOffset(0, 0), new TileOffset(1, 0)
        ));
        TerrainSnapshot terrain = terrain(
            new TileKey(2, 2), new TileState(false, false, false, null, null, copper, null),
            new TileKey(3, 2), new TileState(false, false, false, null, null, copper, null)
        );
        DrillSpec spec = new DrillSpec(
            ContentId.of("mod:drill"), 1, 0, 60f, 0f, List.of(), List.of(), CostVector.empty()
        );
        PlannerRequest request = new PlannerRequest(new TileKey(2, 2),
        "sharded",
        ExitPort.forPreference(ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch),
        PlannerRequest.Profile.THROUGHPUT,
        0f,
        1.10f,
        PlannerRequest.BudgetMode.INFINITE_RESOURCES,
        false,
        100,
        1, true);

        assertEquals(2, new CandidateGenerator().generate(patch, terrain, snapshot(spec), request).size());
    }


    @Test
    void rangedDrillEnumeratesFourDirectionsAndRejectsBlockedFullFootprints() {
        ContentId copper = ContentId.of("mindustry:copper");
        OrePatch patch = OrePatch.of(copper, new TileKey(4, 2), Set.of(new TileOffset(0, 0)));
        TerrainSnapshot terrain = terrain(
            new TileKey(3, 2), new TileState(false, false, false, null, null, null, null),
            new TileKey(4, 2), new TileState(false, true, false, null, null, null, copper),
            new TileKey(5, 2), new TileState(false, false, false, null, null, null, null),
            new TileKey(4, 1), new TileState(false, false, false, null, null, null, null),
            new TileKey(4, 3), new TileState(false, false, false, null, null, null, null),
            new TileKey(6, 2), new TileState(false, false, false, "core", "sharded", null, null)
        );
        DrillSpec spec = new DrillSpec(
            ContentId.of("mod:beam"), 1, 2, 30f, 0f, List.of(), List.of(), CostVector.empty()
        );

        List<DrillCandidate> candidates = new CandidateGenerator().generate(
            patch,
            terrain,
            snapshot(spec),
            PlannerRequest.defaults(new TileKey(4, 2), "sharded", ExitPort.forPreference(
                ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch
            ))
        );

        assertTrue(candidates.stream().anyMatch(candidate -> candidate.rotation() == 0));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.rotation() == 1));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.rotation() == 2));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.rotation() == 3));
        assertTrue(candidates.stream().allMatch(candidate -> !candidate.footprint().tiles().contains(new TileKey(6, 2))));
    }

    @Test
    void beamDrillIsExcludedForFloorOre() {
        ContentId copper = ContentId.of("mindustry:copper");
        OrePatch patch = OrePatch.of(copper, new TileKey(2, 2), Set.of(new TileOffset(0, 0)));
        TerrainSnapshot terrain = terrain(
            new TileKey(1, 2), TileState.empty(),
            new TileKey(2, 1), TileState.empty(),
            new TileKey(2, 2), new TileState(false, false, false, null, null, copper, null),
            new TileKey(2, 3), TileState.empty(),
            new TileKey(3, 2), TileState.empty()
        );
        DrillSpec beam = new DrillSpec(
            ContentId.of("mod:beam"), 1, 2, 30f, 0f, List.of(), List.of(), CostVector.empty()
        );

        List<DrillCandidate> candidates = new CandidateGenerator().generate(
            patch,
            terrain,
            snapshot(beam),
            PlannerRequest.defaults(new TileKey(2, 2), "sharded", ExitPort.forPreference(
                ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch
            ))
        );

        assertTrue(candidates.isEmpty());
    }

    @Test
    void unknownOrUnavailableCapabilitiesProduceNoDrillCandidates() {
        ContentId copper = ContentId.of("mindustry:copper");
        OrePatch patch = OrePatch.of(copper, new TileKey(0, 0), Set.of(new TileOffset(0, 0)));
        TerrainSnapshot terrain = terrain(
            new TileKey(0, 0), new TileState(false, false, false, null, null, copper, null)
        );
        CapabilityDescriptor unavailable = new CapabilityDescriptor(
            ContentId.of("mod:locked"),
            CapabilityKind.DRILL,
            EnumSet.of(CapabilityState.DISCOVERED, CapabilityState.SUPPORTED),
            Set.of("NO_UNLOCKED_CONTENT"),
            CostVector.empty(),
            "locked",
            new DrillSpec(ContentId.of("mod:locked"), 1, 0, 60f, 0f, List.of(), List.of(), CostVector.empty())
        );

        assertTrue(new CandidateGenerator().generate(
            patch,
            terrain,
            new CapabilitySnapshot(Map.of(unavailable.id(), unavailable)),
            PlannerRequest.defaults(new TileKey(0, 0), "sharded", ExitPort.forPreference(
                ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch
            ))
        ).isEmpty());
    }

    @Test
    void selectedDrillFiltersCandidatesToTheRequestedContent() {
        ContentId copper = ContentId.of("mindustry:copper");
        OrePatch patch = OrePatch.of(copper, new TileKey(2, 2), Set.of(new TileOffset(0, 0)));
        TerrainSnapshot terrain = terrain(
            new TileKey(2, 2), new TileState(false, false, false, null, null, copper, null)
        );
        DrillSpec first = new DrillSpec(
            ContentId.of("mod:first-drill"), 1, 0, 60f, 0f, List.of(), List.of(), CostVector.empty()
        );
        DrillSpec second = new DrillSpec(
            ContentId.of("mod:second-drill"), 1, 0, 30f, 0f, List.of(), List.of(), CostVector.empty()
        );
        CapabilitySnapshot capabilities = new CapabilitySnapshot(Map.of(
            first.id(), descriptor(first),
            second.id(), descriptor(second)
        ));

        List<DrillCandidate> candidates = new CandidateGenerator().generate(
            patch,
            terrain,
            capabilities,
            PlannerRequest.defaults(new TileKey(2, 2), "sharded", ExitPort.forPreference(
                ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch
            )),
            second.id().value()
        );

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().allMatch(candidate -> candidate.drillId().equals(second.id())));
    }

    @Test
    void runtimeCandidatesUseCapturedOreRatesAndReserveMixedOutputTransport() {
        ContentId target = ContentId.of("mod-target");
        ContentId incidental = ContentId.of("mod-incidental");
        TileKey wall = new TileKey(2, 0);
        TileKey anchor = new TileKey(0, 0);
        OrePatch patch = OrePatch.of(target, wall, Set.of(new TileOffset(0, 0)));
        DrillSpec spec = new DrillSpec(ContentId.of("mod-beam"), 2, 3, 1f, 0f,
            List.of(), List.of(), CostVector.empty());
        Map<TileKey, TileState> tiles = Map.of(
            anchor, TileState.empty(),
            new TileKey(1, 0), TileState.empty(),
            new TileKey(0, 1), TileState.empty(),
            new TileKey(1, 1), TileState.empty(),
            new TileKey(2, 1), new TileState(false, true, false, null, null, null, incidental),
            wall, new TileState(false, true, false, null, null, null, target));
        MiningResult mining = new MiningResult(Map.of(
            target, new MiningResult.Output(Set.of(wall), 2f),
            incidental, new MiningResult.Output(Set.of(new TileKey(2, 1)), 2f)));
        PlacementRules rules = new PlacementRules(Map.of(spec.id(), Map.of(anchor, 1)),
            Map.of(new PlacementRules.Placement(spec.id(), anchor, 0), mining));
        TerrainSnapshot terrain = TerrainSnapshot.of(WorldSnapshot.captured(tiles, TerrainRevision.initial(), rules));
        List<DrillCandidate> candidates = new CandidateGenerator().generate(patch, terrain, snapshot(spec),
            PlannerRequest.defaults(wall, "sharded", ExitPort.forPreference(ExitPort.Side.RIGHT, ExitPort.Bias.CENTER, patch)));
        assertEquals(1, candidates.size());
        assertEquals(Set.of(wall), candidates.get(0).coveredOreCells());
        assertEquals(2f, candidates.get(0).production().perSecond());
        assertEquals(4f, candidates.get(0).production().transportPerSecond());
    }

    private CapabilityDescriptor descriptor(DrillSpec spec) {
        return new CapabilityDescriptor(
            spec.id(),
            CapabilityKind.DRILL,
            EnumSet.of(
                CapabilityState.DISCOVERED,
                CapabilityState.SUPPORTED,
                CapabilityState.AVAILABLE_NOW,
                CapabilityState.AFFORDABLE_NOW
            ),
            Set.of(),
            spec.cost(),
            "test",
            spec
        );
    }

    private CapabilitySnapshot snapshot(DrillSpec spec) {
        CapabilityDescriptor descriptor = new CapabilityDescriptor(
            spec.id(),
            CapabilityKind.DRILL,
            EnumSet.of(
                CapabilityState.DISCOVERED,
                CapabilityState.SUPPORTED,
                CapabilityState.AVAILABLE_NOW,
                CapabilityState.AFFORDABLE_NOW
            ),
            Set.of(),
            spec.cost(),
            "test",
            spec
        );
        return new CapabilitySnapshot(Map.of(spec.id(), descriptor));
    }

    private TerrainSnapshot terrain(Object... entries) {
        LinkedHashMap<TileKey, TileState> tiles = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            tiles.put((TileKey) entries[index], (TileState) entries[index + 1]);
        }
        return TerrainSnapshot.of(tiles, TerrainRevision.of(1));
    }
}
