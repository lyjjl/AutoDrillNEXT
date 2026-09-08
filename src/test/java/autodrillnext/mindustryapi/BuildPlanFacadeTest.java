package autodrillnext.mindustryapi;

import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.entities.units.BuildPlan;
import mindustry.world.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertSame;

class BuildPlanFacadeTest {
    @BeforeEach
    void initializeMindustryContent() {
        Vars.content = new ContentLoader();
    }

    @Test
    void compilerCreatesOnlyEnginePlansAtTheFacadeBoundary() {
        Block block = new Block("plan-block");
        block.rotate = true;
        Object config = "target";

        BuildPlan plan = new BuildPlanFacade().compile(block, 12, 7, 3, config);

        assertSame(block, plan.block);
        assertEquals(12, plan.x);
        assertEquals(7, plan.y);
        assertEquals(3, plan.rotation);
        assertSame(config, plan.config);
    }
}
