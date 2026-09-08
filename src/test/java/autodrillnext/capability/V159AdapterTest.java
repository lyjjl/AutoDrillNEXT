package autodrillnext.capability;

import mindustry.Vars;
import mindustry.core.ContentLoader;
import autodrillnext.capability.adapter.BeamDrillAdapter;
import autodrillnext.capability.adapter.BeamNodeAdapter;
import autodrillnext.capability.adapter.ConduitAdapter;
import autodrillnext.capability.adapter.ConveyorAdapter;
import autodrillnext.capability.adapter.DuctAdapter;
import autodrillnext.capability.adapter.DuctBridgeAdapter;
import autodrillnext.capability.adapter.DrillAdapter;
import autodrillnext.capability.adapter.ItemBridgeAdapter;
import autodrillnext.capability.adapter.LiquidBridgeAdapter;
import autodrillnext.capability.adapter.PowerNodeAdapter;
import autodrillnext.capability.adapter.PumpAdapter;
import autodrillnext.capability.adapter.SolidPumpAdapter;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.Duct;
import mindustry.world.blocks.distribution.DuctBridge;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.liquid.Conduit;
import mindustry.world.blocks.liquid.LiquidBridge;
import mindustry.world.blocks.power.BeamNode;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.Pump;
import mindustry.world.blocks.production.SolidPump;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V159AdapterTest {
    @BeforeEach
    void initializeMindustryContentRegistry() {
        Vars.content = new ContentLoader();
    }

    @Test
    void knownMindustryClassFamiliesAreDetectedWithoutNameHeuristics() {
        assertTrue(new DrillAdapter().supports(new Drill("drill")));
        assertTrue(new BeamDrillAdapter().supports(new BeamDrill("beam")));
        assertTrue(new ConveyorAdapter().supports(new Conveyor("conveyor")));
        assertTrue(new DuctAdapter().supports(new Duct("duct")));
        assertTrue(new ItemBridgeAdapter().supports(new ItemBridge("bridge")));
        assertTrue(new DuctBridgeAdapter().supports(new DuctBridge("duct-bridge")));
        assertTrue(new LiquidBridgeAdapter().supports(new LiquidBridge("liquid-bridge")));
        assertTrue(new ConduitAdapter().supports(new Conduit("conduit")));
        assertTrue(new PowerNodeAdapter().supports(new PowerNode("power")));
        assertTrue(new BeamNodeAdapter().supports(new BeamNode("beam-node")));
        assertTrue(new PumpAdapter().supports(new Pump("pump")));
        assertTrue(new SolidPumpAdapter().supports(new SolidPump("solid-pump")));
    }

    @Test
    void familyAdaptersDoNotClaimUnrelatedCustomBlocks() {
        Block unknown = new Block("quantum-teleporter");

        assertFalse(new DrillAdapter().supports(unknown));
        assertFalse(new ConveyorAdapter().supports(unknown));
        assertFalse(new ItemBridgeAdapter().supports(unknown));
        assertFalse(new ConduitAdapter().supports(unknown));
    }
}
