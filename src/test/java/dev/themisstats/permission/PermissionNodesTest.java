package dev.themisstats.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the permission nodes.
 *
 * <p>The nodes are the contract with a permission mod. The tests make sure that the names are
 * stable, that they all share the one prefix and that the default provider keeps the promised
 * split between an ordinary player and an administrator.</p>
 */
class PermissionNodesTest {

    @Test
    void everyNodeSharesTheOnePrefix() {
        for (String node : PermissionNodes.DEFAULT_PLAYER_NODES) {
            assertTrue(node.startsWith(PermissionNodes.PREFIX), node);
        }
        for (String node : PermissionNodes.DEFAULT_ADMIN_NODES) {
            assertTrue(node.startsWith(PermissionNodes.PREFIX), node);
        }
    }

    @Test
    void theNodeNamesAreTheExpectedOnes() {
        assertEquals("themisstats.", PermissionNodes.PREFIX);
        assertEquals("themisstats.query", PermissionNodes.QUERY);
        assertEquals("themisstats.view_details", PermissionNodes.VIEW_DETAILS);
        assertEquals("themisstats.sidebar", PermissionNodes.SIDEBAR);
        assertEquals("themisstats.watch", PermissionNodes.WATCH);
        assertEquals("themisstats.export", PermissionNodes.EXPORT);
        assertEquals("themisstats.manage", PermissionNodes.MANAGE);
    }

    @Test
    void anOrdinaryPlayerOnlyGetsTheQueryNodes() {
        assertTrue(PermissionNodes.DEFAULT_PLAYER_NODES.contains(PermissionNodes.QUERY));
        assertTrue(PermissionNodes.DEFAULT_PLAYER_NODES.contains(PermissionNodes.SIDEBAR));
        assertFalse(PermissionNodes.DEFAULT_PLAYER_NODES.contains(PermissionNodes.MANAGE));
        assertFalse(PermissionNodes.DEFAULT_PLAYER_NODES.contains(PermissionNodes.EXPORT));
        assertFalse(PermissionNodes.DEFAULT_PLAYER_NODES.contains(PermissionNodes.VIEW_DETAILS));
        assertFalse(PermissionNodes.DEFAULT_PLAYER_NODES.contains(PermissionNodes.WATCH));
    }

    @Test
    void anAdministratorGetsEveryNode() {
        assertTrue(PermissionNodes.DEFAULT_ADMIN_NODES.containsAll(PermissionNodes.DEFAULT_PLAYER_NODES));
        assertEquals(PermissionNodes.DEFAULT_PLAYER_NODES.size() + 4,
                PermissionNodes.DEFAULT_ADMIN_NODES.size());
        for (String node : new String[]{PermissionNodes.QUERY, PermissionNodes.VIEW_DETAILS,
                PermissionNodes.SIDEBAR, PermissionNodes.WATCH, PermissionNodes.EXPORT,
                PermissionNodes.MANAGE}) {
            assertTrue(PermissionNodes.DEFAULT_ADMIN_NODES.contains(node), node);
        }
    }

    @Test
    void theNodeNameIsNormalizedToLowerCase() {
        assertEquals("themisstats.manage", PermissionNodes.normalize("ThemisStats.MANAGE"));
        assertEquals("", PermissionNodes.normalize(null));
    }

    @Test
    void theDefaultProviderIsTheOperatorLevelOne() {
        assertNotNull(PermissionNodes.provider());
        assertTrue(PermissionNodes.provider() instanceof PermissionNodes.OperatorLevelProvider);
    }

    @Test
    void aPermissionModCanTakeOver() {
        PermissionNodes.Provider original = PermissionNodes.provider();
        try {
            PermissionNodes.setProvider((player, node) -> node.equals(PermissionNodes.WATCH));
            assertNotNull(PermissionNodes.provider());
            assertFalse(PermissionNodes.provider() instanceof PermissionNodes.OperatorLevelProvider);
        } finally {
            PermissionNodes.setProvider(original);
        }
        assertTrue(PermissionNodes.provider() instanceof PermissionNodes.OperatorLevelProvider);
    }

    @Test
    void aNullProviderFallsBackToTheDefault() {
        PermissionNodes.Provider original = PermissionNodes.provider();
        try {
            PermissionNodes.setProvider(null);
            assertTrue(PermissionNodes.provider() instanceof PermissionNodes.OperatorLevelProvider);
        } finally {
            PermissionNodes.setProvider(original);
        }
    }

    @Test
    void aMissingPlayerNeverHasANode() {
        assertFalse(PermissionNodes.has(null, PermissionNodes.QUERY));
    }
}
