package autodrillnext.capability;

import autodrillnext.model.ContentId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompatibilityRegistryTest {
    static class KnownTransport {
    }

    static class FastKnownTransport extends KnownTransport {
    }

    @Test
    void mostSpecificRegisteredFamilyWins() {
        CompatibilityRegistry registry = new CompatibilityRegistry();
        registry.register(KnownTransport.class, "generic-transport");
        registry.register(FastKnownTransport.class, "fast-transport");

        assertEquals("fast-transport", registry.adapterIdFor(FastKnownTransport.class));
        assertEquals("generic-transport", registry.adapterIdFor(KnownTransport.class));
    }

    @Test
    void contentPolicyIdentityRemainsSeparateFromClassCompatibility() {
        ContentId content = ContentId.of("mod:fast-transport");
        SafetyPolicy policy = SafetyPolicy.builder().deny(content).build();

        assertEquals(true, policy.denied(content));
        assertEquals("fast-transport", new CompatibilityRegistry()
            .registerAndReturn(FastKnownTransport.class, "fast-transport")
            .adapterIdFor(FastKnownTransport.class));
    }
}
