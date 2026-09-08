package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.PowerConnectorSpec;
import mindustry.world.Block;
import mindustry.world.blocks.power.PowerNode;

public final class PowerNodeAdapter implements CapabilityAdapter<PowerNode, PowerConnectorSpec> {
    @Override
    public boolean supports(Block block) {
        return block instanceof PowerNode;
    }

    @Override
    public PowerConnectorSpec describe(PowerNode block, CapabilityContext context) {
        return new PowerConnectorSpec(
            AdapterSupport.id(block),
            block.size,
            AdapterSupport.positiveOrZero(block.laserRange),
            block.maxNodes,
            false,
            AdapterSupport.cost(block, context)
        );
    }
}
