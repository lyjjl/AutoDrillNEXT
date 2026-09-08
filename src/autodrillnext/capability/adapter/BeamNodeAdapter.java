package autodrillnext.capability.adapter;

import autodrillnext.capability.spec.PowerConnectorSpec;
import mindustry.world.Block;
import mindustry.world.blocks.power.BeamNode;

public final class BeamNodeAdapter implements CapabilityAdapter<BeamNode, PowerConnectorSpec> {
    @Override
    public boolean supports(Block block) {
        return block instanceof BeamNode;
    }

    @Override
    public PowerConnectorSpec describe(BeamNode block, CapabilityContext context) {
        return new PowerConnectorSpec(
            AdapterSupport.id(block),
            block.size,
            AdapterSupport.positiveOrZero(block.range),
            0,
            true,
            AdapterSupport.cost(block, context)
        );
    }
}
