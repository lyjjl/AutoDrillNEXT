package autodrillnext.capability;

import autodrillnext.capability.adapter.ResourceValuationCapture;
import autodrillnext.capability.spec.ResourceValuation;
import autodrillnext.model.ItemId;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.blocks.environment.OreBlock;
import mindustry.world.blocks.environment.StaticWall;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.Separator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceValuationTest {
    @BeforeEach
    void content() {
        Vars.content = new ContentLoader();
    }

    @Test
    void vanillaAndModRecipesShareIngredientCostsWithoutRoundingEachStage() {
        Item coal = new Item("coal");
        Item graphite = new Item("graphite");
        Item mod = new Item("mod-graphite");
        Item assembled = new Item("assembled");
        StaticWall wall = new StaticWall("graphite-wall");
        wall.itemDrop = graphite;
        BeamDrill miner = new BeamDrill("wall-miner");
        miner.tier = 1;
        crafter("vanilla-recipe", coal, 2, graphite, 3);
        crafter("mod-recipe", coal, 2, mod, 3);
        crafter("assembly", graphite, 3, assembled, 1);
        ResourceValuation values = capture();
        assertEquals(4d / 3d, values.weight(id(graphite)), 1e-12);
        assertEquals(4d / 3d, values.weight(id(mod)), 1e-12);
        assertEquals(4d, values.weight(id(assembled)), 1e-12);
        assertEquals(4d, autodrillnext.capability.spec.CostVector.of(
            java.util.Map.of(id(mod), 3), values).economicValue(), 1e-12);
    }

    @Test
    void actualDropsAndMiningTiersPrecedeRecipeChainsWithoutNameHeuristics() {
        Item basic = new Item("mod-plastanium-looking-raw");
        basic.hardness = 1;
        Item advanced = new Item("mod-copper-looking-raw");
        advanced.hardness = 4;
        new OreBlock("basic-deposit", basic);
        StaticWall wall = new StaticWall("advanced-deposit");
        wall.itemDrop = advanced;
        Drill drill = new Drill("basic-miner");
        drill.tier = 2;
        BeamDrill beam = new BeamDrill("advanced-miner");
        beam.tier = 5;
        Item processed = new Item("mod-processed");
        Item finalItem = new Item("mod-final");
        crafter("first-stage", advanced, 2, processed, 1);
        crafter("second-stage", processed, 1, finalItem, 1);
        ResourceValuation values = capture();
        assertTrue(values.weight(id(basic)) < values.weight(id(advanced)));
        assertTrue(values.weight(id(advanced)) < values.weight(id(processed)));
        assertEquals(values.weight(id(processed)), values.weight(id(finalItem)));
    }

    @Test
    void alternativesAvoidExpensiveRecipesAndCyclesNeverBecomeFree() {
        Item raw = new Item("mod-raw");
        new OreBlock("raw-deposit", raw);
        Item unknown = new Item("mod-unknown");
        Item product = new Item("mod-product");
        crafter("expensive", unknown, 8, product, 1);
        crafter("cheap", raw, 1, product, 1);
        Item a = new Item("mod-cycle-a");
        Item b = new Item("mod-cycle-b");
        crafter("cycle-a", b, 1, a, 1);
        crafter("cycle-b", a, 1, b, 1);
        ResourceValuation first = capture();
        assertTrue(first.weight(id(product)) < first.weight(id(unknown)));
        assertTrue(first.weight(id(a)) >= ResourceValuation.UNKNOWN_WEIGHT);
        assertTrue(first.weight(id(b)) >= ResourceValuation.UNKNOWN_WEIGHT);
        crafter("cycle-entry", raw, 1, a, 1);
        ResourceValuation second = capture();
        assertTrue(second.weight(id(a)) < first.weight(id(a)));
        assertEquals(second.weight(id(a)), second.weight(id(b)));
        assertEquals(ResourceValuation.UNRESOLVED_WEIGHT, first.weight(id(a)));
    }

    @Test
    void multiOutputAndSeparatorProbabilitiesCannotInventFreeInputs() {
        Item input = new Item("unknown-input");
        Item common = new Item("common-result");
        Item rare = new Item("rare-result");
        Separator separator = new Separator("separator");
        separator.consumeItems(new ItemStack(input, 1));
        separator.results = new ItemStack[]{new ItemStack(common, 9), new ItemStack(rare, 1)};
        Item coProduct = new Item("co-product");
        Item coProduct2 = new Item("co-product-2");
        GenericCrafter multiple = new GenericCrafter("multiple");
        multiple.consumeItems(new ItemStack(input, 2));
        multiple.outputItems = new ItemStack[]{new ItemStack(coProduct, 10), new ItemStack(coProduct2, 2)};
        ResourceValuation values = capture();
        assertEquals(32d / 0.9d, values.weight(id(common)), 1e-12);
        assertEquals(320d, values.weight(id(rare)), 1e-12);
        assertEquals(6.4d, values.weight(id(coProduct)), 1e-12);
        assertEquals(32d, values.weight(id(coProduct2)), 1e-12);
    }

    @Test
    void dynamicAndCustomRecipesUseConservativeFallbackRatherThanAdvertisedFreeOutput() {
        Item input = new Item("copper");
        Item dynamic = new Item("dynamic-result");
        GenericCrafter scaled = new GenericCrafter("dynamic-crafter");
        scaled.outputItem = new ItemStack(dynamic, 100);
        scaled.consumeItems(new ItemStack(input, 1)).multiplier = build -> 100f;
        Item custom = new Item("custom-result");
        GenericCrafter overridden = new GenericCrafter("custom-crafter");
        overridden.outputItem = new ItemStack(custom, 100);
        overridden.buildType = () -> overridden.new GenericCrafterBuild() {
            @Override
            public void craft() { }
        };
        Item manufactured = new Item("manufactured-from-unknown");
        crafter("unknown-chain", custom, 1, manufactured, 1);
        ResourceValuation values = capture();
        assertEquals(ResourceValuation.UNKNOWN_WEIGHT, values.weight(id(dynamic)));
        assertEquals(ResourceValuation.UNKNOWN_WEIGHT, values.weight(id(custom)));
        assertEquals(values.weight(id(custom)), values.weight(id(manufactured)));
    }

    @Test
    void cheaperBulkAlternativeRepricesPreviouslyReachedDownstreamItems() {
        Item copper = new Item("copper");
        Item lead = new Item("lead");
        Item expensive = new Item("expensive-intermediate");
        Item bulk = new Item("bulk-product");
        Item product = new Item("product");
        Item downstream = new Item("downstream");
        crafter("direct", copper, 1, product, 1);
        crafter("downstream", product, 2, downstream, 1);
        crafter("expensive", lead, 100, expensive, 1);
        crafter("bulk", expensive, 1, bulk, 400);
        crafter("alternative", bulk, 1, product, 1);
        ResourceValuation values = capture();
        assertEquals(0.5d, values.weight(id(product)), 1e-12);
        assertEquals(1d, values.weight(id(downstream)), 1e-12);
    }

    @Test
    void powerOnlyProductionDoesNotBecomeFreeWhenPremiumIsRemoved() {
        Item product = new Item("power-only-product");
        GenericCrafter producer = new GenericCrafter("power-only");
        producer.outputItem = new ItemStack(product, 1);
        producer.consumePower(1f);
        assertEquals(ResourceValuation.UNKNOWN_WEIGHT, capture().weight(id(product)));
    }

    @Test
    void circularMultiplicationCannotDiscountMaterialsIndefinitely() {
        Item copper = new Item("copper");
        Item a = new Item("cycle-a");
        Item b = new Item("cycle-b");
        Item downstream = new Item("cycle-dependent");
        crafter("entry", copper, 1, a, 1);
        crafter("multiply", a, 1, b, 2);
        crafter("return", b, 1, a, 1);
        crafter("dependent", b, 1, downstream, 1);
        ResourceValuation values = capture();
        assertEquals(ResourceValuation.UNRESOLVED_WEIGHT, values.weight(id(a)));
        assertEquals(ResourceValuation.UNRESOLVED_WEIGHT, values.weight(id(b)));
        assertEquals(ResourceValuation.UNRESOLVED_WEIGHT, values.weight(id(downstream)));
        assertEquals(1d, values.weight(id(copper)), 1e-12);
    }

    private GenericCrafter crafter(String name, Item input, int inputCount, Item output, int outputCount) {
        GenericCrafter block = new GenericCrafter(name);
        block.consumeItems(new ItemStack(input, inputCount));
        block.outputItem = new ItemStack(output, outputCount);
        return block;
    }

    private ResourceValuation capture() {
        return ResourceValuationCapture.capture(Vars.content.items(), Vars.content.blocks());
    }

    private static ItemId id(Item item) { return ItemId.of(item.name); }
}
