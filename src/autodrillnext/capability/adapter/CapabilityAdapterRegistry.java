package autodrillnext.capability.adapter;

import mindustry.world.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CapabilityAdapterRegistry {
    private final List<CapabilityAdapter<?, ?>> adapters = new ArrayList<>();

    public CapabilityAdapterRegistry register(CapabilityAdapter<?, ?> adapter) {
        adapters.add(Objects.requireNonNull(adapter, "adapter"));
        return this;
    }

    public CapabilityAdapter<?, ?> find(Block block) {
        for (CapabilityAdapter<?, ?> adapter : adapters) {
            if (adapter.supports(block)) return adapter;
        }
        return null;
    }

    public List<CapabilityAdapter<?, ?>> adapters() {
        return List.copyOf(adapters);
    }

    public static CapabilityAdapterRegistry v159Defaults() {
        return new CapabilityAdapterRegistry()
            .register(new BeamDrillAdapter())
            .register(new DrillAdapter())
            .register(new DuctBridgeAdapter())
            .register(new LiquidBridgeAdapter())
            .register(new ItemBridgeAdapter())
            .register(new DuctAdapter())
            .register(new ConveyorAdapter())
            .register(new BeamNodeAdapter())
            .register(new PowerNodeAdapter())
            .register(new SolidPumpAdapter())
            .register(new PumpAdapter())
            .register(new ConduitAdapter());
    }
}
