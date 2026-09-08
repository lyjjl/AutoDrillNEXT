package autodrillnext.compile;

import autodrillnext.model.DrillCandidate;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlanNode;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class BuildPlanCompiler {
    public List<CompileRecord> compile(PlanGraph graph) {
        return compile(graph, null);
    }

    public List<CompileRecord> compile(PlanGraph graph, MiningLayout layout) {
        ArrayList<CompileRecord> records = new ArrayList<>();
        if (layout != null) {
            for (DrillCandidate candidate : layout.candidates()) {
                records.add(new CompileRecord(
                    candidate.drillId().value(),
                    candidate.anchor(),
                    candidate.rotation(),
                    null,
                    Set.of(candidate.id()),
                    Operation.ADD
                ));
            }
        }
        if (!graph.placements().isEmpty()) {
            for (var placement : graph.placements()) {
                Object config = placement.bridgeLink() != null
                    && placement.spec().portBehavior() == autodrillnext.capability.spec.ItemTransportSpec.PortBehavior.ITEM_BRIDGE
                    ? new TileOffset(placement.bridgeLink().x() - placement.tile().x(),
                        placement.bridgeLink().y() - placement.tile().y()) : null;
                records.add(new CompileRecord(placement.spec().id().value(), placement.tile(), placement.rotation(),
                    config, Set.of("transport:tile:" + placement.tile().x() + "," + placement.tile().y()), Operation.ADD));
            }
            records.sort(Comparator.comparing(CompileRecord::tile).thenComparing(CompileRecord::blockId));
            return List.copyOf(records);
        }
        for (PlanEdge edge : graph.edges()) {
            if (edge.kind() == EdgeKind.SOURCE_EDGE
                || edge.kind() == EdgeKind.SINK_EDGE
                || edge.kind() == EdgeKind.EXISTING_EDGE) continue;
            PlanNode from = graph.node(edge.from());
            PlanNode to = graph.node(edge.to());
            LinkedHashSet<String> dependencies = new LinkedHashSet<>(edge.dependencies());
            dependencies.add(edge.id());
            if (edge.kind() == EdgeKind.BRIDGE_EDGE) {
                if (edge.footprint().isEmpty() || edge.footprint().contains(from.tile())) {
                    records.add(record(edge, from.tile(), to.tile().x() - from.tile().x(), to.tile().y() - from.tile().y(), dependencies));
                }
                if (edge.footprint().isEmpty() || edge.footprint().contains(to.tile())) {
                    records.add(new CompileRecord(edge.transportId(), to.tile(),
                        rotation(from.tile(), to.tile()), null, dependencies, Operation.ADD));
                }
            } else {
                TileKey tile = edge.footprint().stream().sorted().findFirst().orElse(from.tile());
                records.add(new CompileRecord(
                    edge.transportId(),
                    tile,
                    rotation(from.tile(), to.tile()),
                    null,
                    dependencies,
                    Operation.ADD
                ));
            }
        }
        records.sort(Comparator
            .comparingInt((CompileRecord record) -> record.operation().ordinal())
            .thenComparing(record -> record.tile())
            .thenComparing(CompileRecord::blockId)
            .thenComparing(record -> String.valueOf(record.config())));
        return List.copyOf(records);
    }

    private int rotation(TileKey from, TileKey to) {
        int dx = Integer.compare(to.x(), from.x());
        int dy = Integer.compare(to.y(), from.y());
        if (dx > 0) return 0;
        if (dy > 0) return 1;
        if (dx < 0) return 2;
        return 3;
    }

    private CompileRecord record(
        PlanEdge edge,
        TileKey tile,
        int dx,
        int dy,
        Set<String> dependencies
    ) {
        return new CompileRecord(
            edge.transportId(),
            tile,
            rotation(new TileKey(0, 0), new TileKey(dx, dy)),
            new TileOffset(dx, dy),
            dependencies,
            Operation.ADD
        );
    }

    public enum Operation {
        ADD,
        CONFIGURE,
        CONNECT,
        KEEP,
        REMOVE
    }

    public record CompileRecord(
        String blockId,
        TileKey tile,
        int rotation,
        Object config,
        Set<String> dependencies,
        Operation operation
    ) {
        public CompileRecord {
            if (blockId == null || blockId.isBlank()) throw new IllegalArgumentException("compile block id must not be blank");
            Objects.requireNonNull(tile, "compile tile");
            if (rotation < 0 || rotation > 3) throw new IllegalArgumentException("compile rotation must be in [0, 3]");
            dependencies = Set.copyOf(dependencies);
            Objects.requireNonNull(operation, "compile operation");
        }
    }
}
