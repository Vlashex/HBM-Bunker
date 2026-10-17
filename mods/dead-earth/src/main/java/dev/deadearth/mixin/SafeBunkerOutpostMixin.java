package dev.deadearth.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.deadearth.starter.SafeBunker;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo @Mixin(targets="dev.mostlyharmless.outpostzero.event.logic.StarterBunkerLogic",remap=false)
public abstract class SafeBunkerOutpostMixin {
    @WrapMethod(method="placeStarterBunkerIfNeeded(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/lang/String;Ljava/util/UUID;)V")
    private static void deadEarthCaptureStarter(ServerLevel level,BlockPos origin,String reason,UUID key,Operation<Void> original) {
        SafeBunker.beginCapture(level);boolean success=false;
        try{original.call(level,origin,reason,key);success=true;}finally{SafeBunker.endCapture(level,success);}
    }
}
