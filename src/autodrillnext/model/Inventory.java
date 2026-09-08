package autodrillnext.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class Inventory {
    private final Map<ItemId, Integer> amounts;

    private Inventory(Map<ItemId, Integer> amounts) {
        LinkedHashMap<ItemId, Integer> copy = new LinkedHashMap<>();
        amounts.forEach((item, amount) -> {
            Objects.requireNonNull(item, "item");
            if (amount < 0) throw new IllegalArgumentException("inventory amount must not be negative");
            if (amount > 0) copy.put(item, amount);
        });
        this.amounts = Collections.unmodifiableMap(copy);
    }

    public static Inventory empty() {
        return new Inventory(Map.of());
    }

    public static Inventory of(Map<ItemId, Integer> amounts) {
        return new Inventory(amounts);
    }

    public int amount(ItemId item) {
        return amounts.getOrDefault(item, 0);
    }

    public Map<ItemId, Integer> amounts() {
        return amounts;
    }
}
