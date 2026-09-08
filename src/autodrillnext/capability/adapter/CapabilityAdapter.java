package autodrillnext.capability.adapter;

import mindustry.world.Block;

public interface CapabilityAdapter<B extends Block, S> {
    boolean supports(Block block);

    S describe(B block, CapabilityContext context);

    default String id() {
        return getClass().getSimpleName();
    }
}
