package autodrillnext.testsupport;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class SyntheticScenario {
    private final TileKey seed;
    private final Set<TileKey> oreCells;
    private final Set<TileKey> solidCells;
    private final Set<TileKey> deepLiquidCells;
    private final int exitRegionCount;

    private SyntheticScenario(
        TileKey seed,
        Set<TileKey> oreCells,
        Set<TileKey> solidCells,
        Set<TileKey> deepLiquidCells,
        int exitRegionCount
    ) {
        this.seed = Objects.requireNonNull(seed);
        this.oreCells = immutableCopy(oreCells);
        this.solidCells = immutableCopy(solidCells);
        this.deepLiquidCells = immutableCopy(deepLiquidCells);
        if (exitRegionCount != 12) throw new IllegalArgumentException("expected twelve exit regions");
        this.exitRegionCount = exitRegionCount;
    }

    public static SyntheticScenario rectangle(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("rectangle must be positive");
        Set<TileKey> ore = new LinkedHashSet<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) ore.add(new TileKey(x, y));
        }
        return new SyntheticScenario(new TileKey(0, 0), ore, Set.of(), Set.of(), 12);
    }

    public static SyntheticScenario wallWaterIslandAndNarrowExit() {
        SyntheticScenario base = rectangle(9, 7);
        Set<TileKey> solid = new LinkedHashSet<>();
        for (int x = -1; x <= 9; x++) {
            solid.add(new TileKey(x, -1));
            solid.add(new TileKey(x, 7));
        }
        solid.add(new TileKey(4, 3));
        solid.add(new TileKey(4, 4));

        Set<TileKey> liquid = Set.of(
            new TileKey(10, 1), new TileKey(10, 2), new TileKey(10, 3),
            new TileKey(10, 4), new TileKey(10, 5)
        );
        return new SyntheticScenario(base.seed, base.oreCells, solid, liquid, 12);
    }

    public SyntheticScenario translated(int dx, int dy) {
        return map(key -> new TileKey(key.x + dx, key.y + dy));
    }

    public TileKey seed() {
        return seed;
    }

    public Set<TileKey> oreCells() {
        return oreCells;
    }

    public Set<TileKey> solidCells() {
        return solidCells;
    }

    public Set<TileKey> deepLiquidCells() {
        return deepLiquidCells;
    }

    public int exitRegionCount() {
        return exitRegionCount;
    }

    public Set<TileKey> obstacleCells() {
        LinkedHashSet<TileKey> result = new LinkedHashSet<>(solidCells);
        result.addAll(deepLiquidCells);
        return Collections.unmodifiableSet(result);
    }

    public Set<TileKey> oreCellsRelativeToSeed() {
        return relativeCells(oreCells);
    }

    public Set<TileKey> obstacleCellsRelativeToSeed() {
        return relativeCells(obstacleCells());
    }

    private SyntheticScenario map(Function<TileKey, TileKey> mapper) {
        return new SyntheticScenario(
            mapper.apply(seed),
            mapCells(oreCells, mapper),
            mapCells(solidCells, mapper),
            mapCells(deepLiquidCells, mapper),
            exitRegionCount
        );
    }

    private static Set<TileKey> mapCells(Set<TileKey> source, Function<TileKey, TileKey> mapper) {
        return source.stream().map(mapper).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<TileKey> relativeCells(Set<TileKey> cells) {
        TreeSet<TileKey> result = new TreeSet<>();
        for (TileKey cell : cells) result.add(cell.relativeTo(seed));
        return Collections.unmodifiableSet(result);
    }

    private static Set<TileKey> immutableCopy(Set<TileKey> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    public record TileKey(int x, int y) implements Comparable<TileKey> {
        public TileKey relativeTo(TileKey origin) {
            return new TileKey(x - origin.x, y - origin.y);
        }

        @Override
        public int compareTo(TileKey other) {
            int byX = Integer.compare(x, other.x);
            return byX != 0 ? byX : Integer.compare(y, other.y);
        }
    }
}
