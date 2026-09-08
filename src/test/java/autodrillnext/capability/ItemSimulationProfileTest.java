package autodrillnext.capability;

import autodrillnext.capability.adapter.CapabilityContext;
import autodrillnext.capability.adapter.ConveyorAdapter;
import autodrillnext.capability.adapter.DuctAdapter;
import autodrillnext.capability.adapter.DuctBridgeAdapter;
import autodrillnext.capability.adapter.ItemBridgeAdapter;
import autodrillnext.capability.spec.CostVector;
import autodrillnext.capability.spec.ItemTransportSpec;
import autodrillnext.model.ContentId;
import autodrillnext.simulation.SimulationFidelity;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.BufferedItemBridge;
import mindustry.world.blocks.distribution.Duct;
import mindustry.world.blocks.distribution.DuctBridge;
import mindustry.world.blocks.distribution.ItemBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemSimulationProfileTest {
    private final CapabilityContext context = new CapabilityContext(1f, List.of(), false, autodrillnext.capability.spec.ResourceValuation.defaults());

    @BeforeEach
    void initializeMindustryContent() {
        Vars.content = new ContentLoader();
    }

    @Test
    void knownItemTransportFamiliesDeclareBoundedShadowModels() {
        Conveyor conveyorBlock = new Conveyor("conveyor");
        conveyorBlock.speed = 0.03f;
        ItemBridge bridgeBlock = bridge("bridge");
        ItemTransportSpec conveyor = new ConveyorAdapter().describe(conveyorBlock, context);
        ItemTransportSpec bridge = new ItemBridgeAdapter().describe(bridgeBlock, context);

        assertEquals(SimulationFidelity.BOUNDED_MODEL, conveyor.simulationFidelity());
        assertEquals(SimulationFidelity.BOUNDED_MODEL, bridge.simulationFidelity());
    }

    @Test
    void legacyTransportSpecIsExplicitlyUnsupportedForSimulation() {
        ItemTransportSpec transport = new ItemTransportSpec(
            ContentId.of("test:transport"), 1, 1f, 0, false, CostVector.empty()
        );

        assertEquals(SimulationFidelity.UNSUPPORTED, transport.simulationFidelity());
    }

    @Test
    void poweredBridgeExposesItsMandatoryPowerDemand() {
        ItemBridge block = bridge("powered-bridge");
        block.consumePower(0.3f);
        ItemTransportSpec spec = new ItemBridgeAdapter().describe(block, context);
        assertEquals(18f, spec.powerPerSecond(), 0.0001f);
    }

    @Test
    void mandatoryLiquidConsumerCannotMasqueradeAsUnpoweredTransport() {
        ItemBridge block = bridge("liquid-dependent-bridge");
        block.consumeLiquid(new mindustry.type.Liquid("required-liquid"), 0.1f);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new ItemBridgeAdapter().describe(block, context));
    }

    @Test
    void mandatoryItemConsumerCannotMasqueradeAsConveyor() {
        Conveyor block = new Conveyor("fuel-dependent-conveyor");
        block.speed = 0.03f;
        block.consumeItems(new mindustry.type.ItemStack(new mindustry.type.Item("required-fuel"), 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new ConveyorAdapter().describe(block, context));
    }

    @Test
    void conveyorMovementCapacityDoesNotDependOnDisplayMetadata() {
        Conveyor block = new Conveyor("data-only-mod-belt");
        block.speed = 0.05f;
        float capacity = new ConveyorAdapter().describe(block, context).capacityPerSecond();
        assertTrue(capacity > 0f);
        block.displayedSpeed = 1000f;
        assertEquals(capacity, new ConveyorAdapter().describe(block, context).capacityPerSecond());
        block.speed = 0.01f;
        assertTrue(new ConveyorAdapter().describe(block, context).capacityPerSecond() < capacity);
    }

    @Test
    void bufferedBridgeCapacityIncludesTransitBufferAndTransferTimer() {
        BufferedItemBridge block = new BufferedItemBridge("slow-buffered-bridge");
        block.range = 4;
        block.bufferCapacity = 1;
        block.speed = 120f;
        block.displayedSpeed = 1000f;
        float limited = new ItemBridgeAdapter().describe(block, context).capacityPerSecond();
        assertTrue(limited > 0f && limited <= 0.5f);
        block.bufferCapacity = 100;
        float fullBuffer = new ItemBridgeAdapter().describe(block, context).capacityPerSecond();
        assertTrue(fullBuffer > limited && fullBuffer <= 15f);
    }

    @Test
    void nonmovingTransportCannotReceiveAPositiveDisplayOnlyCapacity() {
        Conveyor conveyor = new Conveyor("stopped-conveyor");
        conveyor.displayedSpeed = 100f;
        assertThrows(IllegalArgumentException.class, () -> new ConveyorAdapter().describe(conveyor, context));
        Duct duct = new Duct("invalid-duct");
        duct.speed = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> new DuctAdapter().describe(duct, context));
        DuctBridge ductBridge = new DuctBridge("stopped-duct-bridge");
        ductBridge.speed = 0f;
        assertThrows(IllegalArgumentException.class, () -> new DuctBridgeAdapter().describe(ductBridge, context));
        ItemBridge bridge = bridge("stopped-item-bridge");
        bridge.transportTime = 0f;
        assertThrows(IllegalArgumentException.class, () -> new ItemBridgeAdapter().describe(bridge, context));
    }

    @Test
    void customAcceptanceAndLinkRulesRequireAnExplicitAdapter() {
        Duct duct = new Duct("filtered-duct");
        duct.buildType = () -> duct.new DuctBuild() {
            @Override
            public boolean acceptItem(mindustry.gen.Building source, mindustry.type.Item item) {
                return false;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new DuctAdapter().describe(duct, context));
        ItemBridge bridge = new ItemBridge("custom-link-bridge") {
            @Override
            public boolean positionsValid(int x1, int y1, int x2, int y2) {
                return false;
            }
        };
        bridge.range = 4;
        bridge.transportTime = 2f;
        assertThrows(IllegalArgumentException.class, () -> new ItemBridgeAdapter().describe(bridge, context));
    }

    @Test
    void cosmeticOnlyBuildSubclassesKeepTheirInheritedTransportMechanics() {
        Conveyor conveyor = new Conveyor("cosmetic-conveyor");
        conveyor.speed = 0.03f;
        conveyor.buildType = () -> conveyor.new ConveyorBuild() {
            @Override
            public void draw() { }
        };
        assertTrue(new ConveyorAdapter().describe(conveyor, context).capacityPerSecond() > 0f);
    }

    private ItemBridge bridge(String name) {
        ItemBridge block = new ItemBridge(name);
        block.range = 4;
        block.transportTime = 2f;
        return block;
    }
}
