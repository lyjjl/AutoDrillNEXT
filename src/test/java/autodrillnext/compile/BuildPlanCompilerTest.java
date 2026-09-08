package autodrillnext.compile;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlanNode;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildPlanCompilerTest {
    @Test
    void bridgeCompileRecordsPreserveRelativeConfigsAndDependencyPair() {
        PlanGraph graph = PlanGraph.of(
            List.of(
                PlanNode.junction("a", new TileKey(2, 3)),
                PlanNode.junction("b", new TileKey(5, 3))
            ),
            List.of(PlanEdge.transport(
                "bridge-edge",
                autodrillnext.model.EdgeKind.BRIDGE_EDGE,
                "a",
                "b",
                5f,
                5f,
                CostVector.empty(),
                "item-bridge"
            )),
            "b"
        );

        List<BuildPlanCompiler.CompileRecord> records = new BuildPlanCompiler().compile(graph);

        assertEquals(2, records.size());
        assertTrue(records.stream().allMatch(record -> record.dependencies().contains("bridge-edge")));
        assertTrue(records.stream().anyMatch(record ->
            record.tile().equals(new TileKey(2, 3)) && new TileOffset(3, 0).equals(record.config())));
        assertTrue(records.stream().anyMatch(record ->
            record.tile().equals(new TileKey(5, 3)) && record.config() == null));
    }
}
