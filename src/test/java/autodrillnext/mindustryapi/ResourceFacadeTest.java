package autodrillnext.mindustryapi;

import autodrillnext.capability.spec.CostVector;
import autodrillnext.model.Inventory;
import autodrillnext.model.ItemId;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.core.ContentLoader;
import mindustry.game.Rules;
import mindustry.game.Teams;
import mindustry.game.Team;
import mindustry.world.modules.ItemModule;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResourceFacadeTest {
    @BeforeEach
    void initializeContent() {
        Vars.content = new ContentLoader();
        Vars.state = new GameState();
        Vars.state.rules = new Rules();
        Vars.state.teams = new Teams();
    }

    @Test
    void buildCostUsesRulesMultiplierAndRoundsEachItem() {
        Item copper = new Item("copper");
        Item graphite = new Item("graphite");
        Block block = new Block("cost-test");
        block.requirements = new ItemStack[]{new ItemStack(copper, 3), new ItemStack(graphite, 5)};
        Rules rules = new Rules();
        rules.buildCostMultiplier = 0.5f;

        CostVector cost = new ResourceFacade().buildCost(block, rules);

        assertEquals(2, cost.amount(ItemId.of("copper")));
        assertEquals(3, cost.amount(ItemId.of("graphite")));
    }

    @Test
    void inventoryReadsTheCurrentItemModule() {
        Item copper = new Item("copper");
        ItemModule items = new ItemModule();
        items.set(copper, 7);

        Inventory inventory = new ResourceFacade().inventory(items);

        assertEquals(7, inventory.amount(ItemId.of("copper")));
    }
}
