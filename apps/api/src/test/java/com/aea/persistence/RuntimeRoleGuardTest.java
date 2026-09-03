package com.aea.persistence;

import com.aea.persistence.PersistenceConfig.RuntimeRoleGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The single most dangerous misconfiguration in this system is connecting the
 * application as aea_owner. A table owner bypasses its own RLS policies unless
 * FORCE is set, so tenant isolation would be silently disabled platform-wide
 * while every other test still passed. It must be a startup failure, not a
 * warning someone scrolls past.
 */
class RuntimeRoleGuardTest {

    @Test
    @DisplayName("refuses to start when configured as the schema owner")
    void rejectsOwnerRole() {
        var ex = assertThrows(IllegalStateException.class, () -> new RuntimeRoleGuard("aea_owner"));
        assertTrue(ex.getMessage().contains("aea_app"),
                "the failure must tell an operator what to set instead");
    }

    @Test
    @DisplayName("case and whitespace do not slip past the guard")
    void rejectsOwnerRoleRegardlessOfFormatting() {
        assertThrows(IllegalStateException.class, () -> new RuntimeRoleGuard("AEA_OWNER"));
        assertThrows(IllegalStateException.class, () -> new RuntimeRoleGuard("  aea_owner  "));
        assertThrows(IllegalStateException.class, () -> new RuntimeRoleGuard("Aea_Owner"));
    }

    @Test
    @DisplayName("the runtime role is accepted")
    void acceptsRuntimeRole() {
        assertDoesNotThrow(() -> new RuntimeRoleGuard("aea_app"));
        assertDoesNotThrow(() -> new RuntimeRoleGuard(null));
    }
}
