package autodrillnext.mindustryapi;

import autodrillnext.capability.spec.CapabilityCandidate;
import autodrillnext.capability.spec.CapabilityKind;
import autodrillnext.capability.spec.CapabilitySnapshot;
import autodrillnext.capability.spec.DrillSpec;
import autodrillnext.model.ItemId;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.game.Teams;
import mindustry.world.Block;
import mindustry.world.blocks.production.Drill;
import mindustry.world.meta.BuildVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameFacadeTest {
    @BeforeEach
    void initializeMindustryState() {
        Vars.content = new ContentLoader();
        Vars.state = new GameState();
        Vars.state.rules = new Rules();
        Vars.state.teams = new Teams();
    }

    @Test
    void discoveryUsesRegisteredClassAdaptersAndQuarantinesUnknownBlocks() {
        Drill drill = new Drill("test-drill");
        drill.alwaysUnlocked = true;
        drill.buildVisibility = BuildVisibility.shown;
        Block unknown = new Block("test-unknown");
        unknown.alwaysUnlocked = true;
        unknown.buildVisibility = BuildVisibility.shown;

        GameFacade facade = new GameFacade();
        Map<String, CapabilityCandidate> candidates = facade.discover(Team.sharded, Vars.state.rules)
            .stream()
            .collect(java.util.stream.Collectors.toMap(candidate -> candidate.id().value(), candidate -> candidate));

        CapabilityCandidate discoveredDrill = candidates.get("test-drill");
        CapabilityCandidate discoveredUnknown = candidates.get("test-unknown");
        assertEquals(CapabilityKind.DRILL, discoveredDrill.kind());
        assertTrue(discoveredDrill.supported());
        assertInstanceOf(DrillSpec.class, discoveredDrill.spec());
        assertFalse(discoveredUnknown.supported());
        assertEquals(CapabilityKind.UNKNOWN, discoveredUnknown.kind());
    }

    @Test
    void snapshotSeparatesAvailabilityFromAffordability() {
        Drill drill = new Drill("affordable-drill");
        drill.alwaysUnlocked = true;
        drill.buildVisibility = BuildVisibility.shown;

        CapabilitySnapshot snapshot = new GameFacade().snapshot(Team.sharded, Vars.state.rules);

        var descriptor = snapshot.require(autodrillnext.model.ContentId.of("affordable-drill"));
        assertTrue(descriptor.states().contains(autodrillnext.capability.spec.CapabilityState.SUPPORTED));
        assertTrue(descriptor.states().contains(autodrillnext.capability.spec.CapabilityState.AVAILABLE_NOW));
        assertTrue(descriptor.states().contains(autodrillnext.capability.spec.CapabilityState.AFFORDABLE_NOW));
        assertEquals("DrillAdapter", descriptor.adapterId());
        assertInstanceOf(DrillSpec.class, descriptor.spec());
    }
    @Test
    void customMiningUpdateIsQuarantinedInsteadOfClaimingStandardProduction() {
        Drill drill = new Drill("mod-custom-mechanism");
        drill.alwaysUnlocked = true;
        drill.buildVisibility = BuildVisibility.shown;
        drill.buildType = () -> drill.new DrillBuild() {
            @Override
            public void updateTile() {
                progress += 100f;
            }
        };
        CapabilityCandidate candidate = new GameFacade().describe(drill, Team.sharded, Vars.state.rules);
        assertFalse(candidate.supported());
        assertTrue(candidate.adapterId().contains("updateTile"));
    }

    @Test
    void customProductionValidityIsNotAssumedToBeStandardMining() {
        Drill drill = new Drill("mod-production-gate");
        drill.alwaysUnlocked = true;
        drill.buildVisibility = BuildVisibility.shown;
        drill.buildType = () -> drill.new DrillBuild() {
            @Override
            public boolean productionValid() {
                return false;
            }
        };
        CapabilityCandidate candidate = new GameFacade().describe(drill, Team.sharded, Vars.state.rules);
        assertFalse(candidate.supported());
        assertTrue(candidate.adapterId().contains("productionValid"));
    }

    @Test
    void cosmeticBuildOverridesRemainSupported() {
        Drill drill = new Drill("mod-cosmetic-drill");
        drill.alwaysUnlocked = true;
        drill.buildVisibility = BuildVisibility.shown;
        drill.buildType = () -> drill.new DrillBuild() {
            @Override
            public void draw() {
                super.draw();
            }
        };
        assertTrue(new GameFacade().describe(drill, Team.sharded, Vars.state.rules).supported());
    }

    @Test
    void capturedCostsRefreshRecipesAndRuleRoundingWithoutMutatingOldSnapshots() {
        Item input = new Item("mod-unknown-input");
        Item product = new Item("mod-building-material");
        GenericCrafter crafter = new GenericCrafter("mod-recipe");
        crafter.consumeItems(new ItemStack(input, 2));
        crafter.outputItem = new ItemStack(product, 1);
        Drill drill = new Drill("valued-drill");
        drill.requirements = new ItemStack[]{new ItemStack(product, 3)};
        Vars.state.rules.buildCostMultiplier = 0.5f;
        GameFacade facade = new GameFacade();
        var oldCost = facade.describe(drill, Team.sharded, Vars.state.rules).cost();
        assertEquals(2, oldCost.amount(ItemId.of(product.name)));
        assertEquals(128d, oldCost.economicValue());

        crafter.outputItem = new ItemStack(product, 2);
        Vars.state.rules.buildCostMultiplier = 1f;
        var newCost = facade.describe(drill, Team.sharded, Vars.state.rules).cost();
        assertEquals(3, newCost.amount(ItemId.of(product.name)));
        assertEquals(96d, newCost.economicValue());
        assertEquals(128d, oldCost.economicValue());
    }

}
