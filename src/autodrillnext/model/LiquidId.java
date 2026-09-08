package autodrillnext.model;

import java.util.Objects;

public record LiquidId(String value) implements Comparable<LiquidId> {
    public LiquidId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("liquid id must not be blank");
    }

    public static LiquidId of(String value) {
        return new LiquidId(value);
    }

    @Override
    public int compareTo(LiquidId other) {
        return value.compareTo(other.value);
    }
}
