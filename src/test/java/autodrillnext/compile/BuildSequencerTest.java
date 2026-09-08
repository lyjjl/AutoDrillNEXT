package autodrillnext.compile;

import autodrillnext.world.TileKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildSequencerTest {
    @Test
    void additionsPrecedeConfigurationAndRemoval() {
        BuildPlanCompiler.CompileRecord remove = new BuildPlanCompiler.CompileRecord(
            "old", new TileKey(0, 0), 0, null, Set.of(), BuildPlanCompiler.Operation.REMOVE
        );
        BuildPlanCompiler.CompileRecord configure = new BuildPlanCompiler.CompileRecord(
            "bridge", new TileKey(1, 0), 0, new autodrillnext.world.TileOffset(1, 0), Set.of("bridge-edge"),
            BuildPlanCompiler.Operation.CONFIGURE
        );
        BuildPlanCompiler.CompileRecord add = new BuildPlanCompiler.CompileRecord(
            "bridge", new TileKey(1, 0), 0, null, Set.of("bridge-edge"), BuildPlanCompiler.Operation.ADD
        );

        List<BuildPlanCompiler.CompileRecord> ordered = new BuildSequencer().sequence(List.of(remove, configure, add));

        assertEquals(List.of(BuildPlanCompiler.Operation.ADD, BuildPlanCompiler.Operation.CONFIGURE,
            BuildPlanCompiler.Operation.REMOVE), ordered.stream().map(BuildPlanCompiler.CompileRecord::operation).toList());
    }
}
