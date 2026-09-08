package autodrillnext.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ExitPort {
    public enum Side {
        TOP,
        BOTTOM,
        LEFT,
        RIGHT
    }

    public enum Bias {
        START,
        CENTER,
        END
    }

    private final String id;
    private final Side side;
    private final Bias bias;
    private final List<ExitAnchor> anchors;

    public ExitPort(String id, Side side, Bias bias, List<ExitAnchor> anchors) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("exit id must not be blank");
        this.id = id;
        this.side = Objects.requireNonNull(side, "side");
        this.bias = Objects.requireNonNull(bias, "bias");
        if (anchors.isEmpty()) throw new IllegalArgumentException("exit must have anchors");
        this.anchors = List.copyOf(anchors);
    }

    public static ExitPort forPreference(Side side, Bias bias, OrePatch patch) {
        Objects.requireNonNull(patch, "patch");
        int axisMin = side == Side.TOP || side == Side.BOTTOM ? patch.minX() : patch.minY();
        int axisMax = side == Side.TOP || side == Side.BOTTOM ? patch.maxX() : patch.maxY();
        int length = axisMax - axisMin + 1;
        int bucket = bias.ordinal();
        int start = axisMin + (length * bucket) / 3;
        int end = axisMin + (length * (bucket + 1)) / 3 - 1;
        if (start > end) end = start;

        ArrayList<ExitAnchor> anchors = new ArrayList<>();
        int fixed = side == Side.TOP ? patch.maxY() + 1
            : side == Side.BOTTOM ? patch.minY() - 1
            : side == Side.LEFT ? patch.minX() - 1
            : patch.maxX() + 1;
        for (int axis = start; axis <= end; axis++) {
            TileKey tile = side == Side.TOP || side == Side.BOTTOM
                ? new TileKey(axis, fixed)
                : new TileKey(fixed, axis);
            int rank = Math.abs(axis - (start + end) / 2);
            anchors.add(new ExitAnchor(tile, side, bias, rank));
        }
        anchors.sort((first, second) -> {
            int byRank = Integer.compare(first.rank(), second.rank());
            if (byRank != 0) return byRank;
            return first.tile().compareTo(second.tile());
        });
        return new ExitPort(
            side.name().toLowerCase(Locale.ROOT) + "-" + bias.name().toLowerCase(Locale.ROOT),
            side,
            bias,
            anchors
        );
    }

    public static List<ExitPort> allPreferences(OrePatch patch) {
        ArrayList<ExitPort> ports = new ArrayList<>();
        for (Side side : Side.values()) {
            for (Bias bias : Bias.values()) ports.add(forPreference(side, bias, patch));
        }
        return Collections.unmodifiableList(ports);
    }

    public String id() {
        return id;
    }

    public Side side() {
        return side;
    }

    public Bias bias() {
        return bias;
    }

    public List<ExitAnchor> anchors() {
        return anchors;
    }
}
