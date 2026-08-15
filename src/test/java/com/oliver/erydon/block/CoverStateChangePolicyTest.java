package com.oliver.erydon.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoverStateChangePolicyTest {
    @Test
    void internalStateChangesDoNotRefreshTheCoverPlane() {
        assertEquals(
                CoverStateChangePolicy.Action.INTERNAL_STATE_ONLY,
                CoverStateChangePolicy.classify(true, false, false));
    }

    @Test
    void removalRefreshesTheOldCoverPlane() {
        assertEquals(
                CoverStateChangePolicy.Action.REMOVED,
                CoverStateChangePolicy.classify(false, false, false));
    }

    @Test
    void attachmentChangesRefreshTheCoverPlane() {
        assertEquals(
                CoverStateChangePolicy.Action.TOPOLOGY_CHANGED,
                CoverStateChangePolicy.classify(true, true, false));
    }

    @Test
    void sizeChangesRefreshTheCoverPlane() {
        assertEquals(
                CoverStateChangePolicy.Action.TOPOLOGY_CHANGED,
                CoverStateChangePolicy.classify(true, false, true));
    }
}
