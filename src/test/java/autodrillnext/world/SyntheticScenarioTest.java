package autodrillnext.world;

import autodrillnext.testsupport.SyntheticScenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticScenarioTest {
    @Test
    void translatedScenarioKeepsTheSameOreTopology() {
        SyntheticScenario original = SyntheticScenario.rectangle(8, 5);
        SyntheticScenario shifted = original.translated(137, -41);

        assertEquals(original.oreCellsRelativeToSeed(), shifted.oreCellsRelativeToSeed());
        assertEquals(original.obstacleCellsRelativeToSeed(), shifted.obstacleCellsRelativeToSeed());
    }

    @Test
    void scenarioBuilderCoversTheRequiredObstacleShapes() {
        SyntheticScenario scenario = SyntheticScenario.wallWaterIslandAndNarrowExit();

        assertTrue(scenario.oreCells().size() > 0);
        assertTrue(scenario.solidCells().size() > 0);
        assertTrue(scenario.deepLiquidCells().size() > 0);
        assertTrue(scenario.exitRegionCount() == 12);
    }
}
