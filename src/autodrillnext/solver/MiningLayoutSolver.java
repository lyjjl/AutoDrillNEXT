package autodrillnext.solver;

import autodrillnext.model.DrillCandidate;
import autodrillnext.model.MiningLayout;
import autodrillnext.model.ObjectiveValue;
import autodrillnext.model.PlannerRequest;
import autodrillnext.model.SearchBudget;
import autodrillnext.world.TileKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

public final class MiningLayoutSolver {
    private static final Comparator<DrillCandidate> SPATIAL = Comparator
        .comparingInt((DrillCandidate candidate) -> candidate.anchor().x())
        .thenComparingInt(candidate -> candidate.anchor().y())
        .thenComparingInt(DrillCandidate::rotation).thenComparing(DrillCandidate::id);
    private static final Comparator<DrillCandidate> PRODUCTIVE = Comparator
        .comparingDouble((DrillCandidate candidate) -> candidate.production().perSecond()).reversed().thenComparing(SPATIAL);
    // Eight 2x2 footprints wind around a lattice of free bridge positions in each 6x6 period.
    private static final int[][] PINWHEEL = {{0, 1}, {2, 1}, {1, 3}, {1, 5}, {3, 4}, {5, 4}, {4, 0}, {4, 2}};

    public List<MiningLayout> seeds(List<DrillCandidate> candidates, PlannerRequest request) {
        return seeds(candidates, request, SearchBudget.unlimited());
    }
    public MiningLayout primarySeed(
        List<DrillCandidate> candidates,
        SearchBudget budget
    ) {
        budget.checkpoint();
        Map<String, Geometry> geometry = geometry(candidates, budget);
        ArrayList<DrillCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(PRODUCTIVE);
        return pack(ordered, geometry, budget);
    }

    public List<MiningLayout> seeds(
        List<DrillCandidate> candidates,
        PlannerRequest request,
        SearchBudget budget
    ) {
        budget.checkpoint();
        Map<String, Geometry> geometry = geometry(candidates, budget);
        ArrayList<DrillCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(PRODUCTIVE);
        LinkedHashMap<Set<String>, MiningLayout> layouts = new LinkedHashMap<>();
        budget.checkpoint();
        addUnique(layouts, pack(ordered, geometry, budget));
        ArrayList<DrillCandidate> cheap = new ArrayList<>(ordered);
        cheap.sort(Comparator.comparingInt((DrillCandidate candidate) ->
            candidate.cost().amounts().values().stream().mapToInt(Integer::intValue).sum()).thenComparing(SPATIAL));
        budget.checkpoint();
        addUnique(layouts, pack(cheap, geometry, budget));
        ArrayList<DrillCandidate> spatial = new ArrayList<>(ordered);
        spatial.sort(SPATIAL);
        budget.checkpoint();
        addUnique(layouts, pack(spatial, geometry, budget));

        Map<Size, List<DrillCandidate>> sizes = new LinkedHashMap<>();
        for (DrillCandidate candidate : spatial) {
            Geometry shape = geometry.get(candidate.id());
            sizes.computeIfAbsent(new Size(shape.width, shape.height), ignored -> new ArrayList<>()).add(candidate);
        }
        for (Map.Entry<Size, List<DrillCandidate>> entry : sizes.entrySet()) {
            budget.checkpoint();
            List<DrillCandidate> group = entry.getValue();
            int originX = group.stream().mapToInt(candidate -> geometry.get(candidate.id()).x).min().orElseThrow();
            int originY = group.stream().mapToInt(candidate -> geometry.get(candidate.id()).y).min().orElseThrow();
            Size size = entry.getKey();
            // Two adjacent drill bands can share a walkway; retain narrower lanes for irregular terrain.
            for (int bands = 2; bands >= 1; bands--) {
                for (int axis = 0; axis < 2; axis++) {
                    int width = axis == 0 ? size.width : size.height;
                    int period = bands * width + 1;
                    for (int phase = 0; phase < period; phase++) {
                        ArrayList<DrillCandidate> lane = new ArrayList<>();
                        for (DrillCandidate candidate : group) {
                            Geometry shape = geometry.get(candidate.id());
                            int coordinate = axis == 0 ? shape.x - originX : shape.y - originY;
                            int position = Math.floorMod(coordinate - phase, period);
                            if (position == 0 || bands == 2 && position == width) lane.add(candidate);
                        }
                        lane.sort(PRODUCTIVE);
                        budget.checkpoint();
                        addUnique(layouts, pack(lane, geometry, budget));
                    }
                }
            }
            if (size.width == 2 && size.height == 2) {
                // Rotate the footprint minima, then translate the entire pattern relative to this patch.
                Set<Set<Integer>> masks = new LinkedHashSet<>();
                for (int rotation = 0; rotation < 4; rotation++) {
                    for (int dx = 0; dx < 6; dx++) for (int dy = 0; dy < 6; dy++) {
                        Set<Integer> mask = new HashSet<>();
                        for (int[] point : PINWHEEL) {
                            int x = point[0], y = point[1];
                            for (int turn = 0; turn < rotation; turn++) {
                                int nextX = -y - 1;
                                y = x;
                                x = nextX;
                            }
                            mask.add(Math.floorMod(x + dx, 6) * 6 + Math.floorMod(y + dy, 6));
                        }
                        masks.add(mask);
                    }
                }
                for (Set<Integer> mask : masks) {
                    budget.checkpoint();
                    ArrayList<DrillCandidate> pinwheel = new ArrayList<>();
                    for (DrillCandidate candidate : group) {
                        Geometry shape = geometry.get(candidate.id());
                        int position = Math.floorMod(shape.x - originX, 6) * 6 + Math.floorMod(shape.y - originY, 6);
                        if (mask.contains(position)) pinwheel.add(candidate);
                    }
                    pinwheel.sort(PRODUCTIVE);
                    budget.checkpoint();
                    addUnique(layouts, pack(pinwheel, geometry, budget));
                }
            }
        }
        return List.copyOf(layouts.values());
    }

