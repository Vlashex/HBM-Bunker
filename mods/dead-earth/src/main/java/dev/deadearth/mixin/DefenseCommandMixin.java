package dev.deadearth.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.ParseResults;
import dev.deadearth.defense.DefensePolicy;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Commands.class)
public abstract class DefenseCommandMixin {
    @WrapMethod(method="performCommand")
    private void defenseAdmin(ParseResults<CommandSourceStack> parsed,String command,Operation<Void> nativeCall) {
        if(parsed.getContext().getSource().hasPermission(2))DefensePolicy.scoped(DefensePolicy.Scope.ADMIN,()->nativeCall.call(parsed,command));
        else nativeCall.call(parsed,command);
    }
}
