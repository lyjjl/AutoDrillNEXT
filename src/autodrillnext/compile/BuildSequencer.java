package autodrillnext.compile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BuildSequencer {
    public List<BuildPlanCompiler.CompileRecord> sequence(List<BuildPlanCompiler.CompileRecord> records) {
        ArrayList<BuildPlanCompiler.CompileRecord> ordered = new ArrayList<>(records);
        ordered.sort(Comparator
            .comparingInt((BuildPlanCompiler.CompileRecord record) -> record.operation().ordinal())
            .thenComparing(record -> record.tile())
            .thenComparing(BuildPlanCompiler.CompileRecord::blockId)
            .thenComparing(record -> String.valueOf(record.config())));
        return List.copyOf(ordered);
    }
}
