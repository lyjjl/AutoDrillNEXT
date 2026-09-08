package autodrillnext.solver;

import autodrillnext.capability.spec.CapabilityDescriptor;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilityState;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.compile.PhysicalTransportGraphBuilder;
import autodrillnext.compile.TransportGeometry;
import autodrillnext.model.DrillCandidate;
import autodrillnext.model.EdgeKind;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.PatchSearchScope;
import autodrillnext.model.PlanEdge;
import autodrillnext.model.PlanGraph;
import autodrillnext.model.SearchBudget;
import autodrillnext.model.PlanNode;
import autodrillnext.model.TransportPlacement;
import autodrillnext.world.ExitAnchor;
import autodrillnext.world.TerrainSnapshot;
import autodrillnext.world.TileKey;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Complete finite transport-configuration domain captured from one immutable patch scope. */
public final class TransportPlacementDomain {
    private final PatchSearchScope scope;
    private final List<PlacementConfiguration> configurations;
    private final Map<TileKey, BitSet> configurationClaims;
    private final List<VirtualPlacement> placements;
    private final Map<TileKey, List<VirtualPlacement>> placementsByTile;
    private final Map<TileKey, List<VirtualPlacement>> placementsByOutput;
    private final PhysicalTransportGraphBuilder graphBuilder = new PhysicalTransportGraphBuilder();

    private TransportPlacementDomain(
        PatchSearchScope scope,
        List<PlacementConfiguration> configurations,
        SearchBudget budget
    ) {
        this.scope = scope;
        this.configurations = List.copyOf(configurations);
        this.configurationClaims = buildClaims(this.configurations, budget);

        ArrayList<VirtualPlacement> flat = new ArrayList<>();
        LinkedHashMap<TileKey, List<VirtualPlacement>> byTile = new LinkedHashMap<>();
        LinkedHashMap<TileKey, List<VirtualPlacement>> byOutput = new LinkedHashMap<>();
        for (int configuration = 0; configuration < this.configurations.size(); configuration++) {
            budget.checkpoint();
            List<TransportPlacement> values = this.configurations.get(configuration).placements();
            for (int placement = 0; placement < values.size(); placement++) {
                VirtualPlacement virtual = new VirtualPlacement(configuration, placement, values.get(placement));
                flat.add(virtual);
                byTile.computeIfAbsent(virtual.value().tile(), ignored -> new ArrayList<>()).add(virtual);
                byOutput.computeIfAbsent(virtual.value().output(), ignored -> new ArrayList<>()).add(virtual);
            }
        }
        this.placements = List.copyOf(flat);
        this.placementsByTile = immutableLists(byTile);
        this.placementsByOutput = immutableLists(byOutput);
    }

    public static TransportPlacementDomain capture(PatchSearchScope scope) {
        return capture(scope, SearchBudget.unlimited());
    }

    public static TransportPlacementDomain capture(PatchSearchScope scope, SearchBudget budget) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(budget, "budget").checkpoint();
        TerrainSnapshot terrain = scope.routingTerrain();
        List<ItemTransportSpec> specs = scope.capabilities().descriptors().values().stream()
            .filter(descriptor -> descriptor.kind() == CapabilityKind.ITEM_TRANSPORT)
            .filter(descriptor -> descriptor.states().contains(CapabilityState.AVAILABLE_NOW))
            .map(CapabilityDescriptor::spec)
            .filter(ItemTransportSpec.class::isInstance)
            .map(ItemTransportSpec.class::cast)
            .filter(spec -> spec.supportedGeometry() && spec.capacityPerSecond() > 0f)
            .sorted(Comparator.comparing(spec -> spec.id().value()))
            .toList();
        List<TileKey> tiles = terrain.tiles().keySet().stream().sorted().toList();
        TreeMap<String, PlacementConfiguration> captured = new TreeMap<>();

