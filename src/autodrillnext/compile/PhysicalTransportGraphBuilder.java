package autodrillnext.compile;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.model.ContentId;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.PlanNode;
import autodrillnext.model.TransportPlacement;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.TileKey;
import autodrillnext.world.TileOffset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PhysicalTransportGraphBuilder {
    private static final Comparator<TransportPlacement> PLACEMENT_ORDER = Comparator
        .comparing(TransportPlacement::tile)
        .thenComparing(placement -> placement.spec().id().value())
        .thenComparingInt(TransportPlacement::rotation)
        .thenComparing(TransportPlacement::output)
        .thenComparing(TransportPlacement::bridgeLink, Comparator.nullsFirst(Comparator.naturalOrder()));

    public Optional<PlanGraph> build(
        MiningLayout layout,
        ExitAnchor exit,
        Collection<TransportPlacement> values
    ) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(exit, "exit");
        Objects.requireNonNull(values, "placements");
        LinkedHashMap<TileKey, TransportPlacement> placements = canonicalPlacements(values);
        if (placements == null || !TransportGeometry.networkConsistent(placements, exit)) {
            return Optional.empty();
        }

        ArrayList<PlanNode> nodes = new ArrayList<>();
        ArrayList<PlanEdge> edges = new ArrayList<>();
        nodes.add(PlanNode.sink("sink", exit.tile()));
        for (TransportPlacement placement : placements.values()) {
            nodes.add(PlanNode.junction(tileId(placement.tile()), placement.tile()));
        }
        for (TransportPlacement placement : placements.values()) {
            String target = placements.containsKey(placement.output())
                ? tileId(placement.output())
                : "sink";
            float capacity = TransportGeometry.admissionCapacity(placement, placements, layout);
            edges.add(PlanEdge.transport(
                "transport:" + tileId(placement.tile()),
                placement.bridgeLink() == null ? EdgeKind.GROUND_EDGE : EdgeKind.BRIDGE_EDGE,
                tileId(placement.tile()),
                target,
                capacity,
                capacity,
                placement.spec().cost(),
                placement.spec().id().value()
            ).withFootprint(Set.of(placement.tile())));
        }
        for (DrillCandidate drill : layout.candidates()) {
            String source = "source:" + drill.id();
            nodes.add(PlanNode.source(
                source,
                drill.production().transportPerSecond(),
                drill.anchor(),
                drill.production().output()
            ));
            boolean connected = false;
            for (TransportPlacement receiver : placements.values()) {
                for (TileKey tile : drill.footprint().tiles()) {
                    if (!TransportGeometry.acceptsAdjacent(receiver, tile, null, placements)) continue;
                    edges.add(PlanEdge.source(
                        source + ":feed:" + tileId(receiver.tile()),
                        source,
                        tileId(receiver.tile()),
                        drill.production().transportPerSecond()
                    ));
                    connected = true;
                    break;
                }
            }
            if (!connected) return Optional.empty();
        }
        return Optional.of(PlanGraph.of(nodes, edges, "sink")
            .withPlacements(List.copyOf(placements.values()), exit));
    }

    public Optional<PlanGraph> fromCompileRecords(
        List<BuildPlanCompiler.CompileRecord> records,
        MiningLayout layout,
        ExitAnchor exit,
        CapabilitySnapshot capabilities
    ) {
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(capabilities, "capabilities");
        if (layout == null || exit == null) return Optional.empty();

        ArrayList<TransportPlacement> placements = new ArrayList<>();
        for (BuildPlanCompiler.CompileRecord record : records) {
            if (record.operation() != BuildPlanCompiler.Operation.ADD) continue;
            CapabilityDescriptor descriptor = capabilities.descriptors().get(ContentId.of(record.blockId()));
            if (descriptor == null || !(descriptor.spec() instanceof ItemTransportSpec spec)) continue;
            if (!spec.supportedGeometry()) return Optional.empty();

            TileKey bridgeLink = null;
            if (record.config() instanceof TileOffset offset) {
                if (spec.portBehavior() != ItemTransportSpec.PortBehavior.ITEM_BRIDGE) {
                    return Optional.empty();
                }
                bridgeLink = offset.from(record.tile());
            } else if (record.config() != null) {
                return Optional.empty();
            }
            TileKey output = bridgeLink == null
                ? TransportGeometry.step(record.tile(), record.rotation(), 1)
                : bridgeLink;
            placements.add(new TransportPlacement(
                spec,
                record.tile(),
                record.rotation(),
                output,
                bridgeLink
            ));
        }

        LinkedHashMap<TileKey, TransportPlacement> byTile = canonicalPlacements(placements);
        if (byTile == null) return Optional.empty();
        for (TransportPlacement placement : List.copyOf(byTile.values())) {
            if (placement.spec().portBehavior() != ItemTransportSpec.PortBehavior.DUCT_BRIDGE) continue;
            TileKey link = TransportGeometry.automaticLink(placement, byTile);
            byTile.put(placement.tile(), new TransportPlacement(
                placement.spec(),
                placement.tile(),
                placement.rotation(),
                link == null ? placement.output() : link,
                link
            ));
        }
        return build(layout, exit, byTile.values());
    }

    private LinkedHashMap<TileKey, TransportPlacement> canonicalPlacements(
        Collection<TransportPlacement> values
    ) {
        ArrayList<TransportPlacement> ordered = new ArrayList<>(values);
        ordered.sort(PLACEMENT_ORDER);
        LinkedHashMap<TileKey, TransportPlacement> placements = new LinkedHashMap<>();
        for (TransportPlacement placement : ordered) {
            Objects.requireNonNull(placement, "placement");
            TransportPlacement previous = placements.putIfAbsent(placement.tile(), placement);
            if (previous != null && !previous.equals(placement)) return null;
        }
        return placements;
    }

    private static String tileId(TileKey tile) {
        return "tile:" + tile.x() + "," + tile.y();
    }
}
