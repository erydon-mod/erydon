package com.oliver.erydon.block;

final class CoverStateChangePolicy {
    private CoverStateChangePolicy() {
    }

    static Action classify(boolean remainsSameBlock, boolean attachmentChanged, boolean sizeChanged) {
        if (!remainsSameBlock) {
            return Action.REMOVED;
        }
        if (attachmentChanged || sizeChanged) {
            return Action.TOPOLOGY_CHANGED;
        }
        return Action.INTERNAL_STATE_ONLY;
    }

    enum Action {
        REMOVED,
        TOPOLOGY_CHANGED,
        INTERNAL_STATE_ONLY
    }
}
