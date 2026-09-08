package autodrillnext.capability.spec;

import autodrillnext.model.Inventory;
import autodrillnext.model.ItemId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class CostVector {
    private final Map<ItemId, Integer> amounts;
    private final long total;
    private final double economicValue;
    private final ResourceValuation valuation;
    private static final CostVector EMPTY = new CostVector(Map.of(), ResourceValuation.defaults());

    private CostVector(Map<ItemId, Integer> amounts, ResourceValuation valuation) {
        this.valuation = Objects.requireNonNull(valuation, "resource valuation");
        TreeMap<ItemId, Integer> sorted = new TreeMap<>();
        amounts.forEach((item, amount) -> {
            Objects.requireNonNull(item, "item");
            if (amount < 0) throw new IllegalArgumentException("cost amount must not be negative");
            if (amount > 0) sorted.put(item, amount);
        });
        this.amounts = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
        this.total = this.amounts.values().stream().mapToLong(Integer::longValue).sum();
        double value = 0d;
        for (Map.Entry<ItemId, Integer> entry : this.amounts.entrySet()) {
            value += entry.getValue() * valuation.weight(entry.getKey());
        }
        this.economicValue = value;
    }

    public static CostVector empty() {
        return EMPTY;
    }

    public static CostVector of(Map<ItemId, Integer> amounts) {
        return new CostVector(amounts, ResourceValuation.defaults());
    }

    public static CostVector of(Map<ItemId, Integer> amounts, ResourceValuation valuation) {
        return new CostVector(amounts, valuation);
    }

    public int amount(ItemId item) {
        return amounts.getOrDefault(item, 0);
    }

    public boolean hasComponent(ItemId item) {
        return amounts.containsKey(item);
    }

    public boolean isEmpty() {
        return amounts.isEmpty();
    }

    public Map<ItemId, Integer> amounts() {
        return amounts;
    }

    public long total() {
        return total;
    }

    /** Cached weighted material score; total() and amounts() retain exact inventory quantities. */
    public double economicValue() {
        return economicValue;
    }

    public CostVector plus(CostVector other) {
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;
        ResourceValuation combined = valuation;
        if (valuation != other.valuation) {
            LinkedHashMap<ItemId, Double> weights = new LinkedHashMap<>();
            for (ItemId item : amounts.keySet()) weights.put(item, valuation.weight(item));
            for (ItemId item : other.amounts.keySet()) {
                double weight = other.valuation.weight(item);
                Double previous = weights.putIfAbsent(item, weight);
                if (previous != null && previous != weight) {
                    throw new IllegalArgumentException("cannot combine different valuation snapshots for " + item.value());
                }
            }
            combined = ResourceValuation.of(weights);
        }
        LinkedHashMap<ItemId, Integer> result = new LinkedHashMap<>(amounts);
        other.amounts.forEach((item, amount) -> result.merge(item, amount, Math::addExact));
        return new CostVector(result, combined);
    }

    public CostVector scaled(float multiplier) {
        if (!Float.isFinite(multiplier) || multiplier < 0f) {
            throw new IllegalArgumentException("cost multiplier must be finite and non-negative");
        }
        LinkedHashMap<ItemId, Integer> result = new LinkedHashMap<>();
        amounts.forEach((item, amount) -> result.put(item, Math.round(multiplier * amount)));
        return new CostVector(result, valuation);
    }

    public boolean componentWiseAtMost(Inventory inventory) {
        for (Map.Entry<ItemId, Integer> entry : amounts.entrySet()) {
            if (entry.getValue() > inventory.amount(entry.getKey())) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof CostVector other) || !amounts.equals(other.amounts)) return false;
        if (valuation == other.valuation) return true;
        for (ItemId item : amounts.keySet()) {
            if (valuation.weight(item) != other.valuation.weight(item)) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = amounts.hashCode();
        for (ItemId item : amounts.keySet()) hash = 31 * hash + Double.hashCode(valuation.weight(item));
        return hash;
    }

    @Override
    public String toString() {
        return amounts.toString();
    }
}
