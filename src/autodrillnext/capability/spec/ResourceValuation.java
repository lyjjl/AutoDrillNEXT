package autodrillnext.capability.spec;

import autodrillnext.model.ItemId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Immutable material costs, separate from exact component-wise inventory checks. */
public final class ResourceValuation {
    public static final double UNKNOWN_WEIGHT = 32d;
    public static final double UNRESOLVED_WEIGHT = 64d;
    public static final double MAX_WEIGHT = 1_000_000d;
    // These remain recipe-valued even when another planet exposes a natural deposit.
    private static final Set<ItemId> VANILLA_MANUFACTURED = Set.of(
        ItemId.of("graphite"), ItemId.of("spore-pod"), ItemId.of("metaglass"),
        ItemId.of("silicon"), ItemId.of("oxide"), ItemId.of("pyratite"), ItemId.of("plastanium"),
        ItemId.of("blast-compound"), ItemId.of("phase-fabric"), ItemId.of("surge-alloy"),
        ItemId.of("carbide"), ItemId.of("fissile-matter"), ItemId.of("dormant-cyst"));
    private static final ResourceValuation DEFAULTS = vanilla();
    private final Map<ItemId, Double> weights;

    private ResourceValuation(Map<ItemId, Double> weights) {
        weights.forEach((item, value) -> {
            if (item == null || value == null || !Double.isFinite(value) || value < 0d || value > MAX_WEIGHT) {
                throw new IllegalArgumentException("resource weights must be finite and between 0 and " + MAX_WEIGHT);
            }
        });
        this.weights = Map.copyOf(weights);
    }

    /** Raw anchors only; manufactured items need captured recipes, otherwise they are unknown. */
    public static ResourceValuation defaults() { return DEFAULTS; }

    public static ResourceValuation of(Map<ItemId, ? extends Number> weights) {
        Map<ItemId, Double> result = new HashMap<>(DEFAULTS.weights);
        weights.forEach((item, value) -> {
            double price = value.doubleValue();
            result.put(item, price == 0d ? 0d : price);
        });
        return new ResourceValuation(result);
    }

    public double weight(ItemId item) { return weights.getOrDefault(item, UNKNOWN_WEIGHT); }

    private static ResourceValuation vanilla() {
        Map<ItemId, Double> weights = new HashMap<>();
        put(weights, 1d, "sand", "scrap", "copper");
        put(weights, 2d, "lead", "coal");
        put(weights, 4d, "titanium", "beryllium");
        put(weights, 6d, "thorium");
        put(weights, 7d, "tungsten");
        return new ResourceValuation(weights);
    }

    private static void put(Map<ItemId, Double> weights, double weight, String... names) {
        for (String name : names) weights.put(ItemId.of(name), weight);
    }

    /** One output per edge; co-products receive no speculative resale credit. Separator yield is probability. */
    public record Recipe(ItemId output, double yield, Map<ItemId, Integer> inputs) {
        public Recipe {
            if (output == null || !Double.isFinite(yield) || yield <= 0d) {
                throw new IllegalArgumentException("recipe needs a positive finite output");
            }
            inputs = Collections.unmodifiableMap(new TreeMap<>(inputs));
            if (inputs.values().stream().anyMatch(amount -> amount <= 0)) {
                throw new IllegalArgumentException("recipe inputs must be positive");
            }
        }
    }

    /**
     * Unit value is the sum of ingredient quantities times unit values, divided by output yield.
     * Synchronous relaxation lets a cheap bulk recipe reprice already-reached descendants without
     * depending on content order. No per-stage rounding, manufacturing premium, or synthetic floor.
     * Empty item recipes remain unknown: liquids and energy have no item valuation yet.
     * Acyclic derivations settle within |items| rounds. Continued decreases imply circular discounts;
     * those items and affected descendants use 64, as do ungrounded cycles. Prices saturate at MAX_WEIGHT.
     */
    public static ResourceValuation infer(Set<ItemId> items, Map<ItemId, Long> rawWeights, List<Recipe> recipes) {
        Set<ItemId> all = new HashSet<>(items);
        Set<ItemId> outputs = new HashSet<>();
        Map<ItemId, List<ItemId>> dependents = new HashMap<>();
        List<Recipe> groundedRecipes = new ArrayList<>();
        for (Recipe recipe : recipes) {
            all.add(recipe.output());
            all.addAll(recipe.inputs().keySet());
            if (recipe.inputs().isEmpty()) continue;
            groundedRecipes.add(recipe);
            outputs.add(recipe.output());
            for (ItemId input : recipe.inputs().keySet()) {
                dependents.computeIfAbsent(input, ignored -> new ArrayList<>()).add(recipe.output());
            }
        }
        Map<ItemId, Double> values = new HashMap<>();
        Set<ItemId> fixed = new HashSet<>();
        for (ItemId item : all) {
            Double anchor = DEFAULTS.weights.get(item);
            if (anchor == null && !VANILLA_MANUFACTURED.contains(item) && rawWeights.containsKey(item)) {
                anchor = rawWeights.get(item).doubleValue();
            }
            if (anchor != null) {
                values.put(item, anchor);
                fixed.add(item);
            } else if (!outputs.contains(item)) {
                values.put(item, UNKNOWN_WEIGHT);
            }
        }
        Map<ItemId, Double> changes = new HashMap<>();
        for (int round = 0; round < all.size(); round++) {
            changes.clear();
            for (Recipe recipe : groundedRecipes) {
                if (fixed.contains(recipe.output())) continue;
                double total = 0d;
                for (Map.Entry<ItemId, Integer> input : recipe.inputs().entrySet()) {
                    Double value = values.get(input.getKey());
                    if (value == null) {
                        total = Double.POSITIVE_INFINITY;
                        break;
                    }
                    total += value * input.getValue();
                }
                if (!Double.isFinite(total)) continue;
                double value = Math.min(MAX_WEIGHT, total / recipe.yield());
                if (value < values.getOrDefault(recipe.output(), Double.POSITIVE_INFINITY)) {
                    changes.merge(recipe.output(), value, Math::min);
                }
            }
            if (changes.isEmpty()) break;
            values.putAll(changes);
            if (round == all.size() - 1) {
                ArrayDeque<ItemId> pending = new ArrayDeque<>(changes.keySet());
                Set<ItemId> unstable = new HashSet<>();
                while (!pending.isEmpty()) {
                    ItemId item = pending.removeFirst();
                    if (fixed.contains(item) || !unstable.add(item)) continue;
                    values.put(item, UNRESOLVED_WEIGHT);
                    pending.addAll(dependents.getOrDefault(item, List.of()));
                }
            }
        }
        for (ItemId item : all) values.putIfAbsent(item, UNRESOLVED_WEIGHT);
        return of(values);
    }
}