    public MiningLayout improve(MiningLayout layout, List<DrillCandidate> candidates, PlannerRequest request,
                                ToDoubleFunction<MiningLayout> score) {
        return improve(layout, candidates, request, score,
            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2));
    }

    public MiningLayout improve(MiningLayout layout, List<DrillCandidate> candidates, PlannerRequest request,
                                ToDoubleFunction<MiningLayout> score, long deadlineNanos) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        if (System.nanoTime() >= deadlineNanos) return layout;
        return improve(layout, candidates, request, score,
            SearchBudget.until(deadlineNanos, System::nanoTime));
    }

    public MiningLayout improve(
        MiningLayout layout,
        List<DrillCandidate> candidates,
        PlannerRequest request,
        ToDoubleFunction<MiningLayout> score,
        SearchBudget budget
    ) {
        budget.checkpoint();
        Map<String, Geometry> geometry = geometry(candidates, budget);
        for (DrillCandidate candidate : layout.candidates()) {
            budget.checkpoint();
            geometry.putIfAbsent(candidate.id(), Geometry.of(candidate));
        }
        ArrayList<DrillCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(PRODUCTIVE);
        Refinement search = new Refinement(layout, request.maxIterations(), score, budget);
        boolean changed;
        do {
            MiningLayout incumbent = search.best;
            List<DrillCandidate> selected = incumbent.candidates();
            for (int i = 0; i < selected.size() && search.remaining(); i++) {
                DrillCandidate removed = selected.get(i);
                ArrayList<DrillCandidate> trial = new ArrayList<>(selected);
                trial.remove(i);
                search.consider(MiningLayout.of(trial));
                if (search.remaining()) {
                    search.consider(refill(trial, ordered, Set.of(removed.id()), geometry, budget));
                }
            }
            for (DrillCandidate candidate : ordered) {
                if (!search.remaining()) break;
                if (selected.stream().anyMatch(current -> current.id().equals(candidate.id()))) continue;
                ArrayList<DrillCandidate> trial = new ArrayList<>();
                trial.add(candidate);
                Set<String> removed = new HashSet<>();
                for (DrillCandidate current : selected) {
                    if (conflicts(candidate, current)
                        || candidate.footprint().overlaps(current.footprint())) {
                        removed.add(current.id());
                    } else {
                        trial.add(current);
                    }
                }
                MiningLayout replacement = pack(trial, geometry, budget);
                search.consider(replacement);
                if (search.remaining()) {
                    search.consider(refill(
                        replacement.candidates(), ordered, removed, geometry, budget));
                }
            }
            for (int i = 0; i < selected.size() && search.remaining(); i++) {
                for (int j = i + 1; j < selected.size() && search.remaining(); j++) {
                    ArrayList<DrillCandidate> trial = new ArrayList<>(selected);
                    trial.remove(j);
                    trial.remove(i);
                    search.consider(MiningLayout.of(trial));
                    if (search.remaining()) {
                        search.consider(refill(
                            trial,
                            ordered,
                            Set.of(selected.get(i).id(), selected.get(j).id()),
                            geometry,
                            budget
                        ));
                    }
                }
            }
            changed = search.best != incumbent;
        } while (changed && search.remaining());
        return search.best;
    }

    private MiningLayout refill(
        List<DrillCandidate> retained,
        List<DrillCandidate> candidates,
        Set<String> excluded,
        Map<String, Geometry> geometry,
        SearchBudget budget
    ) {
        Packing packing = new Packing(geometry, budget);
        for (DrillCandidate candidate : retained) packing.add(candidate);
        for (DrillCandidate candidate : candidates) {
            if (!excluded.contains(candidate.id())) packing.add(candidate);
        }
        return MiningLayout.of(packing.selected);
    }

    private MiningLayout pack(
        List<DrillCandidate> candidates,
        Map<String, Geometry> geometry,
        SearchBudget budget
    ) {
        Packing packing = new Packing(geometry, budget);
        for (DrillCandidate candidate : candidates) packing.add(candidate);
        return MiningLayout.of(packing.selected);
    }

    private Map<String, Geometry> geometry(
        List<DrillCandidate> candidates,
        SearchBudget budget
    ) {
        Map<String, Geometry> result = new HashMap<>();
        for (DrillCandidate candidate : candidates) {
            budget.checkpoint();
            result.put(candidate.id(), Geometry.of(candidate));
        }
        return result;
    }

    private static boolean conflicts(DrillCandidate a, DrillCandidate b) {
        return a.id().equals(b.id()) || a.conflictIds().contains(b.id()) || b.conflictIds().contains(a.id());
    }

    private void addUnique(Map<Set<String>, MiningLayout> layouts, MiningLayout layout) {
        if (layout.candidates().isEmpty()) return;
        Set<String> ids = new HashSet<>();
        for (DrillCandidate candidate : layout.candidates()) ids.add(candidate.id());
        layouts.putIfAbsent(ids, layout);
    }


    private record Size(int width, int height) {}

    private record Geometry(int x, int y, int width, int height, Set<TileKey> perimeter) {
        static Geometry of(DrillCandidate candidate) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            Set<TileKey> perimeter = new HashSet<>();
            for (TileKey tile : candidate.footprint().tiles()) {
                minX = Math.min(minX, tile.x()); minY = Math.min(minY, tile.y());
                maxX = Math.max(maxX, tile.x()); maxY = Math.max(maxY, tile.y());
                perimeter.add(new TileKey(tile.x() + 1, tile.y()));
                perimeter.add(new TileKey(tile.x() - 1, tile.y()));
                perimeter.add(new TileKey(tile.x(), tile.y() + 1));
                perimeter.add(new TileKey(tile.x(), tile.y() - 1));
            }
            perimeter.removeAll(candidate.footprint().tiles());
            return new Geometry(minX, minY, maxX - minX + 1, maxY - minY + 1, perimeter);
        }
    }

    /** Incremental outlet accounting avoids rescanning every selected footprint for every seed addition. */
    private static final class Packing {
        final Map<String, Geometry> geometry;
        final SearchBudget budget;
        final List<DrillCandidate> selected = new ArrayList<>();
        final Set<String> ids = new HashSet<>(), forbidden = new HashSet<>();
        final Set<TileKey> occupied = new HashSet<>();
        final Map<TileKey, List<String>> outletUsers = new HashMap<>();
        final Map<String, Integer> freeOutlets = new HashMap<>();

        Packing(Map<String, Geometry> geometry, SearchBudget budget) {
            this.geometry = geometry;
            this.budget = budget;
        }

        void add(DrillCandidate candidate) {
            budget.checkpoint();
            if (ids.contains(candidate.id()) || forbidden.contains(candidate.id())
                || !java.util.Collections.disjoint(ids, candidate.conflictIds())
                || !java.util.Collections.disjoint(occupied, candidate.footprint().tiles())) return;
            Geometry shape = geometry.get(candidate.id());
            int free = 0;
            for (TileKey tile : shape.perimeter) if (!occupied.contains(tile)) free++;
            if (free == 0) return;
            Map<String, Integer> losses = new HashMap<>();
            for (TileKey tile : candidate.footprint().tiles()) {
                List<String> affected = outletUsers.get(tile);
                if (affected != null) for (String id : affected) losses.merge(id, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> loss : losses.entrySet())
                if (freeOutlets.get(loss.getKey()) <= loss.getValue()) return;
            losses.forEach((id, count) -> freeOutlets.compute(id, (ignored, previous) -> previous - count));
            selected.add(candidate);
            ids.add(candidate.id());
            forbidden.addAll(candidate.conflictIds());
            occupied.addAll(candidate.footprint().tiles());
            freeOutlets.put(candidate.id(), free);
            for (TileKey tile : shape.perimeter) {
                if (!occupied.contains(tile)) outletUsers.computeIfAbsent(tile, ignored -> new ArrayList<>()).add(candidate.id());
            }
        }
    }

    private static final class Refinement {
        final ToDoubleFunction<MiningLayout> score;
        final int limit;
        final SearchBudget budget;
        final Set<Set<String>> evaluated = new HashSet<>();
        int attempts;
        MiningLayout best;
        double bestScore;

        Refinement(
            MiningLayout initial,
            int limit,
            ToDoubleFunction<MiningLayout> score,
            SearchBudget budget
        ) {
            this.best = initial;
            this.limit = limit;
            this.score = score;
            this.budget = budget;
            this.bestScore = score.applyAsDouble(initial);
            budget.checkpoint();
            if (!Double.isFinite(bestScore)) bestScore = 0d;
            evaluated.add(ids(initial));
        }

        boolean remaining() {
            budget.checkpoint();
            return attempts < limit;
        }

        void consider(MiningLayout trial) {
            if (!remaining() || trial.candidates().isEmpty() || !evaluated.add(ids(trial))) return;
            attempts++;
            double value = score.applyAsDouble(trial);
            budget.checkpoint();
            if (!Double.isFinite(value) || value <= 0d) return;
            if (value > bestScore || value == bestScore
                && autodrillnext.model.PlanObjectiveOrder
                    .forProfile(PlannerRequest.Profile.THROUGHPUT)
                    .compare(tieBreak(trial), tieBreak(best)) > 0) {
                best = trial;
                bestScore = value;
            }
        }

        private Set<String> ids(MiningLayout layout) {
            Set<String> result = new HashSet<>();
            for (DrillCandidate candidate : layout.candidates()) result.add(candidate.id());
            return result;
        }

        private ObjectiveValue tieBreak(MiningLayout layout) {
            Set<TileKey> covered = new HashSet<>();
            int space = 0;
            for (DrillCandidate candidate : layout.candidates()) {
                covered.addAll(candidate.coveredOreCells());
                space += candidate.footprint().tiles().size();
            }
            return new ObjectiveValue(0f, covered.size(), layout.cost(), space, layout.candidates().size(),
                String.join("|", layout.candidates().stream().map(DrillCandidate::id).sorted().toList()));
        }
    }
}
