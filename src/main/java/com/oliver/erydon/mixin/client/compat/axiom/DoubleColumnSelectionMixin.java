package com.oliver.erydon.mixin.client.compat.axiom;

import com.oliver.erydon.client.compat.AxiomColumnSelection;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.buildertools.BuilderToolSelectionState", remap = false)
abstract class DoubleColumnSelectionMixin {
    @Unique private Object erydon$unexpandedSelection;
    @Unique private boolean erydon$restoringSelection;
    @Unique private int erydon$selectionEditDepth;

    // Let Axiom set its corners, extend, nudge and magic-select normally. Remove only
    // our previous completion before an edit, so shrinking cannot retain old sections.
    @Inject(method = {"leftClick", "rightClick", "middleClick", "setPos1", "setPos2", "nudge"},
            at = @At("HEAD"), remap = false)
    private void erydon$beforeSelectionChange(CallbackInfo callback) {
        if (erydon$restoringSelection) return;
        // Guard nested selection edits so an inner completion cannot lose the
        // restore point belonging to the outer edit.
        if (++erydon$selectionEditDepth != 1 || erydon$unexpandedSelection == null) return;
        Object original = erydon$unexpandedSelection;
        erydon$unexpandedSelection = null;
        erydon$restoringSelection = true;
        try {
            AxiomColumnSelection.restore(this, original);
        } finally {
            erydon$restoringSelection = false;
        }
    }

    @Inject(method = {"leftClick", "rightClick", "middleClick", "setPos1", "setPos2", "nudge"},
            at = @At("RETURN"), remap = false)
    private void erydon$completeIntersectedSections(CallbackInfo callback) {
        if (erydon$restoringSelection) return;
        if (--erydon$selectionEditDepth != 0) return;
        erydon$completeSelection();
    }

    @Inject(method = "restoreFrom", at = @At("RETURN"), remap = false)
    private void erydon$completeRestoredSections(CallbackInfo callback) {
        if (!erydon$restoringSelection && erydon$selectionEditDepth == 0) erydon$completeSelection();
    }

    // Recheck membership when a move/clone/delete starts, including selections whose
    // blocks changed after the last corner click or were restored by undo.
    @Inject(method = "createSelectionBuffer", at = @At("HEAD"), remap = false)
    private void erydon$refreshBeforeOperation(CallbackInfoReturnable<Object> callback) {
        if (erydon$restoringSelection) return;
        erydon$restoringSelection = true;
        try {
            if (erydon$unexpandedSelection != null)
                AxiomColumnSelection.restore(this, erydon$unexpandedSelection);
        } finally {
            erydon$restoringSelection = false;
        }
        erydon$completeSelection();
    }

    @Unique private void erydon$completeSelection() {
        var world = MinecraftClient.getInstance().world;
        if (world == null) return;
        erydon$restoringSelection = true;
        try {
            erydon$unexpandedSelection = AxiomColumnSelection.complete(this, world);
        } finally {
            erydon$restoringSelection = false;
        }
    }

    @Inject(method = {"resetSelection", "restoreFrom"}, at = @At("HEAD"), remap = false)
    private void erydon$discardPreviousCompletion(CallbackInfo callback) {
        if (!erydon$restoringSelection) erydon$unexpandedSelection = null;
    }

    // Undo/restoration must keep the user's original corners and magic selection;
    // completion is recalculated by restoreFrom against the restored world.
    @Inject(method = "getSelectionRestore", at = @At("HEAD"), cancellable = true, remap = false)
    private void erydon$saveOriginalSelection(CallbackInfoReturnable<Object> callback) {
        if (!erydon$restoringSelection && erydon$unexpandedSelection != null)
            callback.setReturnValue(AxiomColumnSelection.copyRestore(erydon$unexpandedSelection));
    }
}
