package com.oliver.erydon.compat.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import net.minecraft.server.world.ServerWorld;

final class WorldEditCompatListener {

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE || event.getWorld() == null) {
            return;
        }

        if (!(FabricAdapter.adapt(event.getWorld()) instanceof ServerWorld serverWorld)) {
            return;
        }

        event.setExtent(new WorldEditRecalcExtent(event.getExtent(), serverWorld));
    }
}
