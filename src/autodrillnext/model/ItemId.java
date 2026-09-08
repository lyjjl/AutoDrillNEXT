package autodrillnext.model;

import java.util.Objects;

public record ItemId(String value) implements Comparable<ItemId> {
    public ItemId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("item id must not be blank");
    }

    public static ItemId of(String value) {
        return new ItemId(value);
    }

    @Override
    public int compareTo(ItemId other) {
        return value.compareTo(other.value);
    }
}
