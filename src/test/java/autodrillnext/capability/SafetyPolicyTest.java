package autodrillnext.capability;

import autodrillnext.model.ContentId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyPolicyTest {
    @Test
    void explicitDenyWinsOverExplicitAllow() {
        ContentId block = ContentId.of("trustedmod:experimental-drill");
        SafetyPolicy policy = SafetyPolicy.builder()
            .allow(block)
            .deny(block)
            .build();

        assertFalse(policy.allowed(block));
        assertTrue(policy.denied(block));
    }
}
