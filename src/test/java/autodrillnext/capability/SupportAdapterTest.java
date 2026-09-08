package autodrillnext.capability;

import autodrillnext.capability.adapter.CapabilityContext;
import autodrillnext.capability.adapter.DrillAdapter;
import autodrillnext.capability.spec.DrillSpec;
import autodrillnext.model.SupportRequirement;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.type.Liquid;
import mindustry.world.blocks.production.Drill;
import mindustry.world.consumers.ConsumeLiquidFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupportAdapterTest {
    @BeforeEach
    void initializeMindustryContent() {
        Vars.content = new ContentLoader();
    }

    @Test
    void mandatoryConsumersBecomePerSecondPowerAndLiquidRequirements() {
        Liquid water = new Liquid("water");
        Drill drill = new Drill("consumer-drill");
        drill.consumePower(2f);
        drill.consumeLiquid(water, 0.5f);

        DrillSpec spec = new DrillAdapter().describe(
            drill,
            new CapabilityContext(1f, List.of(water), false, autodrillnext.capability.spec.ResourceValuation.defaults())
        );

        assertTrue(spec.mandatorySupport().stream().anyMatch(requirement ->
            requirement.kind() == SupportRequirement.Kind.POWER && requirement.demandPerSecond() == 120f
        ));
        assertTrue(spec.mandatorySupport().stream().anyMatch(requirement ->
            requirement.kind() == SupportRequirement.Kind.LIQUID
                && requirement.allowedLiquids().get(0).value().equals("water")
                && requirement.demandPerSecond() == 30f
        ));
    }

    @Test
    void optionalLiquidFilterEnumeratesOnlyCurrentMatchingLiquids() {
        Liquid water = new Liquid("water");
        Liquid oil = new Liquid("oil");
        Drill drill = new Drill("booster-drill");
        ConsumeLiquidFilter filter = new ConsumeLiquidFilter(liquid -> liquid == water, 0.25f);
        filter.optional(true, true);
        drill.consume(filter);

        DrillSpec spec = new DrillAdapter().describe(
            drill,
            new CapabilityContext(1f, List.of(water, oil), false, autodrillnext.capability.spec.ResourceValuation.defaults())
        );

        assertEquals(1, spec.optionalSupport().size());
        assertEquals(List.of(autodrillnext.model.LiquidId.of("water")),
            spec.optionalSupport().get(0).requirement().allowedLiquids());
        assertEquals(SupportRequirement.Kind.BOOSTER, spec.optionalSupport().get(0).requirement().kind());
    }
    @Test
    void mandatoryLiquidFilterWithNoRegisteredMatchIsRejected() {
        Liquid water = new Liquid("water");
        Drill drill = new Drill("impossible-liquid-drill");
        drill.consume(new ConsumeLiquidFilter(liquid -> liquid.temperature > 100f, 0.25f));
        assertThrows(IllegalArgumentException.class, () -> new DrillAdapter().describe(
            drill, new CapabilityContext(1f, List.of(water), false, autodrillnext.capability.spec.ResourceValuation.defaults())));
    }

    @Test
    void unavailableOptionalLiquidDoesNotDisableUnboostedMining() {
        Liquid water = new Liquid("water");
        Drill drill = new Drill("optional-liquid-drill");
        ConsumeLiquidFilter optional = new ConsumeLiquidFilter(liquid -> liquid.temperature > 100f, 0.25f);
        optional.optional(true, true);
        drill.consume(optional);
        DrillSpec spec = new DrillAdapter().describe(drill, new CapabilityContext(1f, List.of(water), false, autodrillnext.capability.spec.ResourceValuation.defaults()));
        assertTrue(spec.mandatorySupport().isEmpty());
        assertTrue(spec.optionalSupport().isEmpty());
    }

    @Test
    void baselineIncludesImplicitBoostButNotAnUnavailableOptionalBooster() {
        Drill implicit = new Drill("implicit-boost-drill");
        implicit.liquidBoostIntensity = 1.6f;
        implicit.consumePower(1f);
        DrillSpec implicitSpec = new DrillAdapter().describe(implicit, new CapabilityContext(1f, List.of(), false, autodrillnext.capability.spec.ResourceValuation.defaults()));
        assertEquals(60f / implicit.drillTime * 4f * 1.6f * 1.6f, implicitSpec.productionPerSecond(4), 0.0001f);

        Liquid water = new Liquid("optional-water");
        Drill optional = new Drill("optional-boost-drill");
        optional.liquidBoostIntensity = 1.6f;
        optional.consumeLiquid(water, 0.1f).optional(true, true);
        DrillSpec optionalSpec = new DrillAdapter().describe(optional, new CapabilityContext(1f, List.of(water), false, autodrillnext.capability.spec.ResourceValuation.defaults()));
        assertEquals(60f / optional.drillTime * 4f, optionalSpec.productionPerSecond(4), 0.0001f);
    }

    @Test
    void burstAndBeamImplicitBoostRemainsLinear() {
        var burst = new mindustry.world.blocks.production.BurstDrill("implicit-burst");
        burst.drillTime = 60f;
        burst.liquidBoostIntensity = 2f;
        DrillSpec burstSpec = new DrillAdapter().describe(burst, new CapabilityContext(1f, List.of(), false, autodrillnext.capability.spec.ResourceValuation.defaults()));
        assertEquals(6f, burstSpec.productionPerSecond(3));

        var beam = new mindustry.world.blocks.production.BeamDrill("implicit-beam");
        beam.drillTime = 60f;
        beam.optionalBoostIntensity = 2f;
        DrillSpec beamSpec = new autodrillnext.capability.adapter.BeamDrillAdapter().describe(
            beam, new CapabilityContext(1f, List.of(), false, autodrillnext.capability.spec.ResourceValuation.defaults()));
        assertEquals(6f, beamSpec.productionPerSecond(3));
    }

}
