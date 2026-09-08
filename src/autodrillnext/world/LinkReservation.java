package autodrillnext.world;

import java.util.Objects;

public record LinkReservation(TileKey first, TileKey second) {
    public LinkReservation {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.equals(second)) throw new IllegalArgumentException("link endpoints must differ");
        if (first.compareTo(second) > 0) {
            TileKey ordered = first;
            first = second;
            second = ordered;
        }
    }

    public static LinkReservation of(TileKey first, TileKey second) {
        return new LinkReservation(first, second);
    }
}