        for (TileKey tile : tiles) {
            budget.checkpoint();
            for (ItemTransportSpec spec : specs) {
                for (int rotation = 0; rotation < 4; rotation++) {
                    if (!placeable(scope, terrain, spec, tile, rotation)) continue;
                    if (!spec.bridge()) {
                        TransportPlacement placement = new TransportPlacement(
                            spec,
                            tile,
                            rotation,
                            TransportGeometry.step(tile, rotation, 1),
                            null
                        );
                        add(captured, groundId(placement), List.of(placement));
                        continue;
                    }
                    for (int direction = 0; direction < 4; direction++) {
                        budget.checkpoint();
                        for (int distance = 1; distance <= spec.range(); distance++) {
                            TileKey receiverTile = TransportGeometry.step(tile, direction, distance);
                            if (receiverTile.equals(tile)) continue;
                            for (int receiverRotation = 0; receiverRotation < 4; receiverRotation++) {
                                budget.checkpoint();
                                if (!placeable(scope, terrain, spec, receiverTile, receiverRotation)) continue;
                                TransportPlacement sender = new TransportPlacement(
                                    spec,
                                    tile,
                                    rotation,
                                    receiverTile,
                                    receiverTile
                                );
                                TransportPlacement receiver = new TransportPlacement(
                                    spec,
                                    receiverTile,
                                    receiverRotation,
                                    TransportGeometry.step(receiverTile, receiverRotation, 1),
                                    null
                                );
                                if (!TransportGeometry.pairConnects(sender, receiver)) continue;
                                add(captured, bridgeId(sender, receiver), List.of(sender, receiver));
                            }
                        }
                    }
                }
            }
        }
        return new TransportPlacementDomain(scope, List.copyOf(captured.values()), budget);
    }

    public int size() {
        return configurations.size();
    }

    public PlacementConfiguration configuration(int index) {
        return configurations.get(index);
    }

    public BitSet conflicts(int index) {
        if (index < 0 || index >= configurations.size()) {
            throw new IndexOutOfBoundsException("unknown transport configuration: " + index);
        }
        BitSet result = new BitSet(configurations.size());
        for (TileKey tile : configurations.get(index).footprint()) {
            BitSet claims = configurationClaims.get(tile);
            if (claims != null) result.or(claims);
        }
        result.clear(index);
        return result;
    }

    public List<Integer> terminatingAt(ExitAnchor exit) {
        Objects.requireNonNull(exit, "exit");
        ArrayList<Integer> result = new ArrayList<>();
        for (int index = 0; index < configurations.size(); index++) {
            if (configurations.get(index).placements().stream().anyMatch(value -> terminal(value, exit))) {
                result.add(index);
            }
        }
        return List.copyOf(result);
    }

    public List<Integer> feeding(TransportPlacement receiver) {
        Objects.requireNonNull(receiver, "receiver");
        TreeSet<Integer> result = new TreeSet<>();
        for (VirtualPlacement candidate : placementsByOutput.getOrDefault(receiver.tile(), List.of())) {
            if (TransportGeometry.pairConnects(candidate.value(), receiver)) {
                result.add(candidate.configuration());
            }
        }
        return List.copyOf(result);
    }

    public Optional<PlanGraph> exactGraph(MiningLayout layout, BitSet enabled) {
        return exactGraph(layout, enabled, SearchBudget.unlimited());
    }

    public Optional<PlanGraph> exactGraph(
        MiningLayout layout,
        BitSet enabled,
        SearchBudget budget
    ) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(budget, "budget").checkpoint();
        checkBits(enabled, "enabled");
        LinkedHashSet<TileKey> drillTiles = new LinkedHashSet<>();
        for (DrillCandidate drill : layout.candidates()) drillTiles.addAll(drill.footprint().tiles());

        LinkedHashMap<TileKey, TransportPlacement> selected = new LinkedHashMap<>();
        for (int index = enabled.nextSetBit(0); index >= 0; index = enabled.nextSetBit(index + 1)) {
            budget.checkpoint();
            BitSet incompatible = conflicts(index);
            BitSet selectedConflicts = (BitSet) incompatible.clone();
            selectedConflicts.and(enabled);
            if (!selectedConflicts.isEmpty()) return Optional.empty();
            PlacementConfiguration configuration = configurations.get(index);
            for (TransportPlacement placement : configuration.placements()) {
                if (drillTiles.contains(placement.tile())) return Optional.empty();
                TransportPlacement previous = selected.putIfAbsent(placement.tile(), placement);
                if (previous != null && !previous.equals(placement)) return Optional.empty();
            }
        }
        return graphBuilder.build(layout, scope.exit(), selected.values());
    }

    public PlanGraph relaxedGraph(Collection<DrillCandidate> potentialSources, BitSet disabled) {
        return relaxedGraph(potentialSources, disabled, SearchBudget.unlimited());
    }

    public PlanGraph relaxedGraph(
        Collection<DrillCandidate> potentialSources,
        BitSet disabled,
        SearchBudget budget
    ) {
        Objects.requireNonNull(potentialSources, "potential sources");
        Objects.requireNonNull(budget, "budget").checkpoint();
        checkBits(disabled, "disabled");
        ArrayList<PlanNode> nodes = new ArrayList<>();
        ArrayList<PlanEdge> edges = new ArrayList<>();
        nodes.add(PlanNode.sink("sink", scope.exit().tile()));
        float headroom = scope.request().transportHeadroom();

        for (VirtualPlacement virtual : placements) {
            budget.checkpoint();
            if (disabled.get(virtual.configuration())) continue;
            nodes.add(PlanNode.junction(virtual.id(), virtual.value().tile()));
        }
        int edgeIndex = 0;
        for (VirtualPlacement from : placements) {
            budget.checkpoint();
            if (disabled.get(from.configuration())) continue;
            TransportPlacement placement = from.value();
            float capacity = placement.spec().capacityPerSecond() / headroom;
            for (VirtualPlacement to : placementsByTile.getOrDefault(placement.output(), List.of())) {
                if (disabled.get(to.configuration()) || from.equals(to)
                    || !TransportGeometry.pairConnects(placement, to.value())) {
                    continue;
                }
                edges.add(PlanEdge.transport(
                    "relaxed:transport:" + edgeIndex++,
                    placement.bridgeLink() == null ? EdgeKind.GROUND_EDGE : EdgeKind.BRIDGE_EDGE,
                    from.id(),
                    to.id(),
                    capacity,
                    capacity,
                    CostVector.empty(),
                    placement.spec().id().value()
                ));
            }
            if (terminal(placement, scope.exit())) {
                edges.add(PlanEdge.sink(
                    "relaxed:sink:" + edgeIndex++,
                    from.id(),
                    "sink",
                    capacity
                ));
            }
        }

        List<DrillCandidate> sources = potentialSources.stream()
            .sorted(Comparator.comparing(DrillCandidate::id))
            .toList();
        for (DrillCandidate drill : sources) {
            budget.checkpoint();
            String sourceId = "source:" + drill.id();
            nodes.add(PlanNode.source(
                sourceId,
                drill.production().transportPerSecond(),
                drill.anchor(),
                drill.production().output()
            ));
            for (VirtualPlacement receiver : placements) {
                if (disabled.get(receiver.configuration())) continue;
                boolean accepted = false;
                for (TileKey tile : drill.footprint().tiles()) {
                    if (TransportGeometry.acceptsAdjacent(receiver.value(), tile, null)) {
                        accepted = true;
                        break;
                    }
                }
                if (accepted) {
                    edges.add(PlanEdge.source(
                        "relaxed:source:" + edgeIndex++,
                        sourceId,
                        receiver.id(),
                        drill.production().transportPerSecond()
                    ));
                }
            }
        }
        return PlanGraph.of(nodes, edges, "sink");
    }

    private static boolean placeable(
        PatchSearchScope scope,
        TerrainSnapshot terrain,
        ItemTransportSpec spec,
        TileKey tile,
        int rotation
    ) {
        return !scope.existingNetwork().occupiedTiles().contains(tile)
            && !TransportGeometry.conflictsWithExistingDirectionalBridge(spec, tile, terrain)
            && terrain.canPlace(spec.id(), tile, rotation);
    }

    private static void add(
        Map<String, PlacementConfiguration> target,
        String id,
        List<TransportPlacement> placements
    ) {
        PlacementConfiguration configuration = new PlacementConfiguration(
            id,
            placements,
            placements.stream().map(TransportPlacement::tile)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
        );
        PlacementConfiguration previous = target.putIfAbsent(id, configuration);
        if (previous != null && !previous.equals(configuration)) {
            throw new IllegalStateException("duplicate transport configuration id: " + id);
        }
    }

    private static Map<TileKey, BitSet> buildClaims(
        List<PlacementConfiguration> configurations,
        SearchBudget budget
    ) {
        LinkedHashMap<TileKey, BitSet> claims = new LinkedHashMap<>();
        for (int index = 0; index < configurations.size(); index++) {
            budget.checkpoint();
            for (TileKey tile : configurations.get(index).footprint()) {
                claims.computeIfAbsent(tile, ignored -> new BitSet(configurations.size())).set(index);
            }
        }
        return Collections.unmodifiableMap(claims);
    }

    private static Map<TileKey, List<VirtualPlacement>> immutableLists(
        Map<TileKey, List<VirtualPlacement>> source
    ) {
        LinkedHashMap<TileKey, List<VirtualPlacement>> result = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> result.put(entry.getKey(), List.copyOf(entry.getValue())));
        return Collections.unmodifiableMap(result);
    }

    private void checkBits(BitSet bits, String label) {
        Objects.requireNonNull(bits, label);
        if (bits.nextSetBit(configurations.size()) >= 0) {
            throw new IllegalArgumentException(label + " contains an unknown configuration");
        }
    }

    private static boolean terminal(TransportPlacement placement, ExitAnchor exit) {
        return placement.tile().equals(exit.tile()) && placement.bridgeLink() == null
            && placement.output().equals(TransportGeometry.step(
                exit.tile(),
                TransportGeometry.exitDirection(exit),
                1
            ));
    }

    private static String groundId(TransportPlacement placement) {
        return placement.spec().id().value() + "@" + tileId(placement.tile())
            + "/r" + placement.rotation() + "->" + tileId(placement.output());
    }

    private static String bridgeId(TransportPlacement sender, TransportPlacement receiver) {
        return sender.spec().id().value() + "@" + tileId(sender.tile())
            + "/r" + sender.rotation() + "=>" + tileId(receiver.tile())
            + "/r" + receiver.rotation() + "->" + tileId(receiver.output());
    }

    private static String tileId(TileKey tile) {
        return tile.x() + "," + tile.y();
    }

    public record PlacementConfiguration(
        String canonicalId,
        List<TransportPlacement> placements,
        Set<TileKey> footprint
    ) {
        public PlacementConfiguration {
            if (canonicalId == null || canonicalId.isBlank()) {
                throw new IllegalArgumentException("configuration id must not be blank");
            }
            placements = List.copyOf(placements);
            if (placements.isEmpty()) throw new IllegalArgumentException("configuration must not be empty");
            TreeSet<TileKey> ordered = new TreeSet<>(footprint);
            if (ordered.isEmpty()) throw new IllegalArgumentException("configuration footprint must not be empty");
            footprint = Collections.unmodifiableSet(new LinkedHashSet<>(ordered));
        }
    }

    private record VirtualPlacement(int configuration, int placement, TransportPlacement value) {
        private String id() {
            return "relaxed:c" + configuration + ":p" + placement;
        }
    }
}
