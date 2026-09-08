package autodrillnext.capability.adapter;

import mindustry.world.Block;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumePower;

/** Transport routing currently supplies static grid power, not fuel, liquid or dynamic consumers. */
final class TransportConsumers {
    private TransportConsumers() {}

    static float powerPerSecond(Block block) {
        block.reinitializeConsumers();
        if (block.consumers != null) {
            for (Consume consumer : block.consumers) {
                if (consumer.optional) continue;
                if (consumer.getClass() != ConsumePower.class || ((ConsumePower) consumer).buffered) {
                    throw new IllegalArgumentException("unsupported mandatory transport consumer: "
                        + consumer.getClass().getName());
                }
            }
        }
        ConsumePower power = block.consPower;
        if (power == null || power.optional) return 0f;
        if (!block.hasPower || !Float.isFinite(power.usage) || power.usage < 0f) {
            throw new IllegalArgumentException("invalid transport power consumption");
        }
        return power.usage * 60f;
    }
}
