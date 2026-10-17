package dev.deadearth.mixin;

import dev.deadearth.compat.InventorySortSafety;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="cpw.mods.inventorysorter.ServerHandler", remap=false)
public abstract class InventorySortSafetyMixin {
    // 1.21.1-24.0.24: already on the server thread, before getSlot/ContainerContext/execute.
    // Fail injection if an update moves this worker; never silently lose the menu boundary.
    @Inject(method="lambda$onMessage$0(Lcpw/mods/inventorysorter/Network$ActionMessage;Lnet/minecraft/server/level/ServerPlayer;)V",
            at=@At("HEAD"), cancellable=true, require=1, remap=false)
    private static void deadEarth$guardNativeAction(@Coerce Object message, ServerPlayer player, CallbackInfo ci) {
        if (!InventorySortSafety.allows(player)) ci.cancel();
    }
}
