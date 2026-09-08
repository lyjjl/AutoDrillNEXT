package autodrillnext.mindustryapi;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.Inventory;
import autodrillnext.model.ItemId;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.world.modules.ItemModule;
import mindustry.type.ItemStack;
import mindustry.world.Block;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ResourceFacade {
    public CostVector buildCost(Block block, Rules rules) {
        LinkedHashMap<ItemId, Integer> amounts = new LinkedHashMap<>();
        if (block.requirements != null) {
            for (ItemStack requirement : block.requirements) {
                if (requirement != null && requirement.item != null && requirement.amount > 0) {
                    amounts.merge(ItemId.of(requirement.item.name), requirement.amount, Integer::sum);
                }
            }
        }
        return CostVector.of(amounts).scaled(rules.buildCostMultiplier);
    }

    public Inventory inventory(Team team) {
        return inventory(team.items());
    }

    public Inventory inventory(ItemModule items) {
        LinkedHashMap<ItemId, Integer> amounts = new LinkedHashMap<>();
        items.each((item, amount) -> {
            if (amount > 0) amounts.put(ItemId.of(item.name), amount);
        });
        return Inventory.of(amounts);
    }

    public boolean affordable(Block block, Team team, Rules rules) {
        return rules.infiniteResources || buildCost(block, rules).componentWiseAtMost(inventory(team));
    }
}
